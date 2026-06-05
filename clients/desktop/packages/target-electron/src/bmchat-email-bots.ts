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
    await publishBotSync(accountId, JSON.stringify(wrapper))
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

async function publishBotSync(
  accountId: number,
  plainJson: string
): Promise<void> {
  const now = Date.now()
  if (now - lastSyncPublishMs < MIN_SYNC_PUBLISH_MS) return
  lastSyncPublishMs = now
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
  } catch (e) {
    log.warn('publishBotSync failed', e)
  }
}

async function tryIngestBotSync(
  accountId: number,
  body: string
): Promise<boolean> {
  const first = body.split(/\r?\n/, 1)[0]?.trim() || ''
  if (!first.startsWith(SYNC_MARKER)) return false
  let srcAccount = accountId
  const accIdx = first.indexOf('account=')
  if (accIdx >= 0) {
    const tail = first.slice(accIdx + 8).trim().split(/\s+/)[0]
    const n = parseInt(tail, 10)
    if (Number.isFinite(n) && n > 0) srcAccount = n
  }
  const payload = body.includes('\n')
    ? body.slice(body.indexOf('\n') + 1).trim()
    : ''
  if (!payload) return true
  try {
    const json = await openJson(srcAccount, payload)
    if (!json) return true
    const root = JSON.parse(json)
    const arr = root?.bots
    if (!Array.isArray(arr)) return true
    const merged = new Map<string, EmailBot>()
    for (const b of bots) {
      if (b.ownerAccountId === srcAccount) continue
      merged.set(b.id, b)
    }
    for (const raw of arr) {
      const b = sanitizeBot(raw)
      if (b && b.ownerAccountId === srcAccount) merged.set(b.id, b)
    }
    bots = Array.from(merged.values())
    await saveStore({ publishSync: false })
    await persistUiConfigForAccount(srcAccount, false)
    await migrateBotContacts()
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

/**
 * Returns true when {@link chatId} is a local bot home chat: a self-only
 * outgoing broadcast that is never queued to SMTP.
 */
async function isLocalBotChat(
  accountId: number,
  chatId: number
): Promise<boolean> {
  if (chatId <= 0) return false
  try {
    const rpc = getDCJsonrpcRemote().rpc
    const chat: any = await rpc.getBasicChatInfo(accountId, chatId)
    return chat?.chatType === 'OutBroadcast'
  } catch {
    return false
  }
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
        const migrated = await createLocalBotChat(bot, displayName)
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
    const created = await createLocalBotChat(bot, displayName)
    if (created > 0) {
      bot.botChatId = created
      haveLocal = true
    }
  }

  await saveStore()
}

async function migrateBotContacts(): Promise<void> {
  for (const bot of bots) {
    if (!bot.enabled) continue
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
    let homeBot =
      findBotByChatId(accountId, chatId) ?? findBotByChatIdAny(chatId)
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
        if (!findBotByChatId(accountId, chatId) && !findBotByChatIdAny(chatId)) {
          return
        }
        void (async () => {
          try {
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
  }).catch(() => {})

  ipcMain.handle('bmchat:emailbots:list', async () => {
    await reloadStoreMerged()
    return bots
  })

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
