// BMChat 2.49.87 (Phase 6 шаг 2): durable scheduled-message queue that lives in the Electron
// main process instead of the renderer's localStorage. The queue is persisted as a JSON file
// inside the user data directory, every entry has its own `setTimeout`, and the timers keep
// firing even when the main BrowserWindow is hidden to tray.
//
// When a timer fires:
//   1. The entry is marked `deliveryPendingSinceMs` and persisted.
//   2. If a renderer is alive we emit `bmchat:scheduled-due` over IPC so it can call
//      `BackendRemote.rpc.sendMsg(...)` synchronously.
//   3. If the renderer is hidden or not yet listening, the entry stays in the "pending" state
//      and is delivered on the next renderer-ready event (see `flushPendingDeliveries`).
//
// Older releases stored the queue in the renderer-side localStorage key
// `bmchat.scheduled-messages.v1`. The renderer migrates those entries into this queue on its
// first start after the upgrade, see `scheduler/scheduledMessages.ts` in the frontend package.

import { app, ipcMain, BrowserWindow } from 'electron'
import { promises as fs } from 'fs'
import { join } from 'path'

import { getConfigPath } from './application-constants.js'
import { getLogger } from '../../shared/logger.js'

const log = getLogger('main/scheduled-messages')

const QUEUE_FILENAME = 'bmchat-scheduled-messages.json'

export interface ScheduledMessage {
  id: string
  accountId: number
  chatId: number
  scheduledAtMs: number
  text: string
  file?: string | null
  filename?: string | null
  viewtype?: string | null
  quotedMessageId?: number | null
  createdAtMs: number
  /** Set when the timer has fired but the renderer was not ready to deliver. */
  deliveryPendingSinceMs?: number | null
}

const timers = new Map<string, NodeJS.Timeout>()
let queue: ScheduledMessage[] = []
let initialised = false

function queuePath(): string {
  return join(getConfigPath(), QUEUE_FILENAME)
}

async function readQueueFromDisk(): Promise<ScheduledMessage[]> {
  try {
    const raw = await fs.readFile(queuePath(), 'utf8')
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      x =>
        x &&
        typeof x.id === 'string' &&
        typeof x.accountId === 'number' &&
        typeof x.chatId === 'number' &&
        typeof x.scheduledAtMs === 'number'
    ) as ScheduledMessage[]
  } catch (e: any) {
    if (e && e.code === 'ENOENT') return []
    log.warn('Failed to read scheduled-messages queue', e)
    return []
  }
}

async function writeQueueToDisk(): Promise<void> {
  try {
    await fs.writeFile(queuePath(), JSON.stringify(queue, null, 2), 'utf8')
  } catch (e) {
    log.warn('Failed to persist scheduled-messages queue', e)
  }
}

function findMainRenderer(): BrowserWindow | null {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win.isDestroyed() && !win.webContents.isDestroyed()) {
      return win
    }
  }
  return null
}

function broadcastDue(msg: ScheduledMessage): boolean {
  const win = findMainRenderer()
  if (!win) {
    log.info('No renderer to deliver scheduled message %s; will retry on next attach', msg.id)
    return false
  }
  try {
    win.webContents.send('bmchat:scheduled-due', msg)
    return true
  } catch (e) {
    log.warn('IPC send failed for scheduled message %s', msg.id, e)
    return false
  }
}

function armTimer(msg: ScheduledMessage): void {
  const existing = timers.get(msg.id)
  if (existing) clearTimeout(existing)
  const delay = Math.max(0, msg.scheduledAtMs - Date.now())
  const handle = setTimeout(() => {
    void onTimerFired(msg.id)
  }, delay)
  timers.set(msg.id, handle)
}

async function onTimerFired(id: string): Promise<void> {
  const msg = queue.find(m => m.id === id)
  if (!msg) return
  msg.deliveryPendingSinceMs = Date.now()
  await writeQueueToDisk()
  if (broadcastDue(msg)) {
    log.info('Dispatched scheduled message %s to renderer', id)
  }
}

/** Re-emit `bmchat:scheduled-due` for every entry whose timer already fired. */
export function flushPendingDeliveries(): void {
  const now = Date.now()
  for (const msg of queue) {
    if (msg.scheduledAtMs <= now || msg.deliveryPendingSinceMs) {
      broadcastDue(msg)
    }
  }
}

export async function initScheduler(): Promise<void> {
  if (initialised) return
  initialised = true

  await app.whenReady()
  queue = await readQueueFromDisk()
  log.info('Loaded scheduled-messages queue (%d entries)', queue.length)
  for (const msg of queue) armTimer(msg)

  ipcMain.handle('bmchat:scheduled:list', () => queue.slice())

  ipcMain.handle('bmchat:scheduled:put', async (_e, msg: ScheduledMessage) => {
    queue = queue.filter(m => m.id !== msg.id)
    queue.push(msg)
    queue.sort((a, b) => a.scheduledAtMs - b.scheduledAtMs)
    await writeQueueToDisk()
    armTimer(msg)
    return queue.slice()
  })

  ipcMain.handle('bmchat:scheduled:remove', async (_e, id: string) => {
    queue = queue.filter(m => m.id !== id)
    const timer = timers.get(id)
    if (timer) {
      clearTimeout(timer)
      timers.delete(id)
    }
    await writeQueueToDisk()
    return queue.slice()
  })

  ipcMain.handle('bmchat:scheduled:ack', async (_e, id: string) => {
    queue = queue.filter(m => m.id !== id)
    const timer = timers.get(id)
    if (timer) {
      clearTimeout(timer)
      timers.delete(id)
    }
    await writeQueueToDisk()
    return queue.slice()
  })

  ipcMain.handle('bmchat:scheduled:flush', () => {
    flushPendingDeliveries()
  })
}
