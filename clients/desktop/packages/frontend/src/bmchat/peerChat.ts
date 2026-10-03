import { C, type T } from '@deltachat/jsonrpc-client'

import { BackendRemote } from '../backend-com'
import { rewriteInviteLink } from '../../../shared/util'
import { textMessageData } from './textMessage'

const PENDING_KEY = 'ui.bmchat.pending_plaintext'

export class HeldForEncryptionError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'HeldForEncryptionError'
  }
}

function sameAddr(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase()
}

async function peerAddress(
  accountId: number,
  chatId: number
): Promise<string | null> {
  const ids = await BackendRemote.rpc.getChatContacts(accountId, chatId)
  if (ids.length !== 1 || ids[0] <= C.DC_CHAT_ID_LAST_SPECIAL) return null
  const contact = await BackendRemote.rpc.getContact(accountId, ids[0])
  const addr = contact.address?.trim()
  return addr ? addr : null
}

async function isPlainSingle(
  accountId: number,
  chatId: number
): Promise<boolean> {
  if (chatId <= C.DC_CHAT_ID_LAST_SPECIAL) return false
  const chat = await BackendRemote.rpc.getBasicChatInfo(accountId, chatId)
  if (chat.chatType !== 'Single') return false
  if (
    chat.isEncrypted ||
    chat.isSelfTalk ||
    chat.isDeviceChat
  ) {
    return false
  }
  return (await peerAddress(accountId, chatId)) != null
}

/** Encrypted 1:1 chat for this address, or null. Unarchives it when found. */
export async function encryptedChatForAddress(
  accountId: number,
  addr: string
): Promise<number | null> {
  const want = addr.trim()
  if (!want) return null
  const ids = new Set<number>()
  for (const flags of [0, C.DC_GCL_ADDRESS]) {
    const found = await BackendRemote.rpc.getContactIds(accountId, flags, want)
    for (const id of found) ids.add(id)
  }
  const lookedUp = await BackendRemote.rpc.lookupContactIdByAddr(accountId, want)
  if (lookedUp) ids.add(lookedUp)
  for (const id of ids) {
    if (id <= C.DC_CHAT_ID_LAST_SPECIAL) continue
    const contact = await BackendRemote.rpc.getContact(accountId, id)
    if (!sameAddr(contact.address, want)) continue
    const chatId = await BackendRemote.rpc.getChatIdByContactId(accountId, id)
    if (!chatId) continue
    const chat = await BackendRemote.rpc.getBasicChatInfo(accountId, chatId)
    if (chat.chatType !== 'Single' || !chat.isEncrypted) continue
    if (chat.archived) {
      await BackendRemote.rpc.setChatVisibility(accountId, chatId, 'Normal')
    }
    return chatId
  }
  return null
}

export async function preferEncryptedChat(
  accountId: number,
  chatId: number
): Promise<number> {
  if (!(await isPlainSingle(accountId, chatId))) return chatId
  const addr = await peerAddress(accountId, chatId)
  if (!addr) return chatId
  return (await encryptedChatForAddress(accountId, addr)) ?? chatId
}

export async function ensureInvite(
  accountId: number,
  chatId: number,
  inviteBody: (link: string) => string
): Promise<void> {
  if (!(await isPlainSingle(accountId, chatId))) return
  const key = `ui.bmchat.invited.${chatId}`
  if ((await BackendRemote.rpc.getConfig(accountId, key)) === '1') return
  const [qrCode] = await BackendRemote.rpc.getChatSecurejoinQrCodeSvg(
    accountId,
    null
  )
  const invite = rewriteInviteLink(qrCode)
  if (!invite) return
  const sent = await BackendRemote.rpc.sendMsg(
    accountId,
    chatId,
    textMessageData(inviteBody(invite))
  )
  if (sent) {
    await BackendRemote.rpc.setConfig(accountId, key, '1')
  }
}

async function queueText(
  accountId: number,
  chatId: number,
  text: string
): Promise<void> {
  const raw = await BackendRemote.rpc.getConfig(accountId, PENDING_KEY)
  let pending: Record<string, string> = {}
  if (raw) {
    try {
      pending = JSON.parse(raw) as Record<string, string>
    } catch {
      pending = {}
    }
  }
  pending[String(chatId)] = text
  await BackendRemote.rpc.setConfig(
    accountId,
    PENDING_KEY,
    JSON.stringify(pending)
  )
}

/** Deliver texts that were waiting once an encrypted chat exists. */
export async function flushPendingPlaintext(accountId: number): Promise<void> {
  const raw = await BackendRemote.rpc.getConfig(accountId, PENDING_KEY)
  if (!raw) return
  let pending: Record<string, string>
  try {
    pending = JSON.parse(raw) as Record<string, string>
  } catch {
    return
  }
  let changed = false
  for (const [key, text] of Object.entries(pending)) {
    const chatId = Number(key)
    if (!Number.isFinite(chatId)) {
      delete pending[key]
      changed = true
      continue
    }
    const target = await preferEncryptedChat(accountId, chatId)
    const chat = await BackendRemote.rpc.getBasicChatInfo(accountId, target)
    if (!chat.isEncrypted) continue
    if (text.trim()) {
      await BackendRemote.rpc.sendMsg(
        accountId,
        target,
        textMessageData(text)
      )
      await BackendRemote.rpc.removeDraft(accountId, chatId)
      if (target !== chatId) {
        await BackendRemote.rpc.removeDraft(accountId, target)
      }
    }
    delete pending[key]
    changed = true
  }
  if (changed) {
    const left = Object.keys(pending).length
    await BackendRemote.rpc.setConfig(
      accountId,
      PENDING_KEY,
      left ? JSON.stringify(pending) : ''
    )
  }
}

/**
 * Send into the encrypted chat when one already exists for this address.
 * Otherwise hold a plain 1:1 message and send only the SecureJoin invite.
 */
export async function prepareOutgoingChat(
  accountId: number,
  chatId: number,
  text: string | null,
  inviteBody: (link: string) => string
): Promise<{ chatId: number; held: boolean }> {
  const target = await preferEncryptedChat(accountId, chatId)
  if (target !== chatId) return { chatId: target, held: false }
  if (!(await isPlainSingle(accountId, chatId))) {
    return { chatId, held: false }
  }
  await ensureInvite(accountId, chatId, inviteBody)
  if (text && text.trim()) await queueText(accountId, chatId, text)
  return { chatId, held: true }
}
