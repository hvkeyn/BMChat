// BMChat desktop Telegram-bots backend — a port of the Android
// `org.thoughtcrime.securesms.bots` stack adapted to the Electron main
// process.
//
// Why the main process: the renderer has DNS hard-disabled
// (`MAP * ^NOTFOUND` in `index.ts`), so it cannot reach the BMChat
// Telegram Bot API gateway. All Telegram traffic therefore happens here,
// over plain HTTP against the literal VPS IP (the same endpoints the
// Android client uses, so a token works on both platforms).
//
// Flow:
//   1. The user registers a bot (BotFather token) and picks a target
//      BMChat chat. We validate the token via getMe.
//   2. A polling loop calls getUpdates(offset, 0) for every active bot,
//      groups media albums by `media_group_id`, and turns each update
//      into a "post" (text/caption + optional downloaded attachment).
//   3. Posts are either published straight into the target chat (via the
//      core JSON-RPC `sendMsg`) or — when "manual review" is on — queued
//      in a moderation store the renderer drives from the UI.
//
// Messages are posted through the same JSON-RPC remote the renderer uses
// (`getDCJsonrpcRemote().rpc`), so they go out via the user's own
// transport exactly like a normal outgoing message.

import { app, ipcMain } from 'electron'
import { promises as fs } from 'fs'
import * as fsSync from 'fs'
import * as http from 'http'
import * as crypto from 'crypto'
import { join } from 'path'
import { randomUUID } from 'crypto'

import { getConfigPath } from './application-constants.js'
import { getLogger } from '../../shared/logger.js'
import { getDCJsonrpcRemote, DCJsonrpcRemoteInitializedP } from './ipc.js'
import { openJson, sealJson } from './bmchat-email-bot-crypto.js'

const log = getLogger('main/bmchat-telegram-bots')

// Same gateway the Android client uses (see TelegramApi.java / TelegramProxy.java).
const API_BASE = 'http://5.187.4.132/bot-api/bot'
const FILE_BASE = 'http://5.187.4.132/bot-api/file/bot'
const PROXY_BASE = 'http://5.187.4.132/tgmedia/'
// Shared secret for the media proxy; must match TelegramProxy.SECRET byte-for-byte.
const PROXY_SECRET =
  '9e3c8f2a7b4d5e6f1a8b9c0d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f' +
  '9e3c8f2a7b4d5e6f1a8b9c0d2e3f4a5b'

const STORE_FILE = 'bmchat-telegram-bots.json'
const PENDING_FILE = 'bmchat-telegram-pending.json'
const UI_CONFIG_KEY = 'ui.bmchat.telegram_bots'
const SYNC_MARKER = 'BMCHAT-TG-BOT-SYNC v1'
const DC_CONTACT_ID_SELF = 1
const MIN_SYNC_PUBLISH_MS = 8_000
let lastSyncPublishMs = 0
const POLL_INTERVAL_MS = 90_000
const MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024 // 50 MB, matches Android
const REQUEST_TIMEOUT_MS = 35_000 // getUpdates(timeout=0) returns fast, but allow slack
const DOWNLOAD_TIMEOUT_MS = 120_000

type Viewtype =
  | 'Text'
  | 'Image'
  | 'Gif'
  | 'Sticker'
  | 'Audio'
  | 'Voice'
  | 'Video'
  | 'File'

interface Bot {
  id: string
  token: string
  telegramUsername?: string | null
  telegramName?: string | null
  telegramBotId?: number
  accountId: number
  targetChatId: number
  lastUpdateId: number
  lastPolledAtMs: number
  paused: boolean
  manualReview: boolean
  /** BMChat pseudo-contact id (synced from Android). */
  botContactId?: number
  /** Channels/groups the bot publishes into locally. */
  attachedChatIds?: number[]
}

/** Bot shape exposed to the renderer (token redacted). */
interface BotPublic {
  id: string
  displayName: string
  telegramUsername?: string | null
  accountId: number
  targetChatId: number
  paused: boolean
  manualReview: boolean
  lastPolledAtMs: number
  pendingCount: number
}

interface Attachment {
  fileId: string
  fileName: string | null
  mimeType: string
  viewtype: Viewtype
  width: number
  height: number
  duration: number
  size: number
}

interface PendingPost {
  id: string
  botId: string
  accountId: number
  chatId: number
  createdAtMs: number
  text: string
  /** Local temp path of an already-downloaded attachment, if any. */
  filePath?: string | null
  filename?: string | null
  mimeType?: string | null
  viewtype?: Viewtype | null
  width?: number
  height?: number
  duration?: number
  /** Short human preview for the moderation list. */
  preview: string
}

let bots: Bot[] = []
let pending: PendingPost[] = []
let pollTimer: NodeJS.Timeout | null = null
let polling = false
let initialised = false

// ---------------------------------------------------------------------------
//  persistence
// ---------------------------------------------------------------------------

function storePath(): string {
  return join(getConfigPath(), STORE_FILE)
}
function pendingPath(): string {
  return join(getConfigPath(), PENDING_FILE)
}

async function loadStore(): Promise<void> {
  try {
    const raw = await fs.readFile(storePath(), 'utf8')
    const parsed = JSON.parse(raw)
    bots = Array.isArray(parsed?.bots) ? parsed.bots : []
  } catch (e: any) {
    if (e?.code !== 'ENOENT') log.warn('Failed to read bots store', e)
    bots = []
  }
  try {
    const raw = await fs.readFile(pendingPath(), 'utf8')
    const parsed = JSON.parse(raw)
    pending = Array.isArray(parsed?.posts) ? parsed.posts : []
  } catch (e: any) {
    if (e?.code !== 'ENOENT') log.warn('Failed to read pending posts store', e)
    pending = []
  }
}

async function readUiConfigForAccount(accountId: number): Promise<Bot[]> {
  try {
    const raw = await getDCJsonrpcRemote().rpc.getConfig(accountId, UI_CONFIG_KEY)
    const opened = await openJson(accountId, raw)
    if (!opened) return []
    const root = JSON.parse(opened)
    const arr = root?.bots
    if (!Array.isArray(arr)) return []
    return arr.filter((b: unknown) => b && typeof b === 'object') as Bot[]
  } catch (e) {
    log.warn('readUiConfigForAccount failed account=%s', accountId, e)
    return []
  }
}

async function reloadStoreMerged(): Promise<void> {
  await loadStore()
  const merged = new Map<string, Bot>()
  for (const b of bots) merged.set(b.id, b)
  try {
    await DCJsonrpcRemoteInitializedP
    const accountIds = await getDCJsonrpcRemote().rpc.getAllAccountIds()
    for (const accountId of accountIds) {
      for (const b of await readUiConfigForAccount(accountId)) {
        if (!b.id) continue
        const local = merged.get(b.id)
        merged.set(b.id, local ? mergeTgBot(local, b) : b)
      }
    }
  } catch (e) {
    log.warn('reloadStoreMerged: ui-config read failed', e)
  }
  bots = Array.from(merged.values())
}

function mergeTgBot(local: Bot, remote: Bot): Bot {
  const attached = new Set<number>([
    ...(local.attachedChatIds ?? []),
    ...(remote.attachedChatIds ?? []),
  ])
  const botContactId =
    (local.botContactId ?? 0) > 0
      ? local.botContactId!
      : remote.botContactId ?? 0
  const botChatId =
    (local.botChatId ?? 0) > 0 ? local.botChatId! : remote.botChatId ?? 0
  const base =
    (remote.lastReplyAtMs ?? 0) >= (local.lastReplyAtMs ?? 0) ? remote : local
  return {
    ...base,
    botContactId,
    botChatId,
    attachedChatIds: Array.from(attached).filter(id => id > 0),
  }
}

async function persistUiConfigForAccount(
  accountId: number,
  publishSync: boolean
): Promise<void> {
  const accountBots = bots.filter(b => b.accountId === accountId)
  const wrapper = { bots: accountBots, updatedAtMs: Date.now() }
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
    if (b.accountId > 0) accountIds.add(b.accountId)
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
    let selfChat = await rpc.getChatIdByContactId(accountId, DC_CONTACT_ID_SELF)
    if (!selfChat || selfChat <= 0) {
      selfChat = await rpc.createChatByContactId(accountId, DC_CONTACT_ID_SELF)
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

export async function tryIngestTelegramBotSync(
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
    const merged = new Map<string, Bot>()
    for (const b of bots) {
      if (b.accountId === accountId) continue
      merged.set(b.id, b)
    }
    for (const raw of arr) {
      const b = raw as Bot
      if (b?.id) {
        b.accountId = accountId
        merged.set(b.id, b)
      }
    }
    bots = Array.from(merged.values())
    await saveStore({ publishSync: false })
    await persistUiConfigForAccount(accountId, false)
    return true
  } catch (e) {
    log.warn('tryIngestTelegramBotSync failed', e)
    return true
  }
}

async function saveStore(opts?: { publishSync?: boolean }): Promise<void> {
  try {
    await fs.writeFile(storePath(), JSON.stringify({ bots }, null, 2), 'utf8')
  } catch (e) {
    log.warn('Failed to persist bots store', e)
  }
  try {
    await persistAllUiConfig(!!opts?.publishSync)
  } catch (e) {
    log.warn('Failed to persist telegram bots ui-config', e)
  }
}

async function savePending(): Promise<void> {
  try {
    await fs.writeFile(
      pendingPath(),
      JSON.stringify({ posts: pending }, null, 2),
      'utf8'
    )
  } catch (e) {
    log.warn('Failed to persist pending posts store', e)
  }
}

function botDisplayName(bot: Bot): string {
  if (bot.telegramName) return bot.telegramName
  if (bot.telegramUsername) return '@' + bot.telegramUsername
  return bot.token.length > 12 ? bot.token.slice(0, 12) + '…' : bot.token
}

function toPublic(bot: Bot): BotPublic {
  return {
    id: bot.id,
    displayName: botDisplayName(bot),
    telegramUsername: bot.telegramUsername ?? null,
    accountId: bot.accountId,
    targetChatId: bot.targetChatId,
    paused: bot.paused,
    manualReview: bot.manualReview,
    lastPolledAtMs: bot.lastPolledAtMs,
    pendingCount: pending.filter(p => p.botId === bot.id).length,
  }
}

function tgBotEmail(bot: Bot): string {
  const slug = (bot.telegramUsername || bot.id).toLowerCase().replace(/[^a-z0-9_]/g, '')
  return 'tgbot.' + slug + '@bots.bmchat.local'
}

function withTgAttachedChat(bot: Bot, chatId: number): Bot {
  const prev = bot.attachedChatIds ?? []
  if (prev.includes(chatId)) return bot
  return { ...bot, attachedChatIds: [...prev, chatId] }
}

function withoutTgAttachedChat(bot: Bot, chatId: number): Bot {
  const prev = bot.attachedChatIds ?? []
  return { ...bot, attachedChatIds: prev.filter(id => id !== chatId) }
}

async function ensureTgBotContact(bot: Bot): Promise<Bot> {
  if (bot.botContactId && bot.botContactId > 0) return bot
  const rpc = getDCJsonrpcRemote().rpc
  const email = tgBotEmail(bot)
  let contactId = await rpc.lookupContactIdByAddr(bot.accountId, email)
  if (!contactId || contactId <= 0) {
    const label = bot.telegramName || (bot.telegramUsername ? '@' + bot.telegramUsername : 'Bot')
    contactId = await rpc.createContact(bot.accountId, label, email)
  }
  if (contactId > 0) return { ...bot, botContactId: contactId }
  return bot
}

async function resolveTargetChatIds(bot: Bot): Promise<number[]> {
  const out = new Set<number>()
  const rpc = getDCJsonrpcRemote().rpc
  const cid = bot.botContactId ?? 0
  if (cid > 0) {
    try {
      const home = await rpc.getChatIdByContactId(bot.accountId, cid)
      if (home > 0) out.add(home)
    } catch {}
  } else if (bot.targetChatId > 0) {
    out.add(bot.targetChatId)
  }
  for (const id of bot.attachedChatIds ?? []) {
    if (id > 0) out.add(id)
  }
  return Array.from(out)
}

async function isChatEligibleForTgBotAttach(
  accountId: number,
  chatId: number
): Promise<boolean> {
  if (accountId <= 0 || chatId <= 0) return false
  try {
    const chat: any = await getDCJsonrpcRemote().rpc.getBasicChatInfo(
      accountId,
      chatId
    )
    const chatType = String(chat?.chatType || '')
    const groupLike =
      chatType === 'Group' ||
      chatType === 'OutBroadcast' ||
      chatType === 'InBroadcast' ||
      !!chat?.isMultiUser
    return (
      chat?.canSend !== false &&
      groupLike &&
      !chat?.isContactRequest &&
      !chat?.isDeviceTalk &&
      !chat?.isSelfTalk
    )
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
//  Telegram Bot API client (per token)
// ---------------------------------------------------------------------------

function httpGetJson(url: string, timeoutMs: number): Promise<any> {
  return new Promise((resolve, reject) => {
    let settled = false
    const finish = (err: Error | null, value?: any) => {
      if (settled) return
      settled = true
      if (err) reject(err)
      else resolve(value)
    }
    let req: http.ClientRequest
    try {
      req = http.get(url, { timeout: timeoutMs }, res => {
        const chunks: Buffer[] = []
        let total = 0
        res.on('data', (c: Buffer) => {
          total += c.length
          if (total > 16 * 1024 * 1024) {
            res.destroy(new Error('response too large'))
            return
          }
          chunks.push(c)
        })
        res.on('end', () => {
          try {
            const text = Buffer.concat(chunks).toString('utf8')
            const json = JSON.parse(text)
            if (!json.ok) {
              if (res.statusCode === 401 || res.statusCode === 404) {
                finish(new Error('invalid token'))
              } else {
                finish(null, null)
              }
              return
            }
            finish(null, json)
          } catch (err) {
            finish(err as Error)
          }
        })
        res.on('error', e => finish(e))
      })
      req.on('timeout', () => {
        req.destroy()
        finish(new Error('request timeout'))
      })
      req.on('error', e => finish(e))
    } catch (err) {
      finish(err as Error)
    }
  })
}

async function tgGetMe(token: string): Promise<any | null> {
  const json = await httpGetJson(API_BASE + token + '/getMe', REQUEST_TIMEOUT_MS)
  return json?.result ?? null
}

async function tgGetUpdates(token: string, offset: number): Promise<any[]> {
  const params =
    'offset=' +
    offset +
    '&timeout=0&allowed_updates=' +
    encodeURIComponent(
      '["message","channel_post","edited_message","edited_channel_post"]'
    )
  const json = await httpGetJson(
    API_BASE + token + '/getUpdates?' + params,
    REQUEST_TIMEOUT_MS
  )
  return Array.isArray(json?.result) ? json.result : []
}

async function tgGetFilePath(
  token: string,
  fileId: string
): Promise<string | null> {
  const json = await httpGetJson(
    API_BASE + token + '/getFile?file_id=' + encodeURIComponent(fileId),
    REQUEST_TIMEOUT_MS
  )
  return json?.result?.file_path ?? null
}

/** AES-GCM-signed proxy URL — port of TelegramProxy.buildUrl. */
function buildProxyUrl(
  token: string,
  fileId: string,
  mimeType?: string | null,
  fileName?: string | null
): string | null {
  if (!token || !fileId) return null
  try {
    const payload: Record<string, unknown> = {
      v: 1,
      exp: Date.now() + 7 * 24 * 60 * 60 * 1000,
      t: token,
      f: fileId,
    }
    if (mimeType) payload.m = mimeType
    if (fileName) payload.n = fileName
    const key = crypto.createHash('sha256').update(PROXY_SECRET, 'utf8').digest()
    const nonce = crypto.randomBytes(12)
    const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce)
    const ct = Buffer.concat([
      cipher.update(JSON.stringify(payload), 'utf8'),
      cipher.final(),
    ])
    const tag = cipher.getAuthTag()
    const envelope = Buffer.concat([nonce, ct, tag])
    const b64 = envelope
      .toString('base64')
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '')
    return PROXY_BASE + b64
  } catch (e) {
    log.warn('buildProxyUrl failed', e)
    return null
  }
}

function downloadToFile(url: string, dest: string): Promise<void> {
  return new Promise((resolve, reject) => {
    let settled = false
    const finish = (err?: Error) => {
      if (settled) return
      settled = true
      if (err) reject(err)
      else resolve()
    }
    let stream: fsSync.WriteStream
    try {
      stream = fsSync.createWriteStream(dest)
    } catch (e) {
      reject(e as Error)
      return
    }
    const doRequest = (u: string, redirectsLeft: number) => {
      const req = http.get(u, { timeout: REQUEST_TIMEOUT_MS }, res => {
        if (
          redirectsLeft > 0 &&
          res.statusCode &&
          res.statusCode >= 300 &&
          res.statusCode < 400 &&
          res.headers.location
        ) {
          res.resume()
          doRequest(new URL(res.headers.location, u).toString(), redirectsLeft - 1)
          return
        }
        if (res.statusCode !== 200) {
          res.resume()
          finish(new Error('download HTTP ' + res.statusCode))
          return
        }
        res.pipe(stream)
        stream.on('finish', () => {
          stream.close()
          finish()
        })
        res.on('error', finish)
      })
      req.setTimeout(DOWNLOAD_TIMEOUT_MS, () => {
        req.destroy()
        finish(new Error('download timeout'))
      })
      req.on('error', finish)
    }
    doRequest(url, 5)
  })
}

function sanitizeFilename(n: string | null | undefined): string {
  if (!n) return 'file'
  let safe = n.replace(/[^A-Za-z0-9_.\-+()[\]]/g, '_')
  if (safe.length > 80) safe = safe.slice(0, 80)
  return safe || 'file'
}

async function downloadAttachment(
  bot: Bot,
  a: Attachment
): Promise<string | null> {
  if (a.size > MAX_ATTACHMENT_BYTES) {
    log.warn('attachment too large (%d bytes); skipping', a.size)
    return null
  }
  try {
    const filePath = await tgGetFilePath(bot.token, a.fileId)
    if (!filePath) return null
    const dir = join(app.getPath('temp'), 'bmchat-tgbots', bot.id)
    await fs.mkdir(dir, { recursive: true })
    const baseName = a.fileName
      ? sanitizeFilename(a.fileName)
      : sanitizeFilename(filePath.slice(filePath.lastIndexOf('/') + 1))
    const dest = join(dir, Date.now() + '_' + baseName)
    const url =
      filePath.startsWith('/') && a.fileId
        ? buildProxyUrl(bot.token, a.fileId, a.mimeType, a.fileName)
        : FILE_BASE + bot.token + '/' + filePath
    if (!url) return null
    await downloadToFile(url, dest)
    return dest
  } catch (e) {
    log.warn('downloadAttachment failed', e)
    return null
  }
}

// ---------------------------------------------------------------------------
//  Telegram message → post transform
// ---------------------------------------------------------------------------

function firstMessageLike(update: any): any | null {
  const keys = [
    'message',
    'channel_post',
    'business_message',
    'edited_business_message',
    'edited_message',
    'edited_channel_post',
  ]
  for (const k of keys) {
    if (update[k]) return update[k]
  }
  return null
}

function isBotControlCommand(m: any): boolean {
  const text: string = m.text || ''
  if (!text || text[0] !== '/') return false
  const head = text.split(/[\s@]/, 1)[0]
  return head.toLowerCase() === '/start'
}

function describeForwardAttribution(m: any): string | null {
  const origin = m.forward_origin
  if (origin) {
    const type = origin.type
    if (type === 'user' && origin.sender_user) {
      const name = joinName(origin.sender_user)
      if (name) return '↪ ' + name
    } else if (type === 'hidden_user' && origin.sender_user_name) {
      return '↪ ' + origin.sender_user_name
    } else if (type === 'chat' && origin.sender_chat) {
      const name = origin.sender_chat.title || origin.sender_chat.username
      if (name) return '↪ ' + name
    } else if (type === 'channel' && origin.chat) {
      const name = origin.chat.title || origin.chat.username
      if (name) return '↪ ' + name
    }
  }
  if (m.forward_from) {
    const name = joinName(m.forward_from)
    if (name) return '↪ ' + name
  }
  if (m.forward_from_chat) {
    const name = m.forward_from_chat.title || m.forward_from_chat.username
    if (name) return '↪ ' + name
  }
  return null
}

function joinName(user: any): string {
  if (!user) return ''
  const parts = [user.first_name, user.last_name].filter(Boolean)
  if (parts.length) return parts.join(' ')
  return user.username ? '@' + user.username : ''
}

function pickAttachment(m: any): Attachment | null {
  const photo = m.photo
  if (Array.isArray(photo) && photo.length > 0) {
    let biggest: any = null
    let biggestArea = 0
    for (const p of photo) {
      const area = (p.width || 0) * (p.height || 0)
      if (area >= biggestArea) {
        biggestArea = area
        biggest = p
      }
    }
    if (biggest) {
      return {
        fileId: biggest.file_id,
        fileName: null,
        mimeType: 'image/jpeg',
        viewtype: 'Image',
        width: biggest.width || 0,
        height: biggest.height || 0,
        duration: 0,
        size: biggest.file_size || 0,
      }
    }
  }
  const animation = m.animation
  if (animation) {
    const mime = animation.mime_type || 'video/mp4'
    return {
      fileId: animation.file_id,
      fileName: animation.file_name || 'animation.mp4',
      mimeType: mime,
      viewtype: mime.includes('gif') ? 'Gif' : 'Video',
      width: animation.width || 0,
      height: animation.height || 0,
      duration: animation.duration || 0,
      size: animation.file_size || 0,
    }
  }
  const video = m.video
  if (video) {
    return {
      fileId: video.file_id,
      fileName: video.file_name || 'video.mp4',
      mimeType: video.mime_type || 'video/mp4',
      viewtype: 'Video',
      width: video.width || 0,
      height: video.height || 0,
      duration: video.duration || 0,
      size: video.file_size || 0,
    }
  }
  const videoNote = m.video_note
  if (videoNote) {
    return {
      fileId: videoNote.file_id,
      fileName: 'video_note.mp4',
      mimeType: 'video/mp4',
      viewtype: 'Video',
      width: videoNote.length || 0,
      height: videoNote.length || 0,
      duration: videoNote.duration || 0,
      size: videoNote.file_size || 0,
    }
  }
  const voice = m.voice
  if (voice) {
    return {
      fileId: voice.file_id,
      fileName: 'voice.ogg',
      mimeType: voice.mime_type || 'audio/ogg',
      viewtype: 'Voice',
      width: 0,
      height: 0,
      duration: voice.duration || 0,
      size: voice.file_size || 0,
    }
  }
  const audio = m.audio
  if (audio) {
    let name: string = audio.file_name || ''
    if (!name) {
      const performer = audio.performer || ''
      const title = audio.title || 'audio'
      name = (performer ? performer + ' - ' : '') + title + '.mp3'
    }
    return {
      fileId: audio.file_id,
      fileName: name,
      mimeType: audio.mime_type || 'audio/mpeg',
      viewtype: 'Audio',
      width: 0,
      height: 0,
      duration: audio.duration || 0,
      size: audio.file_size || 0,
    }
  }
  const sticker = m.sticker
  if (sticker) {
    let mime = 'image/webp'
    let ext = 'webp'
    let viewtype: Viewtype = 'Sticker'
    if (sticker.is_video) {
      mime = 'video/webm'
      ext = 'webm'
      viewtype = 'Video'
    } else if (sticker.is_animated) {
      mime = 'application/x-tgsticker'
      ext = 'tgs'
      viewtype = 'File'
    }
    return {
      fileId: sticker.file_id,
      fileName: 'sticker.' + ext,
      mimeType: mime,
      viewtype,
      width: sticker.width || 0,
      height: sticker.height || 0,
      duration: 0,
      size: sticker.file_size || 0,
    }
  }
  const document = m.document
  if (document) {
    const mime = document.mime_type || 'application/octet-stream'
    let viewtype: Viewtype = 'File'
    if (mime.startsWith('video/')) viewtype = 'Video'
    else if (mime.startsWith('audio/')) viewtype = 'Audio'
    return {
      fileId: document.file_id,
      fileName: document.file_name || 'document',
      mimeType: mime,
      viewtype,
      width: 0,
      height: 0,
      duration: 0,
      size: document.file_size || 0,
    }
  }
  return null
}

function buildBodyText(m: any): string {
  const text: string = m.text || ''
  const caption: string = m.caption || ''
  let body = text || caption || ''
  const fwd = describeForwardAttribution(m)
  if (fwd) {
    body = body ? fwd + '\n\n' + body : fwd
  }
  return body
}

function previewFor(text: string, a: Attachment | null): string {
  const icon = a
    ? a.viewtype === 'Image'
      ? '🖼 '
      : a.viewtype === 'Video'
        ? '🎬 '
        : a.viewtype === 'Audio'
          ? '🎵 '
          : a.viewtype === 'Voice'
            ? '🎤 '
            : a.viewtype === 'Sticker'
              ? '🌟 '
              : '📎 '
    : ''
  const t = text.replace(/\s+/g, ' ').trim()
  const short = t.length > 120 ? t.slice(0, 120) + '…' : t
  return (icon + short).trim() || (a ? a.viewtype : 'сообщение')
}

// ---------------------------------------------------------------------------
//  publishing
// ---------------------------------------------------------------------------

async function publishToChat(
  accountId: number,
  chatId: number,
  text: string,
  attachment?: {
    filePath: string
    filename?: string | null
    viewtype?: Viewtype | null
    width?: number
    height?: number
    duration?: number
  } | null
): Promise<boolean> {
  try {
    const rpc = getDCJsonrpcRemote().rpc
    const data: any = {
      file: null,
      filename: null,
      viewtype: null,
      html: null,
      location: null,
      overrideSenderName: null,
      quotedMessageId: null,
      quotedText: null,
      text: text || null,
    }
    if (attachment && attachment.filePath) {
      data.file = attachment.filePath
      data.filename = attachment.filename ?? null
      data.viewtype = attachment.viewtype ?? 'File'
    }
    await rpc.sendMsg(accountId, chatId, data)
    return true
  } catch (e) {
    log.warn('publishToChat failed', e)
    return false
  }
}

interface PollSummary {
  received: number
  published: number
  queued: number
}

/**
 * Process a single getUpdates batch for one bot, grouping consecutive
 * items that share a media_group_id into one logical post.
 */
async function processBatch(
  bot: Bot,
  updates: any[],
  summary: PollSummary
): Promise<number> {
  let newest = bot.lastUpdateId
  let i = 0
  while (i < updates.length) {
    const update = updates[i]
    const updateId = update.update_id || 0
    if (updateId > newest) newest = updateId
    const m = firstMessageLike(update)
    if (!m) {
      i++
      continue
    }
    summary.received++

    // Skip bot control commands like /start.
    if (isBotControlCommand(m)) {
      i++
      continue
    }

    // Album grouping: consume consecutive updates with the same media_group_id.
    const groupId: string = m.media_group_id || ''
    const groupParts: any[] = [m]
    if (groupId) {
      let j = i + 1
      while (j < updates.length) {
        const nm = firstMessageLike(updates[j])
        if (!nm || nm.media_group_id !== groupId) break
        const nid = updates[j].update_id || 0
        if (nid > newest) newest = nid
        groupParts.push(nm)
        summary.received++
        j++
      }
      i = j
    } else {
      i++
    }

    // For an album we post each part; caption only on the first.
    for (let pIdx = 0; pIdx < groupParts.length; pIdx++) {
      const part = groupParts[pIdx]
      const text = pIdx === 0 ? buildBodyText(part) : ''
      const attachment = pickAttachment(part)
      if (!text && !attachment) continue

      let filePath: string | null = null
      if (attachment) {
        filePath = await downloadAttachment(bot, attachment)
      }

      if (bot.manualReview) {
        const post: PendingPost = {
          id: randomUUID(),
          botId: bot.id,
          accountId: bot.accountId,
          chatId: bot.targetChatId,
          createdAtMs: Date.now(),
          text,
          filePath,
          filename: attachment?.fileName ?? null,
          mimeType: attachment?.mimeType ?? null,
          viewtype: attachment?.viewtype ?? null,
          width: attachment?.width,
          height: attachment?.height,
          duration: attachment?.duration,
          preview: previewFor(text, attachment),
        }
        pending.push(post)
        summary.queued++
      } else {
        const targets = await resolveTargetChatIds(bot)
        let anyOk = false
        for (const targetChatId of targets) {
          const ok = await publishToChat(
            bot.accountId,
            targetChatId,
            attachment && filePath
              ? text
              : text ||
                  (attachment ? '[медиа ' + attachment.viewtype + ']' : ''),
            attachment && filePath
              ? {
                  filePath,
                  filename: attachment.fileName,
                  viewtype: attachment.viewtype,
                  width: attachment.width,
                  height: attachment.height,
                  duration: attachment.duration,
                }
              : null
          )
          if (ok) anyOk = true
        }
        if (anyOk) summary.published++
      }
    }
  }
  return newest
}

async function pollOne(bot: Bot, summary: PollSummary): Promise<void> {
  // Refresh getMe metadata if missing or stale (>1 day).
  if (
    !bot.telegramBotId ||
    !bot.telegramName ||
    Date.now() - bot.lastPolledAtMs > 24 * 60 * 60 * 1000
  ) {
    try {
      const me = await tgGetMe(bot.token)
      if (me) {
        bot.telegramUsername = me.username ?? bot.telegramUsername
        bot.telegramName = me.first_name ?? bot.telegramName
        bot.telegramBotId = me.id ?? bot.telegramBotId
      }
    } catch (e) {
      log.warn('getMe refresh failed for %s', bot.id, e)
    }
  }

  const offset = bot.lastUpdateId === 0 ? 0 : bot.lastUpdateId + 1
  let updates: any[]
  try {
    updates = await tgGetUpdates(bot.token, offset)
  } catch (e) {
    log.warn('getUpdates failed for %s: %s', bot.id, (e as Error)?.message)
    bot.lastPolledAtMs = Date.now()
    return
  }
  if (updates.length === 0) {
    bot.lastPolledAtMs = Date.now()
    return
  }
  const newest = await processBatch(bot, updates, summary)
  bot.lastUpdateId = Math.max(newest, bot.lastUpdateId)
  bot.lastPolledAtMs = Date.now()
}

async function pollAll(): Promise<PollSummary> {
  const summary: PollSummary = { received: 0, published: 0, queued: 0 }
  if (polling) return summary
  polling = true
  try {
    let changed = false
    for (const bot of bots) {
      if (bot.paused) continue
      try {
        const before = bot.lastUpdateId
        await pollOne(bot, summary)
        if (bot.lastUpdateId !== before) changed = true
        else changed = true // lastPolledAtMs updated anyway
      } catch (e) {
        log.warn('poll failed for bot %s', bot.id, e)
      }
    }
    if (changed) await saveStore()
    if (summary.queued > 0) await savePending()
  } finally {
    polling = false
  }
  return summary
}

// ---------------------------------------------------------------------------
//  pending-post moderation
// ---------------------------------------------------------------------------

async function publishPending(id: string): Promise<boolean> {
  const post = pending.find(p => p.id === id)
  if (!post) return false
  const ok = await publishToChat(
    post.accountId,
    post.chatId,
    post.filePath
      ? post.text
      : post.text || (post.viewtype ? '[медиа ' + post.viewtype + ']' : ''),
    post.filePath
      ? {
          filePath: post.filePath,
          filename: post.filename,
          viewtype: post.viewtype ?? 'File',
          width: post.width,
          height: post.height,
          duration: post.duration,
        }
      : null
  )
  if (ok) {
    pending = pending.filter(p => p.id !== id)
    await savePending()
  }
  return ok
}

async function dropPending(id: string): Promise<void> {
  const post = pending.find(p => p.id === id)
  if (post?.filePath) {
    try {
      await fs.unlink(post.filePath)
    } catch {}
  }
  pending = pending.filter(p => p.id !== id)
  await savePending()
}

// ---------------------------------------------------------------------------
//  init + IPC
// ---------------------------------------------------------------------------

export async function initTelegramBots(): Promise<void> {
  if (initialised) return
  initialised = true

  await app.whenReady()
  await reloadStoreMerged()
  log.info('Loaded Telegram bots store (%d bots, %d pending)', bots.length, pending.length)

  // Defer polling until the core JSON-RPC remote is ready, otherwise
  // publishing would throw before the controller is initialised.
  DCJsonrpcRemoteInitializedP.then(() => {
    if (pollTimer) return
    // initial poll shortly after startup, then on an interval
    setTimeout(() => {
      pollAll().catch(err => log.warn('initial poll failed', err))
    }, 15_000)
    pollTimer = setInterval(() => {
      pollAll().catch(err => log.warn('scheduled poll failed', err))
    }, POLL_INTERVAL_MS)
  }).catch(() => {})

  ipcMain.handle('bmchat:tgbots:list', () => bots.map(toPublic))

  ipcMain.handle('bmchat:tgbots:list-config', () =>
    bots.map(b => ({
      id: b.id,
      accountId: b.accountId,
      botContactId: b.botContactId ?? 0,
      attachedChatIds: b.attachedChatIds ?? [],
      telegramUsername: b.telegramUsername ?? null,
      telegramName: b.telegramName ?? null,
    }))
  )

  ipcMain.handle(
    'bmchat:tgbots:attach-chat',
    async (_e, args: { id: string; chatId: number }) => {
      await reloadStoreMerged()
      let bot = bots.find(b => b.id === args?.id)
      const chatId = Number(args?.chatId) || 0
      if (!bot || chatId <= 0) return { ok: false, error: 'invalid' }
      if (!(await isChatEligibleForTgBotAttach(bot.accountId, chatId))) {
        return { ok: false, error: 'cannot_send' }
      }
      try {
        bot = await ensureTgBotContact(bot)
        if (bot.targetChatId === chatId) {
          return { ok: false, error: 'home_chat' }
        }
        const updated = withTgAttachedChat(bot, chatId)
        const idx = bots.findIndex(b => b.id === bot!.id)
        if (idx >= 0) bots[idx] = updated
        await saveStore({ publishSync: true })
        return { ok: true, bot: updated }
      } catch (e) {
        log.warn('tgbots attach-chat failed', e)
        return { ok: false, error: 'failed' }
      }
    }
  )

  ipcMain.handle(
    'bmchat:tgbots:detach-chat',
    async (_e, args: { id: string; chatId: number }) => {
      await reloadStoreMerged()
      const bot = bots.find(b => b.id === args?.id)
      const chatId = Number(args?.chatId) || 0
      if (!bot || chatId <= 0) return { ok: false, error: 'invalid' }
      const updated = withoutTgAttachedChat(bot, chatId)
      const idx = bots.findIndex(b => b.id === bot.id)
      if (idx >= 0) bots[idx] = updated
      await saveStore({ publishSync: true })
      return { ok: true, bot: updated }
    }
  )

  ipcMain.handle(
    'bmchat:tgbots:add',
    async (
      _e,
      args: { token: string; accountId: number; chatId: number }
    ) => {
      const token = (args?.token || '').trim()
      if (!token || !/^\d+:[\w-]+$/.test(token)) {
        return { ok: false, error: 'invalid_token' }
      }
      if (bots.some(b => b.token === token)) {
        return { ok: false, error: 'already_added' }
      }
      let me: any = null
      try {
        me = await tgGetMe(token)
      } catch (e) {
        return { ok: false, error: 'invalid_token' }
      }
      if (!me) {
        return { ok: false, error: 'unreachable' }
      }
      const bot: Bot = {
        id: randomUUID(),
        token,
        telegramUsername: me.username ?? null,
        telegramName: me.first_name ?? null,
        telegramBotId: me.id ?? 0,
        accountId: args.accountId,
        targetChatId: args.chatId,
        lastUpdateId: 0,
        lastPolledAtMs: 0,
        paused: false,
        manualReview: false,
      }
      bots.push(bot)
      await saveStore({ publishSync: true })
      // Kick an immediate poll for fast feedback.
      pollAll().catch(() => {})
      return { ok: true, bot: toPublic(bot) }
    }
  )

  ipcMain.handle('bmchat:tgbots:remove', async (_e, id: string) => {
    bots = bots.filter(b => b.id !== id)
    // clean up this bot's pending posts + temp files
    for (const p of pending.filter(p => p.botId === id)) {
      if (p.filePath) {
        try {
          await fs.unlink(p.filePath)
        } catch {}
      }
    }
    pending = pending.filter(p => p.botId !== id)
    await saveStore()
    await savePending()
    return bots.map(toPublic)
  })

  ipcMain.handle(
    'bmchat:tgbots:set-paused',
    async (_e, args: { id: string; paused: boolean }) => {
      const bot = bots.find(b => b.id === args.id)
      if (bot) {
        bot.paused = !!args.paused
        await saveStore({ publishSync: true })
      }
      return bots.map(toPublic)
    }
  )

  ipcMain.handle(
    'bmchat:tgbots:set-manual-review',
    async (_e, args: { id: string; manualReview: boolean }) => {
      const bot = bots.find(b => b.id === args.id)
      if (bot) {
        bot.manualReview = !!args.manualReview
        await saveStore({ publishSync: true })
      }
      return bots.map(toPublic)
    }
  )

  ipcMain.handle(
    'bmchat:tgbots:set-target',
    async (_e, args: { id: string; accountId: number; chatId: number }) => {
      const bot = bots.find(b => b.id === args.id)
      if (bot) {
        bot.accountId = args.accountId
        bot.targetChatId = args.chatId
        await saveStore({ publishSync: true })
      }
      return bots.map(toPublic)
    }
  )

  ipcMain.handle('bmchat:tgbots:poll-now', async () => {
    return await pollAll()
  })

  ipcMain.handle('bmchat:tgbots:pending-list', (_e, botId?: string) => {
    const list = botId ? pending.filter(p => p.botId === botId) : pending
    // Don't ship temp file paths to the renderer; preview text is enough.
    return list.map(p => ({
      id: p.id,
      botId: p.botId,
      createdAtMs: p.createdAtMs,
      preview: p.preview,
      hasAttachment: !!p.filePath,
      viewtype: p.viewtype ?? null,
    }))
  })

  ipcMain.handle('bmchat:tgbots:pending-publish', async (_e, id: string) => {
    return await publishPending(id)
  })

  ipcMain.handle('bmchat:tgbots:pending-drop', async (_e, id: string) => {
    await dropPending(id)
    return true
  })

  ipcMain.handle(
    'bmchat:tgbots:pending-publish-all',
    async (_e, botId?: string) => {
      const ids = (botId ? pending.filter(p => p.botId === botId) : pending).map(
        p => p.id
      )
      let published = 0
      for (const id of ids) {
        if (await publishPending(id)) published++
      }
      return published
    }
  )

  ipcMain.handle('bmchat:tgbots:pending-clear', async (_e, botId?: string) => {
    const toClear = botId ? pending.filter(p => p.botId === botId) : pending
    for (const p of toClear) {
      if (p.filePath) {
        try {
          await fs.unlink(p.filePath)
        } catch {}
      }
    }
    pending = botId ? pending.filter(p => p.botId !== botId) : []
    await savePending()
    return true
  })
}
