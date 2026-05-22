// BMChat 2.49.87 (Phase 6 шаг 2): durable scheduled messages on the desktop. The previous
// 2.49.84 port stored the queue in renderer-side localStorage — that survived a renderer
// reload but not the user closing the whole app. The persistent queue now lives in the
// Electron main process (see `target-electron/src/scheduled-messages.ts`) and is reachable
// through the abstract `Runtime` interface, so the same module works for the web/Tauri
// targets too (those still keep a localStorage fallback inside their runtime adapter).
//
// On bootstrap we:
//   1. Migrate any legacy `bmchat.scheduled-messages.v1` localStorage entries into the
//      main-process queue (one-shot, then we clear the key).
//   2. Ask the main process to flush deliveries whose timers have already fired while the
//      renderer was not yet listening.
//   3. Subscribe to `onBMChatScheduledDue` so any future timer fire triggers a sendMsg.

import { runtime } from '@deltachat-desktop/runtime-interface'
import { BackendRemote } from '../backend-com'
import { getLogger } from '../../../shared/logger'

import type { T } from '@deltachat/jsonrpc-client'
import type { BMChatScheduledMessage } from '@deltachat-desktop/runtime-interface'

const log = getLogger('scheduler/scheduledMessages')

const LEGACY_STORAGE_KEY = 'bmchat.scheduled-messages.v1'

export type ScheduledMessage = BMChatScheduledMessage

const listeners = new Set<() => void>()
let bootstrapped = false
let inflightSendIds = new Set<string>()

function notify(): void {
  for (const listener of listeners) {
    try {
      listener()
    } catch (e) {
      log.warn('Listener threw', e)
    }
  }
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export async function listAll(): Promise<ScheduledMessage[]> {
  try {
    return await runtime.bmchatScheduledList()
  } catch (e) {
    log.warn('Failed to load scheduled messages from runtime', e)
    return []
  }
}

export async function listByChat(
  accountId: number,
  chatId: number
): Promise<ScheduledMessage[]> {
  const items = await listAll()
  return items.filter(m => m.accountId === accountId && m.chatId === chatId)
}

export function newId(): string {
  if (typeof window.crypto?.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export async function schedule(message: ScheduledMessage): Promise<void> {
  await runtime.bmchatScheduledPut(message)
  notify()
}

export async function cancel(id: string): Promise<void> {
  await runtime.bmchatScheduledRemove(id)
  notify()
}

async function deliver(message: ScheduledMessage): Promise<void> {
  if (inflightSendIds.has(message.id)) return
  inflightSendIds.add(message.id)
  try {
    log.info('Delivering scheduled message', message.id)
    await BackendRemote.rpc.sendMsg(message.accountId, message.chatId, {
      file: message.file ?? null,
      filename: message.filename ?? null,
      viewtype: (message.viewtype ?? null) as T.Viewtype | null,
      html: null,
      location: null,
      overrideSenderName: null,
      quotedMessageId: message.quotedMessageId ?? null,
      quotedText: null,
      text: message.text ?? null,
    })
    await runtime.bmchatScheduledAck(message.id)
    notify()
  } catch (e) {
    log.error('Failed to send scheduled message', message.id, e)
    // Leave the entry in the queue so the next renderer-ready / next timer retries.
  } finally {
    inflightSendIds.delete(message.id)
  }
}

async function migrateLegacyEntries(): Promise<void> {
  try {
    const raw = window.localStorage.getItem(LEGACY_STORAGE_KEY)
    if (!raw) return
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed) || parsed.length === 0) {
      window.localStorage.removeItem(LEGACY_STORAGE_KEY)
      return
    }
    log.info('Migrating', parsed.length, 'legacy localStorage scheduled messages')
    for (const entry of parsed) {
      if (!entry || typeof entry.id !== 'string') continue
      try {
        await runtime.bmchatScheduledPut(entry as ScheduledMessage)
      } catch (e) {
        log.warn('Failed to migrate scheduled message', entry?.id, e)
      }
    }
    window.localStorage.removeItem(LEGACY_STORAGE_KEY)
  } catch (e) {
    log.warn('Legacy scheduled-message migration failed', e)
  }
}

/**
 * Wire up the renderer side of the scheduler. Idempotent — multiple calls are no-ops.
 *
 * Should be invoked once after the BackendRemote bridge is ready (i.e. after
 * `runtime.initialize(...)` and after the account list has been hydrated). The function:
 *   - migrates legacy localStorage queue (if any),
 *   - subscribes to `bmchat:scheduled-due` events,
 *   - asks the main process to replay any timers whose moment passed while the renderer was
 *     offline so we deliver them immediately.
 */
export async function bootstrap(): Promise<void> {
  if (bootstrapped) return
  bootstrapped = true
  try {
    await migrateLegacyEntries()
    runtime.onBMChatScheduledDue(msg => {
      void deliver(msg)
    })
    await runtime.bmchatScheduledFlush()
  } catch (e) {
    log.warn('Scheduler bootstrap failed; will retry lazily', e)
    bootstrapped = false
  }
}
