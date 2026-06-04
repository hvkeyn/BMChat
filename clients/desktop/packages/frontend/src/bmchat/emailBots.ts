import { C } from '@deltachat/jsonrpc-client'

import { BackendRemote } from '../backend-com'
import { runtime } from '@deltachat-desktop/runtime-interface'

export interface EmailBotPublic {
  id: string
  name: string
  displayName?: string | null
  enabled: boolean
}

export async function listEmailBots(): Promise<EmailBotPublic[]> {
  if (runtime.getRuntimeInfo().target !== 'electron') {
    return []
  }
  try {
    const bots = await runtime.bmchatBotsInvoke('bmchat:emailbots:list')
    return Array.isArray(bots) ? bots : []
  } catch {
    return []
  }
}

/** Match configured email bots against sidebar search (e.g. "@newsbot"). */
export function matchEmailBots(
  queryStr: string,
  bots: EmailBotPublic[]
): EmailBotPublic[] {
  const trimmed = queryStr.trim().toLowerCase()
  if (!trimmed) return []
  const bare = trimmed.startsWith('@') ? trimmed.slice(1) : trimmed
  if (!bare) return []
  return bots.filter(b => {
    if (!b.enabled) return false
    const n = b.name.toLowerCase()
    return n === bare || n.startsWith(bare) || `@${n}` === trimmed
  })
}

export async function findSavedMessagesChatId(
  accountId: number
): Promise<number | null> {
  const entries = await BackendRemote.rpc.getChatlistEntries(
    accountId,
    C.DC_GCL_NO_SPECIALS,
    null,
    null
  )
  for (const entry of entries) {
    const info = await BackendRemote.rpc.getBasicChatInfo(accountId, entry.id)
    if (info.isSelfTalk) {
      return entry.id
    }
  }
  return null
}

/** Open «Сохранённые сообщения» with a prefilled @bot /start command. */
export async function openEmailBotChat(
  accountId: number,
  botName: string,
  selectChat: (chatId: number) => void
): Promise<boolean> {
  const chatId = await findSavedMessagesChatId(accountId)
  if (!chatId) {
    return false
  }
  const text = `@${botName} /start`
  window.__setDraftRequest = {
    accountId,
    chatId,
    text,
  }
  selectChat(chatId)
  window.__checkSetDraftRequest?.()
  return true
}
