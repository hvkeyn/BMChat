// BMChat 2.49.84 (Phase 6, port of Android Phase 4B): scheduled messages on the desktop.
//
// The runtime keeps a flat array of pending messages in localStorage and re-arms a
// setTimeout for each entry on startup. When the timeout fires we call BackendRemote.rpc
// .sendMsg with the saved payload. localStorage survives renderer reloads but not full
// app exits — for production-grade durability a later patch should move this state into
// the Electron main process via IPC, but as a first port this matches what users would
// expect from a desktop messenger that's been left running.

import { BackendRemote } from '../backend-com'
import { getLogger } from '../../../shared/logger'

import type { T } from '@deltachat/jsonrpc-client'

const log = getLogger('scheduler/scheduledMessages')

const STORAGE_KEY = 'bmchat.scheduled-messages.v1'

export type ScheduledMessage = {
  id: string
  accountId: number
  chatId: number
  scheduledAtMs: number
  text: string
  file?: string | null
  filename?: string | null
  viewtype?: T.Viewtype | null
  quotedMessageId?: number | null
  createdAtMs: number
}

const timers = new Map<string, ReturnType<typeof setTimeout>>()
const listeners = new Set<() => void>()
let bootstrapped = false

function load(): ScheduledMessage[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed as ScheduledMessage[]
  } catch (e) {
    log.warn('Failed to read scheduled messages from storage', e)
    return []
  }
}

function persist(items: ScheduledMessage[]): void {
  try {
    items.sort((a, b) => a.scheduledAtMs - b.scheduledAtMs)
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(items))
  } catch (e) {
    log.warn('Failed to persist scheduled messages', e)
  }
  notify()
}

function notify(): void {
  for (const listener of listeners) {
    try {
      listener()
    } catch (e) {
      log.warn('Listener threw', e)
    }
  }
}

export function listAll(): ScheduledMessage[] {
  return load()
}

export function listByChat(
  accountId: number,
  chatId: number
): ScheduledMessage[] {
  return load().filter(m => m.accountId === accountId && m.chatId === chatId)
}

export function newId(): string {
  if (typeof window.crypto?.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export function schedule(message: ScheduledMessage): void {
  const all = load()
  all.push(message)
  persist(all)
  armTimer(message)
}

export function cancel(id: string): void {
  const all = load().filter(m => m.id !== id)
  persist(all)
  const timer = timers.get(id)
  if (timer != null) {
    clearTimeout(timer)
    timers.delete(id)
  }
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function armTimer(message: ScheduledMessage): void {
  const existing = timers.get(message.id)
  if (existing != null) clearTimeout(existing)
  const delay = Math.max(0, message.scheduledAtMs - Date.now())
  const handle = setTimeout(() => {
    void deliver(message)
  }, delay)
  timers.set(message.id, handle)
  log.debug('Armed scheduled message', message.id, 'in', delay, 'ms')
}

async function deliver(message: ScheduledMessage): Promise<void> {
  try {
    log.info('Delivering scheduled message', message.id)
    await BackendRemote.rpc.sendMsg(message.accountId, message.chatId, {
      file: message.file ?? null,
      filename: message.filename ?? null,
      viewtype: message.viewtype ?? null,
      html: null,
      location: null,
      overrideSenderName: null,
      quotedMessageId: message.quotedMessageId ?? null,
      quotedText: null,
      text: message.text ?? null,
    })
    cancel(message.id)
  } catch (e) {
    log.error('Failed to send scheduled message', message.id, e)
    // Leave it in the queue so a future restart retries.
  }
}

/**
 * Re-arm pending timers. Should be called once after the renderer is ready and the
 * BackendRemote bridge is wired up. Multiple calls are idempotent.
 */
export function bootstrap(): void {
  if (bootstrapped) return
  bootstrapped = true
  for (const msg of load()) {
    armTimer(msg)
  }
  log.info('Bootstrapped scheduled messages')
}
