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
import { createHash, randomUUID } from 'crypto'

import { getConfigPath } from './application-constants.js'
import {
  decryptForAccount,
  encryptForAccount,
  openJson,
  sealJson,
} from './bmchat-email-bot-crypto.js'
import { getLogger } from '../../shared/logger.js'
import { getDCJsonrpcRemote, DCJsonrpcRemoteInitializedP } from './ipc.js'

const log = getLogger('main/bmchat-email-bots')

const STORE_FILE = 'bmchat-email-bots.json'
/** Synced across devices via Delta Chat multidevice (ui.* keys). */
const UI_CONFIG_KEY = 'ui.bmchat.email_bots'
/** Public bot catalog (mesh-light), synced on multidevice. */
const UI_DIRECTORY_KEY = 'ui.bmchat.bot_directory'
const UI_LAST_PUBLISH_KEY = 'ui.bmchat.bot_directory_last_publish_ms'
const CATALOG_MARKER_PREFIX = 'BMCHAT-BOT-CATALOG v1'
const BOT_SYNC_MARKER = 'BMCHAT-BOT-SYNC v1'
const DC_CONTACT_ID_SELF = 1
let lastBotSyncPublishMs = 0
const MIN_BOT_SYNC_INTERVAL_MS = 8_000
const MIN_CATALOG_PUBLISH_INTERVAL_MS = 24 * 60 * 60 * 1000
const MAX_CATALOG_GOSSIP_CONTACTS_PER_DAY = 8

interface DirectoryEntry {
  name: string
  displayName: string
  description: string
  botEmail: string
}
/** Invisible marker on bot-authored messages — stops echo/webhook loops. */
const BOT_OUT_MARKER = '\u2060'
const MAX_REPLIES_PER_CHAT_PER_MIN = 12
const MIN_MS_BETWEEN_REPLIES = 600
const HANDLE_DEDUPE_MS = 120_000

const handledMsgKeys = new Map<string, number>()
const replyWindowByChat = new Map<
  string,
  { count: number; windowStart: number; lastReplyMs: number }
>()

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
  /** Group/channel chats where the bot may post (local attach, like Telegram). */
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
    if (parsed?.v === 2 && parsed?.accounts && typeof parsed.accounts === 'object') {
      const merged: EmailBot[] = []
      const rpc = getDCJsonrpcRemote().rpc
      const accounts: number[] = await rpc.getAllAccounts()
      for (const accountId of accounts) {
        const enc = parsed.accounts[String(accountId)]
        if (!enc || typeof enc !== 'string') continue
        const dec = await decryptForAccount(accountId, enc)
        try {
          const inner = JSON.parse(dec)
          if (Array.isArray(inner?.bots)) {
            merged.push(...inner.bots.map((b: any) => sanitizeBot(b)).filter(Boolean))
          }
        } catch {
          /* skip */
        }
      }
      bots = merged as EmailBot[]
      return
    }
    bots = Array.isArray(parsed?.bots) ? parsed.bots : []
  } catch (e: any) {
    if (e?.code !== 'ENOENT') log.warn('Failed to read email bots store', e)
    bots = []
  }
}

async function writeEncryptedStoreFile(): Promise<void> {
  const byAccount = new Map<number, EmailBot[]>()
  for (const b of bots) {
    const list = byAccount.get(b.ownerAccountId) ?? []
    list.push(b)
    byAccount.set(b.ownerAccountId, list)
  }
  const accounts: Record<string, string> = {}
  for (const [accountId, list] of byAccount) {
    accounts[String(accountId)] = await encryptForAccount(
      accountId,
      JSON.stringify({ bots: list })
    )
  }
  await fs.writeFile(
    storePath(),
    JSON.stringify({ v: 2, accounts }, null, 2),
    'utf8'
  )
}

async function saveStore(publishSync = true): Promise<void> {
  try {
    await writeEncryptedStoreFile()
  } catch (e) {
    log.warn('Failed to persist email bots store', e)
  }
  await persistBotsToUiConfig(publishSync)
  const accountIds = new Set(bots.map(b => b.ownerAccountId))
  for (const accountId of accountIds) {
    await publishDirectoryIfNeeded(accountId)
  }
}

async function publishBotSyncToSelf(
  accountId: number,
  plainJson: string
): Promise<void> {
  if (Date.now() - lastBotSyncPublishMs < MIN_BOT_SYNC_INTERVAL_MS) return
  lastBotSyncPublishMs = Date.now()
  try {
    const rpc = getDCJsonrpcRemote().rpc
    let selfChat = await rpc.getChatIdByContactId(accountId, DC_CONTACT_ID_SELF)
    if (!selfChat) {
      selfChat = await rpc.createChatByContactId(accountId, DC_CONTACT_ID_SELF)
    }
    if (!selfChat) return
    const sealed = await sealJson(accountId, plainJson)
    await rpc.sendTextMsg(
      accountId,
      selfChat,
      `${BOT_SYNC_MARKER} account=${accountId}\n${sealed}`
    )
  } catch (e) {
    log.warn('publishBotSyncToSelf failed', e)
  }
}

async function persistBotsToUiConfig(publishSync = true): Promise<void> {
  try {
    const rpc = getDCJsonrpcRemote().rpc
    const byAccount = new Map<number, EmailBot[]>()
    for (const b of bots) {
      const list = byAccount.get(b.ownerAccountId) ?? []
      list.push(b)
      byAccount.set(b.ownerAccountId, list)
    }
    for (const [accountId, list] of byAccount) {
      const wrapper = JSON.stringify({ bots: list, updatedAtMs: Date.now() })
      await rpc.setConfig(accountId, UI_CONFIG_KEY, await sealJson(accountId, wrapper))
      if (publishSync) {
        await publishBotSyncToSelf(accountId, wrapper)
      }
    }
  } catch (e) {
    log.warn('persistBotsToUiConfig failed (non-fatal)', e)
  }
}

async function mergeBotsFromSyncBody(
  accountId: number,
  body: string
): Promise<boolean> {
  const parts = body.trim().split(/\r?\n/, 2)
  if (!parts[0]?.startsWith(BOT_SYNC_MARKER)) return false
  let srcAccount = accountId
  const accMatch = parts[0].match(/account=(\d+)/)
  if (accMatch) srcAccount = Number(accMatch[1]) || accountId
  const payload = parts[1]?.trim() ?? ''
  if (!payload) return true
  try {
    const opened = await openJson(srcAccount, payload)
    if (!opened) return true
    const parsed = JSON.parse(opened)
    const remote: EmailBot[] = Array.isArray(parsed?.bots)
      ? parsed.bots.map((b: any) => sanitizeBot(b)).filter(Boolean)
      : []
    if (remote.length === 0) return true
    bots = bots.filter(b => b.ownerAccountId !== srcAccount)
    bots.push(...(remote as EmailBot[]))
    await persistBotsToUiConfig(false)
    await writeEncryptedStoreFile()
    return true
  } catch (e) {
    log.warn('mergeBotsFromSyncBody failed', e)
    return true
  }
}

async function mergeBotsFromUiConfig(): Promise<void> {
  try {
    const rpc = getDCJsonrpcRemote().rpc
    const accounts: number[] = await rpc.getAllAccounts()
    for (const accountId of accounts) {
      const raw = await rpc.getConfig(accountId, UI_CONFIG_KEY)
      if (!raw) continue
      const opened = await openJson(accountId, raw)
      if (!opened) continue
      const parsed = JSON.parse(opened)
      const remote: EmailBot[] = Array.isArray(parsed?.bots)
        ? parsed.bots.map((b: any) => sanitizeBot(b)).filter(Boolean)
        : []
      if (remote.length === 0) continue
      bots = bots.filter(b => b.ownerAccountId !== accountId)
      bots.push(...(remote as EmailBot[]))
    }
    await saveStore()
    log.info('Merged email bots from ui config (%d total)', bots.length)
  } catch (e) {
    log.warn('mergeBotsFromUiConfig failed (non-fatal)', e)
  }
}

function catalogSha256(s: string): string {
  return createHash('sha256').update(s, 'utf8').digest('hex')
}

async function tryIngestCatalog(
  accountId: number,
  body: string
): Promise<boolean> {
  const trimmed = body.trim()
  if (!trimmed) return false
  const parts = trimmed.split(/\r?\n/, 2)
  const firstLine = parts[0]?.trim() ?? ''
  if (!firstLine.startsWith(CATALOG_MARKER_PREFIX)) return false
  let etag = ''
  const etagIdx = firstLine.indexOf('etag=')
  if (etagIdx >= 0) etag = firstLine.slice(etagIdx + 5).trim()
  let json = parts[1]?.trim() ?? ''
  if (!json && trimmed.startsWith('{')) json = trimmed
  if (!json) return false
  try {
    const incoming = JSON.parse(json)
    const rpc = getDCJsonrpcRemote().rpc
    const existingRaw = await rpc.getConfig(accountId, UI_DIRECTORY_KEY)
    const merged = new Map<string, Record<string, unknown>>()
    if (existingRaw) {
      const opened = await openJson(accountId, existingRaw)
      const existing = JSON.parse(opened ?? existingRaw)
      const arr = existing?.bots
      if (Array.isArray(arr)) {
        for (const o of arr) {
          if (o?.name) merged.set(String(o.name).toLowerCase(), o)
        }
      }
    }
    const incBots = incoming?.bots
    if (Array.isArray(incBots)) {
      for (const o of incBots) {
        if (o?.name) merged.set(String(o.name).toLowerCase(), o)
      }
    }
    const outBots = [...merged.values()]
    const catalogJson = JSON.stringify({
      v: 1,
      etag: etag || catalogSha256(JSON.stringify(outBots)),
      bots: outBots,
      updatedAtMs: Date.now(),
    })
    await rpc.setConfig(
      accountId,
      UI_DIRECTORY_KEY,
      await sealJson(accountId, catalogJson)
    )
    return true
  } catch (e) {
    log.warn('catalog ingest failed', e)
    return false
  }
}

async function getDirectoryEntries(accountId: number): Promise<DirectoryEntry[]> {
  try {
    const raw = await getDCJsonrpcRemote().rpc.getConfig(
      accountId,
      UI_DIRECTORY_KEY
    )
    if (!raw) return []
    const opened = await openJson(accountId, raw)
    if (!opened) return []
    const parsed = JSON.parse(opened)
    const arr = parsed?.bots
    if (!Array.isArray(arr)) return []
    return arr
      .filter((o: any) => o?.name)
      .map((o: any) => ({
        name: String(o.name),
        displayName: String(o.displayName || o.name),
        description: String(o.description || ''),
        botEmail: String(o.botEmail || makeBotEmail(String(o.name))),
      }))
  } catch {
    return []
  }
}

async function publishDirectoryIfNeeded(accountId: number): Promise<void> {
  const enabled = botsForAccount(accountId).filter(b => b.enabled)
  if (enabled.length === 0) return
  try {
    const rpc = getDCJsonrpcRemote().rpc
    const pub = enabled.map(b => ({
      name: b.name,
      displayName: b.displayName?.trim() || b.name,
      description: b.description || '',
      botEmail: makeBotEmail(b.name),
    }))
    const etag = catalogSha256(JSON.stringify(pub))
    const catalog = { v: 1, etag, bots: pub, updatedAtMs: Date.now() }
    await rpc.setConfig(
      accountId,
      UI_DIRECTORY_KEY,
      await sealJson(accountId, JSON.stringify(catalog))
    )

    const lastRaw = await rpc.getConfig(accountId, UI_LAST_PUBLISH_KEY)
    const last = lastRaw ? Number(lastRaw) : 0
    if (Date.now() - last < MIN_CATALOG_PUBLISH_INTERVAL_MS) return

    const gossip = await rpc.getConfig(accountId, 'ui.bmchat.bot_directory_gossip')
    if (gossip !== '1') return

    const payload = `${CATALOG_MARKER_PREFIX} etag=${etag}\n${JSON.stringify(catalog)}`
    const contactIds: number[] = await rpc.getContacts(accountId, 0, null)
    let sent = 0
    for (const cid of contactIds) {
      if (sent >= MAX_CATALOG_GOSSIP_CONTACTS_PER_DAY) break
      if (!cid || cid === DC_CONTACT_ID_SELF) continue
      try {
        let chatId = await rpc.getChatIdByContactId(accountId, cid)
        if (!chatId) chatId = await rpc.createChatByContactId(accountId, cid)
        if (chatId > 0) {
          await rpc.sendTextMsg(accountId, chatId, payload)
          sent++
        }
      } catch {
        /* skip contact */
      }
    }
    await rpc.setConfig(
      accountId,
      UI_LAST_PUBLISH_KEY,
      String(Date.now())
    )
  } catch (e) {
    log.warn('publishDirectoryIfNeeded failed', e)
  }
}

async function openBotChatByName(
  accountId: number,
  botName: string
): Promise<number> {
  const existing = findByName(accountId, botName)
  if (existing) {
    await ensureBotContact(existing)
    return existing.botChatId ?? 0
  }
  const dir = (await getDirectoryEntries(accountId)).find(
    e => e.name.toLowerCase() === botName.toLowerCase()
  )
  if (!dir) return 0
  const rpc = getDCJsonrpcRemote().rpc
  const email = makeBotEmail(dir.name)
  const displayName = dir.displayName || `@${dir.name}`
  let contactId = 0
  try {
    contactId = await rpc.lookupContactIdByAddr(accountId, email)
  } catch {
    /* ignore */
  }
  if (!contactId) {
    contactId = await rpc.createContact(accountId, email, displayName)
  }
  if (!contactId) return 0
  let chatId = 0
  try {
    chatId = await rpc.getChatIdByContactId(accountId, contactId)
  } catch {
    /* ignore */
  }
  if (!chatId) chatId = await rpc.createChatByContactId(accountId, contactId)
  return chatId > 0 ? chatId : 0
}

function listSearchableBots(): Array<{
  id: string
  name: string
  displayName: string | null
  enabled: boolean
  botChatId?: number
  fromDirectory?: boolean
}> {
  const seen = new Set<string>()
  const out: Array<{
    id: string
    name: string
    displayName: string | null
    enabled: boolean
    botChatId?: number
    fromDirectory?: boolean
  }> = []
  for (const b of bots) {
    if (!b.enabled) continue
    const key = `${b.ownerAccountId}:${b.name.toLowerCase()}`
    if (seen.has(key)) continue
    seen.add(key)
    out.push({
      id: b.id,
      name: b.name,
      displayName: b.displayName ?? null,
      enabled: true,
      botChatId: b.botChatId,
    })
  }
  return out
}

async function listSearchableBotsAsync(): Promise<
  ReturnType<typeof listSearchableBots>
> {
  const base = listSearchableBots()
  const seen = new Set(base.map(b => b.name.toLowerCase()))
  try {
    const accounts: number[] = await getDCJsonrpcRemote().rpc.getAllAccounts()
    for (const accountId of accounts) {
      for (const e of await getDirectoryEntries(accountId)) {
        const lower = e.name.toLowerCase()
        if (seen.has(lower)) continue
        seen.add(lower)
        base.push({
          id: `dir:${lower}`,
          name: e.name,
          displayName: e.displayName || null,
          enabled: true,
          fromDirectory: true,
        })
      }
    }
  } catch (e) {
    log.warn('listSearchableBotsAsync directory merge failed', e)
  }
  return base
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
          .map((id: any) => Number(id))
          .filter((id: number) => id > 0)
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

function findBotByChatId(accountId: number, chatId: number): EmailBot | null {
  return findBotForChat(accountId, chatId)
}

function findBotForChat(accountId: number, chatId: number): EmailBot | null {
  for (const b of botsForAccount(accountId)) {
    if (!b.enabled) continue
    if (b.botChatId && b.botChatId === chatId) return b
    if (b.attachedChatIds?.includes(chatId)) return b
  }
  return null
}

function isBotHomeChat(bot: EmailBot, chatId: number): boolean {
  return !!(bot.botChatId && bot.botChatId > 0 && bot.botChatId === chatId)
}

function pruneHandledMsgKeys(now: number): void {
  for (const [k, t] of handledMsgKeys) {
    if (now - t > HANDLE_DEDUPE_MS) handledMsgKeys.delete(k)
  }
}

function wasMsgAlreadyHandled(
  accountId: number,
  chatId: number,
  msgId: number
): boolean {
  const key = `${accountId}:${chatId}:${msgId}`
  const now = Date.now()
  pruneHandledMsgKeys(now)
  if (handledMsgKeys.has(key)) return true
  handledMsgKeys.set(key, now)
  return false
}

function canSendAnotherReply(accountId: number, chatId: number): boolean {
  const key = `${accountId}:${chatId}`
  const now = Date.now()
  let w = replyWindowByChat.get(key)
  if (!w || now - w.windowStart > 60_000) {
    w = { count: 0, windowStart: now, lastReplyMs: 0 }
  }
  if (w.count >= MAX_REPLIES_PER_CHAT_PER_MIN) {
    log.warn('email bot rate limit hit for chat %s', chatId)
    return false
  }
  if (now - w.lastReplyMs < MIN_MS_BETWEEN_REPLIES) return false
  w.count += 1
  w.lastReplyMs = now
  replyWindowByChat.set(key, w)
  return true
}

function isBotEchoMessage(body: string): boolean {
  const t = body.trim()
  if (t.startsWith(BOT_OUT_MARKER)) return true
  if (/^@[A-Za-z0-9_]+:\s/.test(t)) return true
  return false
}

async function ensureBotContact(bot: EmailBot): Promise<void> {
  const rpc = getDCJsonrpcRemote().rpc
  const displayName = bot.displayName?.trim() || `@${bot.name}`
  const email = makeBotEmail(bot.name)
  try {
    const existing = await rpc.lookupContactIdByAddr(bot.ownerAccountId, email)
    if (existing && existing > 0) bot.botContactId = existing
  } catch {
    /* ignore */
  }
  if (!bot.botContactId || bot.botContactId <= 0) {
    bot.botContactId = await rpc.createContact(
      bot.ownerAccountId,
      email,
      displayName
    )
  }
  if (!bot.botChatId || bot.botChatId <= 0) {
    if (bot.botContactId > 0) {
      try {
        const existing = await rpc.getChatIdByContactId(
          bot.ownerAccountId,
          bot.botContactId
        )
        if (existing > 0) bot.botChatId = existing
      } catch {
        /* ignore */
      }
    }
    if (!bot.botChatId || bot.botChatId <= 0) {
      bot.botChatId = await rpc.createChatByContactId(
        bot.ownerAccountId,
        bot.botContactId
      )
    }
  }
  await saveStore()
}

async function migrateBotContacts(): Promise<void> {
  for (const bot of bots) {
    if (!bot.enabled) continue
    if (bot.botChatId && bot.botChatId > 0) continue
    try {
      await ensureBotContact(bot)
      log.info(
        'email bot %s: home chat id=%s contact=%s',
        bot.name,
        bot.botChatId,
        bot.botContactId
      )
    } catch (e) {
      log.warn('ensureBotContact failed for %s', bot.name, e)
    }
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
  } else if (botName !== null && rest.length > 0) {
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
  if (!trimmed || !trimmed.startsWith('/')) return null

  const sp = indexOfWhitespace(trimmed)
  const command =
    sp < 0
      ? trimmed.slice(1).toLowerCase()
      : trimmed.slice(1, sp).toLowerCase()
  const argument = sp < 0 ? '' : trimmed.slice(sp + 1).trim()
  return { bot: homeBot, command, argument }
}

function replyChatId(bot: EmailBot, originChatId: number): number {
  return bot.botChatId && bot.botChatId > 0 ? bot.botChatId : originChatId
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
  const targetChatId = replyChatId(bot, chatId)
  if (!canSendAnotherReply(accountId, targetChatId)) return
  try {
    const rpc = getDCJsonrpcRemote().rpc
    const inHomeChat = isBotHomeChat(bot, targetChatId)
    const visible = inHomeChat ? reply : '@' + bot.name + ': ' + reply
    const text = BOT_OUT_MARKER + visible
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
  if (wasMsgAlreadyHandled(accountId, chatId, msgId)) return
  try {
    const rpc = getDCJsonrpcRemote().rpc
    const msg: any = await rpc.getMessage(accountId, msgId)
    if (!msg) return
    if (msg.isInfo) return

    const bodyEarly: string = msg.text || ''
    if (bodyEarly && (await mergeBotsFromSyncBody(accountId, bodyEarly))) return
    if (bodyEarly && (await tryIngestCatalog(accountId, bodyEarly))) return

    if (botsForAccount(accountId).length === 0) return

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
    if (isBotEchoMessage(body)) return

    const isSelf = msg.fromId === DC_CONTACT_ID_SELF
    const activeBot = findBotForChat(accountId, chatId)
    const inHomeChat = activeBot != null && isBotHomeChat(activeBot, chatId)

    // In the bot home chat only slash-commands from the owner may trigger the bot.
    if (isSelf && inHomeChat) {
      const t = body.trim()
      if (!t.startsWith('/') && !t.startsWith('@')) return
    }
    // In attached group chats ignore plain owner messages without @bot.
    if (isSelf && activeBot && !inHomeChat) {
      if (!body.trim().toLowerCase().includes('@' + activeBot.name)) return
    }

    let inv: Invocation | null
    if (inHomeChat && activeBot) {
      inv = parseInvocationInBotChat(body, accountId, activeBot)
    } else {
      inv = parseInvocation(body, accountId)
    }
    if (!inv) return
    const bot = inv.bot
    if (!bot.enabled) return

    const ownerInHomeChat = isSelf && inHomeChat && activeBot != null && activeBot.id === bot.id

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
      const outgoing = reply ?? welcome
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
      return
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
  await loadStore()
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
      // Outgoing commands in the bot home chat only emit MsgsChanged, not IncomingMsg.
      ;(remote as any).on('MsgsChanged', (accountId: number, event: any) => {
        const chatId = event?.chatId ?? event?.chat_id
        const msgId = event?.msgId ?? event?.msg_id
        if (!chatId || !msgId) return
        if (!findBotForChat(accountId, chatId)) return
        void handleIncoming(accountId, chatId, msgId)
      })
    } catch (e) {
      log.warn('failed to subscribe to email bot events', e)
    }
    void mergeBotsFromUiConfig().then(() => migrateBotContacts())
  }).catch(() => {})

  ipcMain.handle('bmchat:emailbots:list', () => bots)

  ipcMain.handle('bmchat:emailbots:list-search', async () =>
    listSearchableBotsAsync()
  )

  ipcMain.handle(
    'bmchat:emailbots:open-chat',
    async (_e, args: { accountId: number; botName: string }) => {
      const accountId = Number(args?.accountId) || 0
      const botName = args?.botName || ''
      if (!accountId || !botName) return { chatId: 0 }
      try {
        const chatId = await openBotChatByName(accountId, botName)
        return { chatId }
      } catch (e) {
        log.warn('open-chat failed for %s', botName, e)
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
      bot.attachedChatIds = prev.attachedChatIds ?? bot.attachedChatIds
      bots[existingIdx] = bot
    } else {
      // Name must be unique within the account.
      if (findByName(bot.ownerAccountId, bot.name)) {
        return { ok: false, error: 'name_taken' }
      }
      bots.push(bot)
    }
    try {
      await ensureBotContact(bot)
    } catch (e) {
      log.warn('ensureBotContact on save failed for %s', bot.name, e)
    }
    await saveStore()
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
    await saveStore()
    return bots
  })

  ipcMain.handle(
    'bmchat:emailbots:set-enabled',
    async (_e, args: { id: string; enabled: boolean }) => {
      const bot = bots.find(b => b.id === args.id)
      if (bot) {
        bot.enabled = !!args.enabled
        await saveStore()
      }
      return bots
    }
  )

  ipcMain.handle(
    'bmchat:emailbots:attach-chat',
    async (_e, args: { id: string; chatId: number }) => {
      const bot = bots.find(b => b.id === args.id)
      if (!bot || !args.chatId) return { ok: false }
      const ids = new Set(bot.attachedChatIds ?? [])
      ids.add(args.chatId)
      bot.attachedChatIds = [...ids]
      await saveStore()
      return { ok: true, bot }
    }
  )

  ipcMain.handle(
    'bmchat:emailbots:detach-chat',
    async (_e, args: { id: string; chatId: number }) => {
      const bot = bots.find(b => b.id === args.id)
      if (!bot) return { ok: false }
      bot.attachedChatIds = (bot.attachedChatIds ?? []).filter(
        id => id !== args.chatId
      )
      await saveStore()
      return { ok: true, bot }
    }
  )
}
