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

const log = getLogger('main/bmchat-email-bots')

const STORE_FILE = 'bmchat-email-bots.json'
const DC_CONTACT_ID_SELF = 1

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
    bots = Array.isArray(parsed?.bots) ? parsed.bots : []
  } catch (e: any) {
    if (e?.code !== 'ENOENT') log.warn('Failed to read email bots store', e)
    bots = []
  }
}

async function saveStore(): Promise<void> {
  try {
    await fs.writeFile(storePath(), JSON.stringify({ bots }, null, 2), 'utf8')
  } catch (e) {
    log.warn('Failed to persist email bots store', e)
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
    createdAtMs: Number(input.createdAtMs) || Date.now(),
    lastReplyAtMs: Number(input.lastReplyAtMs) || 0,
    totalReplies: Number(input.totalReplies) || 0,
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
  try {
    const rpc = getDCJsonrpcRemote().rpc
    await rpc.miscSendTextMessage(accountId, chatId, '@' + bot.name + ': ' + reply)
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
  if (botsForAccount(accountId).length === 0) return
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

    if (msg.fromId === DC_CONTACT_ID_SELF) return
    const body: string = msg.text || ''
    if (!body) return

    const inv = parseInvocation(body, accountId)
    if (!inv) return
    const bot = inv.bot
    if (!bot.enabled) return

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

    if (senderEmail && !bot.subscribedUsers.includes(senderEmail)) {
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
    try {
      ;(remote as any).on(
        'IncomingMsg',
        (accountId: number, event: any) => {
          void handleIncoming(accountId, event?.chatId, event?.msgId)
        }
      )
    } catch (e) {
      log.warn('failed to subscribe to IncomingMsg', e)
    }
  }).catch(() => {})

  ipcMain.handle('bmchat:emailbots:list', () => bots)

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
      bots[existingIdx] = bot
    } else {
      // Name must be unique within the account.
      if (findByName(bot.ownerAccountId, bot.name)) {
        return { ok: false, error: 'name_taken' }
      }
      bots.push(bot)
    }
    await saveStore()
    return { ok: true, bot }
  })

  ipcMain.handle('bmchat:emailbots:remove', async (_e, id: string) => {
    bots = bots.filter(b => b.id !== id)
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
}
