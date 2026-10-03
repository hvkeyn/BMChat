import { useEffect, useState } from 'react'

import { onDCEvent } from '../backend-com'

export type TransferSample = {
  got: number
  total: number
  at: number
  firstAt: number
  firstGot: number
}

const samples = new Map<number, TransferSample>()
const startedAt = new Map<number, number>()
const listeners = new Set<() => void>()

export function noteTransferLine(line: string) {
  if (!line.startsWith('bmchat-xfer ')) return
  const parts = line.split(' ')
  if (parts.length < 4) return
  const msgId = Number(parts[1])
  const got = Number(parts[2])
  const total = Number(parts[3])
  if (!Number.isFinite(msgId) || !Number.isFinite(got) || !Number.isFinite(total)) {
    return
  }
  const now = Date.now()
  const prev = samples.get(msgId)
  samples.set(msgId, {
    got,
    total,
    at: now,
    firstAt: prev?.firstAt ?? now,
    firstGot: prev?.firstGot ?? got,
  })
  listeners.forEach(listener => listener())
}

export function getTransfer(msgId: number): TransferSample | undefined {
  return samples.get(msgId)
}

export function clearTransfer(msgId: number) {
  samples.delete(msgId)
  startedAt.delete(msgId)
}

/** Milliseconds since this message was first seen mid-transfer in the UI. */
export function transferElapsedMs(msgId: number): number {
  const now = Date.now()
  const started = startedAt.get(msgId)
  if (started == null) {
    startedAt.set(msgId, now)
    return 0
  }
  return now - started
}

export function secondsLeft(sample: TransferSample): number {
  if (sample.total <= 0 || sample.got >= sample.total || sample.got <= sample.firstGot) {
    return -1
  }
  const elapsed = Math.max(1, sample.at - sample.firstAt)
  const rate = (sample.got - sample.firstGot) / elapsed
  if (rate <= 0) return -1
  return Math.ceil((sample.total - sample.got) / rate / 1000)
}

export function useTransferSample(msgId: number, active: boolean): TransferSample | undefined {
  const [sample, setSample] = useState(() => samples.get(msgId))
  useEffect(() => {
    if (!active) {
      clearTransfer(msgId)
      setSample(undefined)
      return
    }
    transferElapsedMs(msgId)
    const sync = () => setSample(samples.get(msgId))
    listeners.add(sync)
    sync()
    const clock = window.setInterval(sync, 1000)
    return () => {
      listeners.delete(sync)
      window.clearInterval(clock)
    }
  }, [msgId, active])
  return sample
}

export function installTransferProgress(accountId: number) {
  return onDCEvent(accountId, 'Info', event => {
    if (event.msg.startsWith('bmchat-xfer ')) noteTransferLine(event.msg)
  })
}
