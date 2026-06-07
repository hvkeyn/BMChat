// BMChat desktop email-bots backend — a port of the Android
// `org.thoughtcrime.securesms.emailbots` stack to the Electron main
// process.
//
// Email bots automate replies over the user's own e-mail transport: every
// incoming message is inspected and, when it carries a command for one of
// the account's registered bots, BMChat sends the configured reply through
// the same core (i.e. the user's IMAP/SMTP credentials).
//
// On desktop the main process already receives the core event stream (see
// `controller.ts` -> `handleEventResponse`), so the dispatcher subscribes to
// `IncomingMsg` on the JSON-RPC remote and runs regardless of whether the
// window is focused. Webhook calls happen here too (the renderer has DNS
// disabled).
//
// Supported activation grammar (mirrors Android / Telegram):
//   * "/command arg"            — when the account has exactly one bot,
//   * "@botname /command arg"   — selects a specific bot,
//   * "@botname free text"      — synthetic "default" command.
// A "/start" gate subscribes the sender; unknown senders are dropped until
// they /start, exactly like Telegram bots.
//
// Not ported (kept out of scope for the first desktop release): the
// developer-email round-trip forwarding that relays updates to a remote
// developer mailbox. Static command tables and HTTP webhooks are supported.

import { app, ipcMain } from 'electron'
import { promises as fs } from 'fs'
import * as http from 'http'
import * as https from 'https'
import { join } from 'path'
import { randomUUID } from 'crypto'

import { getConfigPath } from './application-constants.js'
import { getLogger } from '../../shared/logger.js'
import { getDCJsonrpcRemote, DCJsonrpcRemoteInitializedP } from './ipc.js'
import { openJson, sealJson } from './bmchat-email-bot-crypto.js'
import { tryIngestTelegramBotSync } from './bmchat-telegram-bots.js'

const log = getLogger('main/bmchat-email-bots')

const STORE_FILE = 'bmchat-email-bots.json'
const UI_CONFIG_KEY = 'ui.bmchat.email_bots'
const SYNC_MARKER = 'BMCHAT-BOT-SYNC v1'
const DC_CONTACT_ID_SELF = 1
const MIN_SYNC_PUBLISH_MS = 8_000
let lastSyncPublishMs = 0

interface CommandEntry {
  k: string
  v: string
}

interface EmailBot {
  id: string
  name: string
  description?: string | null
  ownerAccountId: number
  enabled: boolean
  commands: CommandEntry[]
  webhookUrl?: string | null
  token: string
  displayName?: string | null
  developerEmail?: string | null
  subscribedUsers: string[]
  /** Pseudo-contact (@bots.bmchat.local) for a dedicated 1:1 bot chat. */
  botContactId?: number
  botChatId?: number
  /** Group/channel chats that receive mirrored bot replies (Telegram-style). */
  attachedChatIds?: number[]
  createdAtMs: number
  lastReplyAtMs: number
  totalReplies: number
}

let bots: EmailBot[] = []
let initialised = false

function storePath(): string {
  return join(getConfigPath(), STORE_FILE)
}

async function loadStore(): Promise<void> {
  try {
    const raw = await fs.readFile(storePath(), 'utf8')
    const parsed = JSON.parse(raw)
    const arr = Array.isArray(parsed?.bots) ? parsed.bots : []
    bots = arr
      .map((b: unknown) => sanitizeBot(b))
      .filter((b): b is EmailBot => b != null)
  } catch (e: any) {
    if (e?.code !== 'ENOENT') log.warn('Failed to read email bots store', e)
    bots = []
  }
}

async function readUiConfigForAccount(accountId: number): Promise<EmailBot[]> {
  try {
    const raw = await getDCJsonrpcRemote().rpc.getConfig(
      accountId,
      UI_CONFIG_KEY
    )
    const opened = await openJson(accountId, raw)
    if (!opened) return []
    const root = JSON.parse(opened)
    const arr = root?.bots
    if (!Array.isArray(arr)) return []
    return arr
      .map((b: unknown) => sanitizeBot(b))
      .filter((b): b is EmailBot => b != null)
  } catch (e) {
    log.warn('readUiConfigForAccount failed account=%s', accountId, e)
    return []
  }
}

/** Merge local JSON cache + encrypted ui-config on every account. */
async function reloadStoreMerged(): Promise<void> {
  await loadStore()
  const merged = new Map<string, EmailBot>()
  for (const b of bots) merged.set(b.id, b)
  try {
    await DCJsonrpcRemoteInitializedP
    const accountIds = await getDCJsonrpcRemote().rpc.getAllAccountIds()
    for (const accountId of accountIds) {
      for (const b of await readUiConfigForAccount(accountId)) {
        merged.set(b.id, b)
      }
    }
  } catch (e) {
    log.warn('reloadStoreMerged: ui-config read failed', e)
  }
  bots = Array.from(merged.values())
}

async function persistUiConfigForAccount(
  accountId: number,
  publishSync: boolean
): Promise<void> {
  const accountBots = bots.filter(b => b.ownerAccountId === accountId)
  const wrapper = {
    bots: accountBots,
    updatedAtMs: Date.now(),
  }
  const sealed = await sealJson(accountId, JSON.stringify(wrapper))
  await getDCJsonrpcRemote().rpc.setConfig(accountId, UI_CONFIG_KEY, sealed)
  if (publishSync) {
    await publishBotSyncNow(accountId, JSON.stringify(wrapper))
  }
}

async function persistAllUiConfig(publishSync: boolean): Promise<void> {
  await DCJsonrpcRemoteInitializedP
  const accountIds = new Set<number>(
    await getDCJsonrpcRemote().rpc.getAllAccountIds()
  )
  for (const b of bots) {
    if (b.ownerAccountId > 0) accountIds.add(b.ownerAccountId)
  }
  for (const accountId of accountIds) {
    if (accountId > 0) {
      await persistUiConfigForAccount(accountId, publishSync)
    }
  }
}

async function publishBotSyncNow(
  accountId: number,
  plainJson: string
): Promise<void> {
  try {
    const rpc = getDCJsonrpcRemote().rpc
    let selfChat = await rpc.getChatIdByContactId(
      accountId,
      DC_CONTACT_ID_SELF
    )
    if (!selfChat || selfChat <= 0) {
      selfChat = await rpc.createChatByContactId(
        accountId,
        DC_CONTACT_ID_SELF
      )
    }
    if (!selfChat || selfChat <= 0) return
    const sealed = await sealJson(accountId, plainJson)
    const body = `${SYNC_MARKER} account=${accountId}\n${sealed}`
    await rpc.miscSendTextMessage(accountId, selfChat, body)
    lastSyncPublishMs = Date.now()
  } catch (e) {
    log.warn('publishBotSyncNow failed', e)
  }
}

async function publishBotSync(
  accountId: number,
  plainJson: string
): Promise<void> {
  const now = Date.now()
  if (now - lastSyncPublishMs < MIN_SYNC_PUBLISH_MS) return
  await publishBotSyncNow(accountId, plainJson)
}

async function syncAccountBots(accountId: number): Promise<void> {
  await pullBotSyncFromSelfChat(accountId)
  await migrateBotContacts()
  await pruneDuplicateHomeChats(accountId)
}

async function pullBotSyncFromSelfChat(accountId: number): Promise<void> {
  try {
    const rpc = getDCJsonrpcRemote().rpc
    let selfChat = await rpc.getChatIdByContactId(accountId, DC_CONTACT_ID_SELF)
    if (!selfChat || selfChat <= 0) return
    const msgIds = await rpc.getMessageIds(accountId, selfChat, false, false)
    const tail = msgIds.slice(-200)
    let emailSynced = false
    let tgSynced = false
    for (let i = tail.length - 1; i >= 0; i--) {
      const msgId = tail[i]
      if (typeof msgId !== 'number' || msgId <= 0) continue
      const msg: any = await rpc.getMessage(accountId, msgId)
      const body: string = msg?.text || ''
      if (!body) continue
      if (!emailSynced && (await tryIngestBotSync(accountId, body))) {
        emailSynced = true
      }
      if (!tgSynced && (await tryIngestTelegramBotSync(accountId, body))) {
        tgSynced = true
      }
      if (emailSynced && tgSynced) break
    }
  } catch (e) {
    log.warn('pullBotSyncFromSelfChat failed account=%s', accountId, e)
  }
}

async function tryIngestBotSync(
  accountId: number,
  body: string
): Promise<boolean> {
  const first = body.split(/\r?\n/, 1)[0]?.trim() || ''
  if (!first.startsWith(SYNC_MARKER)) return false
  const payload = body.includes('\n')
    ? body.slice(body.indexOf('\n') + 1).trim()
    : ''
  if (!payload) return true
  try {
    const json = await openJson(accountId, payload)
    if (!json) return true
    const root = JSON.parse(json)
    const arr = root?.bots
    if (!Array.isArray(arr)) return true
    const prevById = new Map<string, EmailBot>()
    for (const b of bots) {
      if (b.ownerAccountId === accountId) prevById.set(b.id, b)
    }
    const merged = new Map<string, EmailBot>()
    for (const b of bots) {
      if (b.ownerAccountId === accountId) continue
      merged.set(b.id, b)
    }
    for (const raw of arr) {
      const incoming = sanitizeBot(raw)
      if (!incoming) continue
      incoming.ownerAccountId = accountId
      const prev = prevById.get(incoming.id)
      let contactId = prev?.botContactId ?? 0
      let chatId = prev?.botChatId ?? 0
      if (chatId > 0) {
        try {
          const chat: any = await getDCJsonrpcRemote().rpc.getBasicChatInfo(
            accountId,
            chatId
          )
          if (chat?.chatType !== 'OutBroadcast') chatId = 0
        } catch {
          chatId = 0
        }
      }
      if (chatId <= 0) {
        chatId = await findExistingHomeChatId(accountId, incoming)
      }
      incoming.botContactId = contactId > 0 ? contactId : 0
      incoming.botChatId = chatId > 0 ? chatId : 0
      merged.set(incoming.id, incoming)
    }
    bots = Array.from(merged.values())
    await saveStore({ publishSync: false })
    await persistUiConfigForAccount(accountId, false)
    return true
  } catch (e) {
    log.warn('tryIngestBotSync failed', e)
    return true
  }
}

async function saveStore(opts?: { publishSync?: boolean }): Promise<void> {
  try {
    await fs.writeFile(storePath(), JSON.stringify({ bots }, null, 2), 'utf8')
  } catch (e) {
    log.warn('Failed to persist email bots store', e)
  }
  try {
    await persistAllUiConfig(!!opts?.publishSync)
  } catch (e) {
    log.warn('Failed to persist email bots ui-config', e)
  }
}

function botsForAccount(accountId: number): EmailBot[] {
  return bots.filter(b => b.ownerAccountId === accountId)
}

function findByName(accountId: number, name: string): EmailBot | null {
  const lower = name.toLowerCase()
  return (
    botsForAccount(accountId).find(b => b.name.toLowerCase() === lower) ?? null
  )
}

function findByNameGlobal(name: string, exceptId?: string): EmailBot | null {
  const lower = name.toLowerCase()
  return (
    bots.find(
      b => b.name.toLowerCase() === lower && (!exceptId || b.id !== exceptId)
    ) ?? null
  )
}

function generateToken(id: string): string {
  let hash = 0
  for (let i = 0; i < id.length; i++) {
    hash = (hash * 31 + id.charCodeAt(i)) | 0
  }
  const numeric = Math.abs(hash) % 1_000_000_000
  let suffix = id.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 35)
  while (suffix.length < 16) suffix += 'a'
  return numeric + ':' + suffix
}

function sanitizeBot(input: any): EmailBot | null {
  if (!input || typeof input.name !== 'string' || !input.name.trim()) {
    return null
  }
  const id: string = input.id || randomUUID()
  const commands: CommandEntry[] = Array.isArray(input.commands)
    ? input.commands
        .filter((c: any) => c && typeof c.k === 'string')
        .map((c: any) => ({
          k: String(c.k).trim().replace(/^\/+/, '').toLowerCase(),
          v: typeof c.v === 'string' ? c.v : '',
        }))
        .filter((c: CommandEntry) => c.k.length > 0)
    : []
  return {
    id,
    name: input.name.trim(),
    description: input.description || null,
    ownerAccountId: Number(input.ownerAccountId) || 0,
    enabled: input.enabled !== false,
    commands,
    webhookUrl: input.webhookUrl ? String(input.webhookUrl) : null,
    token: input.token || generateToken(id),
    displayName: input.displayName || null,
    developerEmail: input.developerEmail
      ? String(input.developerEmail).toLowerCase().trim()
      : null,
    subscribedUsers: Array.isArray(input.subscribedUsers)
      ? input.subscribedUsers
          .filter((s: any) => typeof s === 'string' && s.trim())
          .map((s: string) => s.toLowerCase().trim())
      : [],
    botContactId: Number(input.botContactId) || 0,
    botChatId: Number(input.botChatId) || 0,
    attachedChatIds: Array.isArray(input.attachedChatIds)
      ? input.attachedChatIds
          .map((v: unknown) => Number(v))
          .filter((v: number) => v > 0)
      : [],
    createdAtMs: Number(input.createdAtMs) || Date.now(),
    lastReplyAtMs: Number(input.lastReplyAtMs) || 0,
    totalReplies: Number(input.totalReplies) || 0,
  }
}

// ---------------------------------------------------------------------------
//  pseudo-contact (dedicated 1:1 bot chat, mirrors Telegram BotContactFactory)
// ---------------------------------------------------------------------------

function makeBotEmail(name: string): string {
  let slug = name.toLowerCase().replace(/[^a-z0-9._-]/g, '')
  if (!slug) slug = 'bot'
  return `emailbot.${slug}@bots.bmchat.local`
}

function nameFromBotEmail(email: string): string | null {
  const lower = email.toLowerCase().trim()
  if (!lower.endsWith('@bots.bmchat.local')) return null
  const at = lower.indexOf('@')
  if (at <= 0) return null
  const local = lower.slice(0, at)
  if (!local.startsWith('emailbot.')) return null
  const slug = local.slice('emailbot.'.length)
  return slug || null
}

async function slugFromBotHomeChat(
  accountId: number,
  chatId: number
): Promise<string | null> {
  if (chatId <= 0) return null
  try {
    const rpc = getDCJsonrpcRemote().rpc
    const chat: any = await rpc.getBasicChatInfo(accountId, chatId)
    const ids: number[] = Array.isArray(chat?.contactIds)
      ? chat.contactIds
      : []
    for (const contactId of ids) {
      if (contactId === DC_CONTACT_ID_SELF) continue
      try {
        const contact: any = await rpc.getContact(accountId, contactId)
        const slug = nameFromBotEmail(String(contact?.address || ''))
        if (slug) return slug
      } catch {
        /* ignore */
      }
    }
  } catch {
    /* ignore */
  }
  return null
}

function normalizeBotName(raw: string): string {
  let s = (raw || '').trim()
  if (s.startsWith('@')) s = s.slice(1)
  try {
    s = decodeURIComponent(s)
  } catch {
    /* ignore */
  }
  return s.trim()
}

async function resolveEmailBot(
  accountId: number,
  botNameHint: string,
  chatId?: number
): Promise<EmailBot | null> {
  const hint = normalizeBotName(botNameHint)
  if (hint) {
    const byName = findByName(accountId, hint)
    if (byName) return byName
  }
  const openChatId = Number(chatId) || 0
  if (openChatId > 0) {
    const byChat = findBotByChatId(accountId, openChatId)
    if (byChat) return byChat
    const slug = await slugFromBotHomeChat(accountId, openChatId)
    if (slug) {
      const bySlug = findByName(accountId, slug)
      if (bySlug) {
        if (!bySlug.botChatId || bySlug.botChatId <= 0) {
          bySlug.botChatId = openChatId
          await saveStore()
        }
        return bySlug
      }
    }
  }
  if (hint) {
    const byName = findByName(accountId, hint)
    if (byName) return byName
    return (
      bots.find(b => b.enabled && b.name.toLowerCase() === hint.toLowerCase()) ??
      null
    )
  }
  return findBotByChatIdAny(openChatId)
}

function findBotByChatIdAny(chatId: number): EmailBot | null {
  if (chatId <= 0) return null
  return (
    bots.find(
      b => b.enabled && b.botChatId != null && b.botChatId > 0 && b.botChatId === chatId
    ) ?? null
  )
}

function findBotByChatId(accountId: number, chatId: number): EmailBot | null {
  return (
    bots.find(
      b =>
        b.ownerAccountId === accountId &&
        b.botChatId != null &&
        b.botChatId > 0 &&
        b.botChatId === chatId
    ) ?? null
  )
}

function matchesBotLabel(bot: EmailBot, label: string): boolean {
  const n = label.trim()
  if (!n) return false
  const lower = n.toLowerCase()
  const dn = (bot.displayName || '').trim()
  if (dn && dn.toLowerCase() === lower) return true
  if (`@${bot.name}`.toLowerCase() === lower) return true
  if (bot.name.toLowerCase() === lower) return true
  return false
}

function isAttachedChat(bot: EmailBot, chatId: number): boolean {
  return (bot.attachedChatIds || []).some(id => id === chatId)
}

async function isSelfOnlyOutBroadcast(
  accountId: number,
  chatId: number
): Promise<boolean> {
  try {
    const chat: any = await getDCJsonrpcRemote().rpc.getBasicChatInfo(
      accountId,
      chatId
    )
    const ids: number[] = Array.isArray(chat?.contactIds) ? chat.contactIds : []
    if (ids.length === 0) return true
    return ids.every(id => id === DC_CONTACT_ID_SELF)
  } catch {
    return false
  }
}

function isOrphanDefaultChannel(bot: EmailBot, chatName: string): boolean {
  const n = chatName.trim()
  if (!n) return true
  return n.toLowerCase() === 'channel'
}

async function listAccountChatIds(accountId: number): Promise<number[]> {
  try {
    const rpc = getDCJsonrpcRemote().rpc as {
      getChatlistEntries?: (
        accountId: number,
        flag: number,
        query: string,
        queryId: number
      ) => Promise<number[]>
    }
    if (!rpc.getChatlistEntries) return []
    const ids = await rpc.getChatlistEntries(accountId, 0, '', 0)
    return (ids || []).filter(id => typeof id === 'number' && id > 0)
  } catch {
    return []
  }
}

async function findExistingHomeChatId(
  accountId: number,
  bot: EmailBot
): Promise<number> {
  if (bot.botChatId && bot.botChatId > 0) {
    try {
      const chat: any = await getDCJsonrpcRemote().rpc.getBasicChatInfo(
        accountId,
        bot.botChatId
      )
      if (chat?.chatType === 'OutBroadcast') return bot.botChatId
    } catch {
      /* ignore */
    }
  }
  let best = 0
  const rpc = getDCJsonrpcRemote().rpc
  for (const cid of await listAccountChatIds(accountId)) {
    try {
      const chat: any = await rpc.getBasicChatInfo(accountId, cid)
      if (chat?.chatType !== 'OutBroadcast') continue
      const chatName = (chat?.name || '').trim()
      if (isAttachedChat(bot, cid)) continue
      if (!(await isSelfOnlyOutBroadcast(accountId, cid))) continue
      if (
        !matchesBotLabel(bot, chatName) &&
        !isOrphanDefaultChannel(bot, chatName)
      ) {
        continue
      }
      if (best === 0 || cid < best) best = cid
    } catch {
      /* ignore */
    }
  }
  return best
}

async function pruneDuplicateHomeChats(accountId: number): Promise<void> {
  const rpc = getDCJsonrpcRemote().rpc
  for (const bot of botsForAccount(accountId)) {
    if (!bot.enabled) continue
    let canonical = bot.botChatId ?? 0
    if (canonical <= 0) {
      canonical = await findExistingHomeChatId(accountId, bot)
      if (canonical > 0) bot.botChatId = canonical
    }
    if (canonical <= 0) continue
    for (const cid of await listAccountChatIds(accountId)) {
      if (cid === canonical) continue
      try {
        const chat: any = await rpc.getBasicChatInfo(accountId, cid)
        if (chat?.chatType !== 'OutBroadcast') continue
        const chatName = (chat?.name || '').trim()
        if (
          !matchesBotLabel(bot, chatName) &&
          !isOrphanDefaultChannel(bot, chatName)
        ) {
          continue
        }
        await rpc.deleteChat(accountId, cid)
        log.info('pruned duplicate home chat %s for @%s', cid, bot.name)
      } catch (e) {
        log.warn('prune delete failed chat=%s', cid, e)
      }
    }
  }
  await saveStore()
}

async function findBotForHomeChat(
  accountId: number,
  chatId: number
): Promise<EmailBot | null> {
  if (chatId <= 0) return null
  const byId = findBotByChatId(accountId, chatId)
  if (byId) return byId
  try {
    const chat: any = await getDCJsonrpcRemote().rpc.getBasicChatInfo(
      accountId,
      chatId
    )
    if (chat?.chatType !== 'OutBroadcast') return null
  } catch {
    return null
  }
  if (!(await isSelfOnlyOutBroadcast(accountId, chatId))) return null
  let chatName = ''
  try {
    const chat: any = await getDCJsonrpcRemote().rpc.getBasicChatInfo(
      accountId,
      chatId
    )
    chatName = (chat?.name || '').trim()
  } catch {
    /* ignore */
  }
  for (const b of botsForAccount(accountId)) {
    if (!b.enabled) continue
    if (isAttachedChat(b, chatId)) continue
    if (matchesBotLabel(b, chatName)) return b
  }
  return null
}

/** Strict id match, then self-only OutBroadcast title match; relinks stale botChatId. */
async function resolveBotHomeChat(
  accountId: number,
  chatId: number,
  relink = true
): Promise<EmailBot | null> {
  if (chatId <= 0) return null
  const strict = findBotByChatId(accountId, chatId)
  if (strict?.enabled) return strict

  const byLabel = await findBotForHomeChat(accountId, chatId)
  if (!byLabel?.enabled) return null

  if (relink && byLabel.botChatId !== chatId) {
    byLabel.botChatId = chatId
    await saveStore()
    return findBotByChatId(accountId, chatId) ?? byLabel
  }
  return byLabel
}

async function isLocalBotChat(
  accountId: number,
  chatId: number
): Promise<boolean> {
  return (await resolveBotHomeChat(accountId, chatId)) != null
}

/**
 * Creates a self-only outgoing broadcast ("channel" with no external
 * recipients) to host a bot conversation locally. Posting into it never
 * produces a message addressed to the non-deliverable @bots.bmchat.local
 * pseudo-contact, so providers no longer bounce it as spam.
 */
async function createLocalBotChat(
  bot: EmailBot,
  displayName: string
): Promise<number> {
  const rpc = getDCJsonrpcRemote().rpc
  try {
    const chatId = await rpc.createBroadcast(bot.ownerAccountId, displayName)
    return chatId > 0 ? chatId : 0
  } catch (e) {
    log.warn('createBroadcast failed for %s', bot.name, e)
    return 0
  }
}

async function ensureBotContact(bot: EmailBot): Promise<void> {
  const rpc = getDCJsonrpcRemote().rpc
  const displayName = bot.displayName?.trim() || `@${bot.name}`

  // Keep the pseudo-contact only for the "add bot to a real group" feature;
  // it is never used as the home-chat recipient anymore.
  if (!bot.botContactId || bot.botContactId <= 0) {
    try {
      bot.botContactId = await rpc.createContact(
        bot.ownerAccountId,
        makeBotEmail(bot.name),
        displayName
      )
    } catch (e) {
      log.warn('createContact failed for %s', bot.name, e)
    }
  }

  // Resolve / migrate to a local self-only broadcast home chat.
  let haveLocal = false
  if (bot.botChatId && bot.botChatId > 0) {
    try {
      const chat: any = await rpc.getBasicChatInfo(
        bot.ownerAccountId,
        bot.botChatId
      )
      if (chat?.chatType === 'OutBroadcast') {
        haveLocal = true
      } else if (chat) {
        // Legacy 1:1 @bots.bmchat.local chat (or any non-broadcast) →
        // migrate to a local broadcast and delete the old chat so the user
        // can no longer type into a conversation that bounces over SMTP.
        const migrated = await createLocalBotChat(bot, `@${bot.name}`)
        if (migrated > 0) {
          try {
            await rpc.deleteChat(bot.ownerAccountId, bot.botChatId)
          } catch {
            /* ignore */
          }
          bot.botChatId = migrated
          haveLocal = true
        }
      }
    } catch {
      /* ignore */
    }
  }
  if (!haveLocal) {
    const existing = await findExistingHomeChatId(bot.ownerAccountId, bot)
    if (existing > 0) {
      bot.botChatId = existing
      haveLocal = true
    }
  }
  if (!haveLocal) {
    const created = await createLocalBotChat(bot, `@${bot.name}`)
    if (created > 0) {
      bot.botChatId = created
      haveLocal = true
    }
  }

  await saveStore()
}

async function migrateBotContacts(): Promise<void> {
  const accounts = new Set<number>()
  for (const bot of bots) {
    if (!bot.enabled) continue
    if (bot.ownerAccountId > 0) accounts.add(bot.ownerAccountId)
    try {
      await ensureBotContact(bot)
      log.info(
        'email bot %s: local home chat id=%s contact=%s',
        bot.name,
        bot.botChatId,
        bot.botContactId
      )
    } catch (e) {
      log.warn('ensureBotContact failed for %s', bot.name, e)
    }
  }
  for (const accountId of accounts) {
    await pruneDuplicateHomeChats(accountId)
  }
}

// ---------------------------------------------------------------------------
//  invocation parsing + reply resolution (port of EmailBotDispatcher)
// ---------------------------------------------------------------------------

interface Invocation {
  bot: EmailBot
  command: string
  argument: string
}

function indexOfWhitespace(s: string): number {
  return s.search(/\s/)
}

function parseInvocation(body: string, accountId: number): Invocation | null {
  const trimmed = body.trim()
  if (!trimmed) return null

  let botName: string | null = null
  let rest = trimmed

  if (trimmed.startsWith('@')) {
    const sp = indexOfWhitespace(trimmed)
    if (sp < 0) {
      botName = trimmed.slice(1)
      rest = ''
    } else {
      botName = trimmed.slice(1, sp)
      rest = trimmed.slice(sp + 1).trim()
    }
    if (!botName) return null
  }

  let command: string
  let argument: string
  if (rest.startsWith('/')) {
    const sp = indexOfWhitespace(rest)
    if (sp < 0) {
      command = rest.slice(1).toLowerCase()
      argument = ''
    } else {
      command = rest.slice(1, sp).toLowerCase()
      argument = rest.slice(sp + 1).trim()
    }
  } else if (botName !== null) {
    command = 'default'
    argument = rest
  } else {
    return null
  }

  let bot: EmailBot | null
  if (botName !== null) {
    bot = findByName(accountId, botName)
  } else {
    const list = botsForAccount(accountId)
    bot = list.length === 1 ? list[0] : null
  }
  if (!bot) return null
  return { bot, command, argument }
}

/** Parse commands in the bot's dedicated 1:1 chat (/cmd without @mention). */
function parseInvocationInBotChat(
  body: string,
  accountId: number,
  homeBot: EmailBot
): Invocation | null {
  const inv = parseInvocation(body, accountId)
  if (inv) return inv

  const trimmed = body.trim()
  if (!trimmed) return null

  if (trimmed.startsWith('/')) {
    const sp = indexOfWhitespace(trimmed)
    const command =
      sp < 0
        ? trimmed.slice(1).toLowerCase()
        : trimmed.slice(1, sp).toLowerCase()
    const argument = sp < 0 ? '' : trimmed.slice(sp + 1).trim()
    return { bot: homeBot, command, argument }
  }

  return { bot: homeBot, command: 'default', argument: trimmed }
}

function withAttachedChat(bot: EmailBot, chatId: number): EmailBot {
  if (chatId <= 0) return bot
  const prev = bot.attachedChatIds ?? []
  if (prev.includes(chatId)) return bot
  return { ...bot, attachedChatIds: [...prev, chatId] }
}

function withoutAttachedChat(bot: EmailBot, chatId: number): EmailBot {
  if (chatId <= 0) return bot
  const prev = bot.attachedChatIds ?? []
  if (!prev.includes(chatId)) return bot
  return { ...bot, attachedChatIds: prev.filter(id => id !== chatId) }
}

function commandValue(bot: EmailBot, key: string): string | undefined {
  return bot.commands.find(c => c.k === key)?.v
}

function resolveReply(
  bot: EmailBot,
  command: string,
  argument: string,
  senderEmail: string
): string | null {
  let template = commandValue(bot, command)
  if (template === undefined && command !== 'default') {
    template = commandValue(bot, 'default') ?? commandValue(bot, 'help')
    if (template === undefined) return null
  }
  if (template === undefined) return null
  return template
    .replace(/\{\{arg\}\}/g, argument)
    .replace(/\{\{from\}\}/g, senderEmail)
    .replace(/\{\{bot\}\}/g, bot.name)
}

function defaultWelcome(bot: EmailBot): string {
  const label = bot.displayName || bot.name
  let s = `Привет! Я @${bot.name} — ${label}.`
  if (bot.description) s += `\n\n${bot.description}`
  if (bot.commands.length) {
    s += '\n\nКоманды:'
    for (const c of bot.commands) s += `\n/${c.k}`
  }
  return s
}

// ---------------------------------------------------------------------------
//  webhook (main process has network)
// ---------------------------------------------------------------------------

function normalizeWebhookText(text: string, parseMode?: string): string {
  const mode = (parseMode || '').trim().toLowerCase()
  if (mode === 'html') {
    return text
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<(b|strong)>/gi, '**')
      .replace(/<\/(b|strong)>/gi, '**')
      .replace(/<(i|em)>/gi, '__')
      .replace(/<\/(i|em)>/gi, '__')
      .replace(/<[^>]+>/g, '')
      .replace(/\*(?!\*)(\S(?:[^*\n]*\S)?)(?<!\*)\*(?!\*)/g, '**$1**')
  }
  return text.replace(
    /(?<!\*)\*(?!\*)(\S(?:[^*\n]*\S)?)(?<!\*)\*(?!\*)/g,
    '**$1**'
  )
}

async function postWebhook(
  bot: EmailBot,
  inv: { command: string; argument: string },
  senderEmail: string,
  body: string,
  chatId: number,
  msgId: number,
  accountId: number
): Promise<string | null> {
  return new Promise(resolve => {
    if (!bot.webhookUrl) {
      resolve(null)
      return
    }
    let url: URL
    try {
      url = new URL(bot.webhookUrl)
    } catch {
      resolve(null)
      return
    }
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      resolve(null)
      return
    }
    void (async () => {
      let replyTo = ''
      try {
        const self: any = await getDCJsonrpcRemote().rpc.getContact(
          accountId,
          DC_CONTACT_ID_SELF
        )
        replyTo = (self?.address || '').toLowerCase()
      } catch {
        /* optional hint for PHP mailer mode */
      }
      const bmchat: Record<string, string> = {
        bot: bot.name,
        token_suffix: bot.token,
        command: inv.command,
        argument: inv.argument,
      }
      if (replyTo) bmchat.reply_to = replyTo
      const payload = JSON.stringify({
        update_id: msgId,
        message: {
          message_id: msgId,
          chat: { id: chatId, type: 'private' },
          from: { email: senderEmail },
          text: body,
          date: Math.floor(Date.now() / 1000),
        },
        bmchat,
      })
    const lib = url.protocol === 'https:' ? https : http
    let settled = false
    const finish = (v: string | null) => {
      if (settled) return
      settled = true
      resolve(v)
    }
    try {
      const req = lib.request(
        url,
        {
          method: 'POST',
          timeout: 15_000,
          headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'User-Agent': 'BMChat-EmailBot/2',
            'X-BMChat-Bot-Token': bot.token,
            'Content-Length': Buffer.byteLength(payload),
          },
        },
        res => {
          const chunks: Buffer[] = []
          res.on('data', (c: Buffer) => chunks.push(c))
          res.on('end', () => {
            if (res.statusCode !== 200) {
              finish(null)
              return
            }
            try {
              const resp = JSON.parse(Buffer.concat(chunks).toString('utf8'))
              let text: string | null = resp.text || resp.reply || null
              if (!text) {
                finish(null)
                return
              }
              text = normalizeWebhookText(text, resp.parse_mode)
              const keyboard = renderInlineKeyboard(
                bot,
                resp.reply_markup?.inline_keyboard
              )
              if (keyboard) text = text + '\n\n' + keyboard
              finish(text)
            } catch {
              finish(null)
            }
          })
          res.on('error', () => finish(null))
        }
      )
      req.on('timeout', () => {
        req.destroy()
        finish(null)
      })
      req.on('error', () => finish(null))
      req.write(payload)
      req.end()
    } catch {
      finish(null)
    }
    })()
  })
}

function parseBotCallbackUrl(
  urlStr: string
): { botName: string; data: string } | null {
  try {
    const u = new URL(urlStr)
    if (u.protocol !== 'bmchat-bot:') return null
    const host = u.hostname || u.host || ''
    const parts = u.pathname.split('/').filter(Boolean)
    if (host === 'cb' && parts.length >= 2) {
      return {
        botName: decodeURIComponent(parts[0]),
        data: decodeURIComponent(parts.slice(1).join('/')),
      }
    }
    return null
  } catch {
    return null
  }
}

/** POST a Telegram-style {@code callback_query} to the bot webhook. */
async function postBotCallback(
  bot: EmailBot,
  callbackData: string,
  chatId?: number
): Promise<string | null> {
  if (!bot.webhookUrl) return null
  let url: URL
  try {
    url = new URL(bot.webhookUrl)
  } catch {
    return null
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null

  const payload = JSON.stringify({
    update_id: Date.now(),
    callback_query: {
      id: String(Date.now()),
      data: callbackData,
      from: { email: '' },
    },
    bmchat: {
      bot: bot.name,
      token_suffix: bot.token,
      kind: 'callback_query',
      ...(Number(chatId) > 0 ? { chat_id: Number(chatId) } : {}),
    },
  })

  return new Promise(resolve => {
    const lib = url.protocol === 'https:' ? https : http
    let settled = false
    const finish = (v: string | null) => {
      if (settled) return
      settled = true
      resolve(v)
    }
    try {
      const req = lib.request(
        url,
        {
          method: 'POST',
          timeout: 15_000,
          headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'User-Agent': 'BMChat-EmailBot/2',
            'X-BMChat-Bot-Token': bot.token,
            'Content-Length': Buffer.byteLength(payload),
          },
        },
        res => {
          const chunks: Buffer[] = []
          res.on('data', (c: Buffer) => chunks.push(c))
          res.on('end', () => {
            if (res.statusCode !== 200) {
              finish(null)
              return
            }
            try {
              const resp = JSON.parse(Buffer.concat(chunks).toString('utf8'))
              let text: string | null = resp.text || resp.reply || null
              if (!text) {
                finish(null)
                return
              }
              text = normalizeWebhookText(text, resp.parse_mode)
              const keyboard = renderInlineKeyboard(
                bot,
                resp.reply_markup?.inline_keyboard
              )
              if (keyboard) text = text + '\n\n' + keyboard
              finish(text)
            } catch {
              finish(null)
            }
          })
          res.on('error', () => finish(null))
        }
      )
      req.on('timeout', () => {
        req.destroy()
        finish(null)
      })
      req.on('error', () => finish(null))
      req.write(payload)
      req.end()
    } catch {
      finish(null)
    }
  })
}

// ---------------------------------------------------------------------------
//  email transport (developer mailbox — mirrors Android EmailBotMailer)
// ---------------------------------------------------------------------------

const MARKER_UPDATE = 'BMCHAT-BOT-UPDATE v1'
const MARKER_REPLY = 'BMCHAT-BOT-REPLY v1'
const REPLY_PATTERN =
  /BMCHAT-BOT-REPLY v1 @?([a-zA-Z0-9_]+) chat=(\d+) in_reply_to=(\d+)/

function tryParseReplyMarker(
  line: string
): { botSlug: string; originChatId: number; originMsgId: number } | null {
  const m = REPLY_PATTERN.exec(line.trim())
  if (!m) return null
  return {
    botSlug: m[1],
    originChatId: parseInt(m[2], 10),
    originMsgId: parseInt(m[3], 10),
  }
}

function parseReplyBody(body: string): {
  text?: string
  reply_markup?: { inline_keyboard?: unknown }
} | null {
  const norm = body.replace(/\r\n/g, '\n')
  let sep = norm.indexOf('\n---\n')
  let json = sep >= 0 ? norm.slice(sep + 5).trim() : ''
  if (!json && norm.trim().startsWith('{')) json = norm.trim()
  if (!json || json[0] !== '{') return null
  try {
    return JSON.parse(json)
  } catch {
    return null
  }
}

async function sendDeveloperUpdate(
  accountId: number,
  bot: EmailBot,
  originChatId: number,
  originMsgId: number,
  senderEmail: string,
  body: string,
  command: string,
  argument: string
): Promise<boolean> {
  if (!bot.developerEmail) return false
  try {
    const rpc = getDCJsonrpcRemote().rpc
    let replyTo = ''
    try {
      const self: any = await rpc.getContact(accountId, DC_CONTACT_ID_SELF)
      replyTo = (self?.address || '').toLowerCase()
    } catch {
      /* ignore */
    }
    const update = {
      update_id: originMsgId,
      message: {
        message_id: originMsgId,
        chat: { id: originChatId, type: 'private' },
        from: { email: senderEmail },
        text: body,
        date: Math.floor(Date.now() / 1000),
      },
      bmchat: {
        bot: bot.name,
        token_suffix: bot.token,
        command,
        argument,
        ...(replyTo ? { reply_to: replyTo } : {}),
      },
    }
    const header = `${MARKER_UPDATE} @${bot.name} chat=${originChatId} message=${originMsgId} from=${senderEmail}`
    const mailBody = header + '\n---\n' + JSON.stringify(update, null, 2)
    const contactId = await rpc.createContact(
      accountId,
      bot.developerEmail,
      null
    )
    const devChatId = await rpc.createChatByContactId(accountId, contactId)
    await rpc.miscSendTextMessage(accountId, devChatId, mailBody)
    return true
  } catch (e) {
    log.warn('sendDeveloperUpdate failed for %s', bot.name, e)
    return false
  }
}

async function handleDeveloperReply(
  accountId: number,
  msg: { text?: string },
  senderEmail: string
): Promise<boolean> {
  const body = msg.text || ''
  if (!body) return false
  const firstLine = body.split(/\r?\n/)[0] || ''
  let env = tryParseReplyMarker(firstLine)
  if (!env) env = tryParseReplyMarker(body.slice(0, 200))
  if (!env) return false

  const bot = findByName(accountId, env.botSlug)
  if (!bot || !bot.developerEmail) return false
  if (bot.developerEmail !== senderEmail.toLowerCase()) {
    log.warn(
      'developer reply for %s from %s, expected %s',
      env.botSlug,
      senderEmail,
      bot.developerEmail
    )
    return false
  }
  const payload = parseReplyBody(body)
  if (!payload) return true
  let text: string | undefined = payload.text
  if (!text && typeof (payload as any).reply === 'string') {
    text = (payload as any).reply
  }
  if (!text) return true
  const keyboard = renderInlineKeyboard(
    bot,
    payload.reply_markup?.inline_keyboard
  )
  if (keyboard) text = text + '\n\n' + keyboard
  await sendBotReply(accountId, env.originChatId, bot, text)
  return true
}

async function resolveOutgoingReply(
  accountId: number,
  bot: EmailBot,
  inv: Invocation,
  senderEmail: string,
  body: string,
  chatId: number,
  msgId: number
): Promise<{ reply: string | null; forwarded: boolean }> {
  let reply = resolveReply(bot, inv.command, inv.argument, senderEmail)
  if (bot.webhookUrl) {
    const webhookReply = await postWebhook(
      bot,
      inv,
      senderEmail,
      body,
      chatId,
      msgId,
      accountId
    )
    if (webhookReply) reply = webhookReply
  }
  let forwarded = false
  if (bot.developerEmail) {
    forwarded = await sendDeveloperUpdate(
      accountId,
      bot,
      chatId,
      msgId,
      senderEmail,
      body,
      inv.command,
      inv.argument
    )
  }
  return { reply, forwarded }
}

function renderInlineKeyboard(bot: EmailBot, rows: any): string | null {
  if (!Array.isArray(rows) || rows.length === 0) return null
  const lines: string[] = []
  for (const row of rows) {
    if (!Array.isArray(row)) continue
    for (const btn of row) {
      if (!btn) continue
      const label = (btn.text || '').trim()
      if (!label) continue
      let target: string | null = null
      if (btn.url) target = btn.url
      else if (btn.callback_data) {
        target =
          'bmchat-bot://cb/' +
          encodeURIComponent(bot.name) +
          '/' +
          encodeURIComponent(btn.callback_data)
      }
      if (!target) continue
      lines.push(`🔘 [${label}](${target})`)
    }
  }
  return lines.length ? lines.join('\n') : null
}

// ---------------------------------------------------------------------------
//  dispatcher
// ---------------------------------------------------------------------------

async function sendBotReply(
  accountId: number,
  chatId: number,
  bot: EmailBot,
  reply: string
): Promise<void> {
  try {
    const rpc = getDCJsonrpcRemote().rpc
    const targetChatId = replyChatId(bot, chatId)
    const inHomeChat = bot.botChatId && bot.botChatId === targetChatId
    const text = inHomeChat ? reply : '@' + bot.name + ': ' + reply
    await rpc.miscSendTextMessage(accountId, targetChatId, text)
    bot.lastReplyAtMs = Date.now()
    bot.totalReplies += 1
    await saveStore()
  } catch (e) {
    log.warn('sendBotReply failed for %s', bot.name, e)
  }
}

async function handleIncoming(
  accountId: number,
  chatId: number,
  msgId: number
): Promise<void> {
  if (accountId <= 0 || chatId <= 0 || msgId <= 0) return
  try {
    const rpc = getDCJsonrpcRemote().rpc
    const msg: any = await rpc.getMessage(accountId, msgId)
    if (!msg) return

    let senderEmail = ''
    if (msg.fromId !== DC_CONTACT_ID_SELF) {
      try {
        const contact: any = await rpc.getContact(accountId, msg.fromId)
        senderEmail = (contact?.address || '').toLowerCase()
      } catch {
        /* ignore */
      }
    } else {
      try {
        const self: any = await rpc.getContact(accountId, DC_CONTACT_ID_SELF)
        senderEmail = (self?.address || '').toLowerCase()
      } catch {
        /* ignore */
      }
    }

    // Developer SMTP reply (BMCHAT-BOT-REPLY) — before user-command parsing.
    if (await handleDeveloperReply(accountId, msg, senderEmail)) return

    const body: string = msg.text || ''
    if (!body) return

    if (await tryIngestBotSync(accountId, body)) {
      await reloadStoreMerged()
      await migrateBotContacts()
      return
    }

    if (await tryIngestTelegramBotSync(accountId, body)) {
      return
    }

    const isSelf = msg.fromId === DC_CONTACT_ID_SELF
    let homeBot = await resolveBotHomeChat(accountId, chatId)
    if (!homeBot) {
      const slug = await slugFromBotHomeChat(accountId, chatId)
      if (slug) {
        homeBot =
          findByName(accountId, slug) ??
          bots.find(b => b.enabled && b.name.toLowerCase() === slug.toLowerCase()) ??
          null
      }
    }
    if (botsForAccount(accountId).length === 0 && !homeBot) return

    // Own messages in the bot's 1:1 home chat must not re-trigger the webhook
    // (every bot reply was parsed as command "default" → infinite PHP loop).
    if (isSelf && homeBot) {
      const t = body.trim()
      if (!t.startsWith('/')) {
        return
      }
    }

    let inv: Invocation | null
    if (homeBot) {
      inv = parseInvocationInBotChat(body, accountId, homeBot)
    } else if (isSelf) {
      inv = parseInvocation(body, accountId)
    } else {
      inv = parseInvocation(body, accountId)
    }
    if (!inv) return
    const bot = inv.bot
    if (!bot.enabled) return

    const ownerInHomeChat = isSelf && homeBot != null && homeBot.id === bot.id

    if (inv.command === 'start') {
      if (senderEmail && !bot.subscribedUsers.includes(senderEmail)) {
        bot.subscribedUsers.push(senderEmail)
        await saveStore()
      }
      let welcome =
        resolveReply(bot, 'start', inv.argument, senderEmail) ??
        defaultWelcome(bot)
      const { reply, forwarded } = await resolveOutgoingReply(
        accountId,
        bot,
        inv,
        senderEmail,
        body,
        chatId,
        msgId
      )
      const outgoing = bot.webhookUrl ? reply : reply ?? welcome
      if (outgoing) {
        await sendBotReply(accountId, chatId, bot, outgoing)
      } else if (!forwarded) {
        log.warn('email bot %s: /start produced no reply', bot.name)
      }
      return
    }

    if (
      !ownerInHomeChat &&
      senderEmail &&
      !bot.subscribedUsers.includes(senderEmail)
    ) {
      bot.subscribedUsers.push(senderEmail)
      await saveStore({ publishSync: false })
    }

    const { reply, forwarded } = await resolveOutgoingReply(
      accountId,
      bot,
      inv,
      senderEmail,
      body,
      chatId,
      msgId
    )
    if (!reply) {
      if (forwarded) return
      return
    }
    await sendBotReply(accountId, chatId, bot, reply)
  } catch (e) {
    log.warn('email bot handleIncoming failed', e)
  }
}

// ---------------------------------------------------------------------------
//  init + IPC
// ---------------------------------------------------------------------------

export async function initEmailBots(): Promise<void> {
  if (initialised) return
  initialised = true

  await app.whenReady()
  await reloadStoreMerged()
  log.info('Loaded email bots store (%d bots)', bots.length)

  DCJsonrpcRemoteInitializedP.then(remote => {
    const onBotMessage = (accountId: number, event: any) => {
      const chatId = event?.chatId ?? event?.chat_id
      const msgId = event?.msgId ?? event?.msg_id
      if (!chatId || !msgId) return
      void handleIncoming(accountId, chatId, msgId)
    }
    try {
      ;(remote as any).on('IncomingMsg', onBotMessage)
      // Only outgoing slash-commands in the bot home chat use MsgsChanged.
      // Incoming user /start already arrives via IncomingMsg — both handlers
      // caused duplicate webhook calls (two identical replies).
      ;(remote as any).on('MsgsChanged', (accountId: number, event: any) => {
        const chatId = event?.chatId ?? event?.chat_id
        const msgId = event?.msgId ?? event?.msg_id
        if (!chatId || !msgId) return
        void (async () => {
          try {
            if (!(await resolveBotHomeChat(accountId, chatId))) return
            const msg: any = await getDCJsonrpcRemote().rpc.getMessage(
              accountId,
              msgId
            )
            if (!msg || msg.fromId !== DC_CONTACT_ID_SELF) return
            await handleIncoming(accountId, chatId, msgId)
          } catch {
            /* ignore */
          }
        })()
      })
    } catch (e) {
      log.warn('failed to subscribe to email bot events', e)
    }
    void migrateBotContacts()
    void (async () => {
      try {
        const accountIds = await getDCJsonrpcRemote().rpc.getAllAccountIds()
        for (const accountId of accountIds) {
          if (accountId > 0) await syncAccountBots(accountId)
        }
      } catch (e) {
        log.warn('startup bot sync pull failed', e)
      }
    })()
  }).catch(() => {})

  ipcMain.handle('bmchat:emailbots:list', async () => {
    await reloadStoreMerged()
    return bots
  })

  ipcMain.handle(
    'bmchat:emailbots:is-home-chat',
    async (_e, args: { accountId: number; chatId: number }) => {
      await reloadStoreMerged()
      const accountId = Number(args?.accountId) || 0
      const chatId = Number(args?.chatId) || 0
      const bot = await resolveBotHomeChat(accountId, chatId)
      return { isHome: !!bot, name: bot?.name ?? null }
    }
  )

  ipcMain.handle(
    'bmchat:emailbots:open-chat',
    async (_e, args: { accountId: number; botName: string }) => {
      const bot = findByName(Number(args?.accountId) || 0, args?.botName || '')
      if (!bot) return { chatId: 0 }
      try {
        await ensureBotContact(bot)
        return { chatId: bot.botChatId ?? 0 }
      } catch (e) {
        log.warn('open-chat failed for %s', args?.botName, e)
        return { chatId: 0 }
      }
    }
  )

  ipcMain.handle('bmchat:emailbots:save', async (_e, input: any) => {
    const bot = sanitizeBot(input)
    if (!bot) return { ok: false, error: 'invalid' }
    if (
      !/^[A-Za-z0-9_-]+$/.test(bot.name) ||
      !(bot.name.toLowerCase().endsWith('bot') || bot.name.toLowerCase().endsWith('_bot'))
    ) {
      return { ok: false, error: 'invalid_name' }
    }
    const existingIdx = bots.findIndex(b => b.id === bot.id)
    if (existingIdx >= 0) {
      const prev = bots[existingIdx]
      bot.botContactId = prev.botContactId ?? bot.botContactId
      bot.botChatId = prev.botChatId ?? bot.botChatId
      bot.attachedChatIds = bot.attachedChatIds?.length
        ? bot.attachedChatIds
        : prev.attachedChatIds ?? []
      bots[existingIdx] = bot
    } else {
      // Name must be unique on all devices of this account.
      if (findByName(bot.ownerAccountId, bot.name)) {
        return { ok: false, error: 'name_taken' }
      }
      if (findByNameGlobal(bot.name)) {
        return { ok: false, error: 'name_taken' }
      }
      bots.push(bot)
    }
    try {
      await ensureBotContact(bot)
    } catch (e) {
      log.warn('ensureBotContact on save failed for %s', bot.name, e)
    }
    await saveStore({ publishSync: true })
    return { ok: true, bot }
  })

  ipcMain.handle('bmchat:emailbots:remove', async (_e, id: string) => {
    const removed = bots.find(b => b.id === id)
    bots = bots.filter(b => b.id !== id)
    if (removed?.botContactId && removed.botContactId > 0) {
      try {
        await getDCJsonrpcRemote().rpc.deleteContact(
          removed.ownerAccountId,
          removed.botContactId
        )
      } catch (e) {
        log.warn('deleteContact for bot %s failed', removed.name, e)
      }
    }
    await saveStore({ publishSync: true })
    return bots
  })

  ipcMain.handle(
    'bmchat:emailbots:set-enabled',
    async (_e, args: { id: string; enabled: boolean }) => {
      const bot = bots.find(b => b.id === args.id)
      if (bot) {
        bot.enabled = !!args.enabled
        await saveStore({ publishSync: true })
      }
      return bots
    }
  )

  ipcMain.handle(
    'bmchat:emailbots:attach-chat',
    async (_e, args: { id: string; chatId: number }) => {
      await reloadStoreMerged()
      const bot = bots.find(b => b.id === args?.id)
      const chatId = Number(args?.chatId) || 0
      if (!bot || chatId <= 0) return { ok: false, error: 'invalid' }
      const accountId = bot.ownerAccountId
      if (accountId <= 0) return { ok: false, error: 'no_account' }
      try {
        const rpc = getDCJsonrpcRemote().rpc
        const chat: any = await rpc.getChat(accountId, chatId)
        if (!chat?.canSend) return { ok: false, error: 'cannot_send' }
        if (!chat?.isMultiUser) return { ok: false, error: 'not_multiuser' }
        if (bot.botChatId && bot.botChatId === chatId) {
          return { ok: false, error: 'home_chat' }
        }
        const updated = withAttachedChat(bot, chatId)
        const idx = bots.findIndex(b => b.id === bot.id)
        if (idx >= 0) bots[idx] = updated
        await saveStore({ publishSync: true })
        return { ok: true, bot: updated }
      } catch (e) {
        log.warn('attach-chat failed for %s chat=%s', bot.name, chatId, e)
        return { ok: false, error: 'failed' }
      }
    }
  )

  ipcMain.handle(
    'bmchat:emailbots:detach-chat',
    async (_e, args: { id: string; chatId: number }) => {
      await reloadStoreMerged()
      const bot = bots.find(b => b.id === args?.id)
      const chatId = Number(args?.chatId) || 0
      if (!bot || chatId <= 0) return { ok: false, error: 'invalid' }
      const updated = withoutAttachedChat(bot, chatId)
      const idx = bots.findIndex(b => b.id === bot.id)
      if (idx >= 0) bots[idx] = updated
      await saveStore({ publishSync: true })
      return { ok: true, bot: updated }
    }
  )

  ipcMain.handle(
    'bmchat:emailbots:callback',
    async (
      _e,
      args: { accountId?: number; url?: string; chatId?: number }
    ) => {
      await reloadStoreMerged()
      const accountId = Number(args?.accountId) || 0
      const parsed = parseBotCallbackUrl(String(args?.url || ''))
      if (!parsed) return { ok: false, error: 'invalid_url' }
      const bot = await resolveEmailBot(
        accountId,
        parsed.botName,
        Number(args?.chatId) || 0
      )
      if (!bot || !bot.enabled) return { ok: false, error: 'no_bot' }
      if (!bot.webhookUrl) return { ok: false, error: 'no_webhook' }
      const ownerId = bot.ownerAccountId > 0 ? bot.ownerAccountId : accountId
      const reply = await postBotCallback(
        bot,
        parsed.data,
        Number(args?.chatId) || bot.botChatId || 0
      )
      if (!reply) return { ok: false, error: 'no_reply' }
      const chatId =
        Number(args?.chatId) > 0
          ? Number(args.chatId)
          : bot.botChatId && bot.botChatId > 0
            ? bot.botChatId
            : 0
      if (chatId > 0) {
        await sendBotReply(ownerId, chatId, bot, reply)
      }
      return { ok: true }
    }
  )
}
