import React from 'react'
import { C, T } from '@deltachat/jsonrpc-client'
import { filesize } from 'filesize'

import { BackendRemote } from '../../backend-com'
import {
  secondsLeft,
  transferElapsedMs,
  useTransferSample,
} from '../../bmchat/fileTransferProgress'
import { selectedAccountId } from '../../ScreenController'
import useTranslationFunction from '../../hooks/useTranslationFunction'

const FILE_VIEWS = new Set<T.Message['viewType']>([
  'File',
  'Image',
  'Gif',
  'Sticker',
  'Audio',
  'Voice',
  'Video',
  'Webxdc',
])

export type FileTransferPhase =
  | 'preparing'
  | 'sending'
  | 'send_failed'
  | 'receiving'
  | 'available'
  | 'failed'
  | 'downloaded'
  | 'sent'

export function fileTransferPhase(
  message: Pick<
    T.Message,
    'state' | 'downloadState' | 'file' | 'fileBytes' | 'fileName' | 'viewType'
  >
): FileTransferPhase | null {
  if (message.downloadState === 'InProgress') return 'receiving'
  if (message.downloadState === 'Available') return 'available'
  if (message.downloadState === 'Failure') return 'failed'
  const hasFile =
    FILE_VIEWS.has(message.viewType) ||
    Boolean(message.fileName) ||
    (message.fileBytes ?? 0) > 0
  if (!hasFile) return null
  if (message.state === C.DC_STATE_OUT_PREPARING) return 'preparing'
  if (message.state === C.DC_STATE_OUT_PENDING) return 'sending'
  if (message.state === C.DC_STATE_OUT_FAILED) return 'send_failed'
  if (
    message.state === C.DC_STATE_OUT_DELIVERED ||
    message.state === C.DC_STATE_OUT_MDN_RCVD
  ) {
    return 'sent'
  }
  if (
    message.downloadState === 'Done' &&
    (message.state === C.DC_STATE_IN_FRESH ||
      message.state === C.DC_STATE_IN_SEEN ||
      message.state === C.DC_STATE_IN_NOTICED)
  ) {
    return 'downloaded'
  }
  return null
}

export function showFileTransferInBubble(
  phase: FileTransferPhase | null
): boolean {
  return (
    phase === 'preparing' ||
    phase === 'sending' ||
    phase === 'send_failed' ||
    phase === 'receiving' ||
    phase === 'available' ||
    phase === 'failed'
  )
}

function phaseLabel(
  tx: (key: string) => string,
  phase: FileTransferPhase
): string {
  switch (phase) {
    case 'preparing':
      return tx('bmchat_transfer_preparing')
    case 'sending':
      return tx('bmchat_transfer_sending')
    case 'send_failed':
      return tx('bmchat_transfer_send_failed')
    case 'receiving':
      return tx('bmchat_transfer_receiving')
    case 'available':
      return tx('bmchat_transfer_not_downloaded')
    case 'failed':
      return tx('download_failed')
    case 'downloaded':
      return tx('bmchat_transfer_downloaded')
    case 'sent':
      return tx('bmchat_transfer_sent')
  }
}

export function fileTransferDetails(
  tx: (key: string) => string,
  message: T.Message
): { name: string | null; size: string | null; status: string | null } | null {
  const phase = fileTransferPhase(message)
  const name = message.fileName || null
  const size =
    message.fileBytes && message.fileBytes > 0
      ? String(filesize(message.fileBytes))
      : null
  if (!phase && !name && !size) return null
  return {
    name,
    size,
    status: phase ? phaseLabel(tx, phase) : null,
  }
}

export default function FileTransferStatus({
  message,
  tabindexForInteractiveContents,
}: {
  message: T.Message
  tabindexForInteractiveContents: -1 | 0
}) {
  const tx = useTranslationFunction()
  const phase = fileTransferPhase(message)
  const busy =
    phase === 'preparing' || phase === 'sending' || phase === 'receiving'
  const sample = useTransferSample(message.id, busy)
  if (!showFileTransferInBubble(phase) || phase == null) return null

  const canDownload = phase === 'available' || phase === 'failed'
  const includeName = !message.file
  const parts = [phaseLabel(key => tx(key as never), phase)]
  if (includeName && message.fileName) parts.push(message.fileName)
  let percent: number | null = null
  if (sample && sample.total > 0) {
    parts.push(
      tx(
        'bmchat_transfer_bytes' as never,
        String(filesize(sample.got)),
        String(filesize(sample.total))
      ) as unknown as string
    )
    const left = secondsLeft(sample)
    if (left >= 0 && left < 90) {
      parts.push(tx('bmchat_transfer_left_soon' as never) as unknown as string)
    } else if (left >= 90) {
      parts.push(
        tx(
          'bmchat_transfer_left_min' as never,
          String(Math.round(left / 60))
        ) as unknown as string
      )
    }
    percent = Math.min(100, Math.max(0, (sample.got / sample.total) * 100))
  } else if (message.fileBytes && message.fileBytes > 0) {
    parts.push(String(filesize(message.fileBytes)))
    const elapsed = transferElapsedMs(message.id)
    if (busy && elapsed >= 5000) {
      const seconds = Math.round(elapsed / 1000)
      const waited =
        seconds < 90
          ? (tx('bmchat_transfer_under_minute' as never) as unknown as string)
          : (tx(
              'bmchat_transfer_minutes' as never,
              String(Math.round(seconds / 60))
            ) as unknown as string)
      parts.push(
        tx('bmchat_transfer_elapsed' as never, waited) as unknown as string
      )
    }
  }
  const label = parts.join(' · ')

  return (
    <div
      className='bmchat-file-transfer'
      role='status'
      aria-busy={busy}
      aria-live='polite'
    >
      <div
        className={
          phase === 'failed' || phase === 'send_failed' ? 'failed' : undefined
        }
      >
        {label}
      </div>
      {busy && (
        <div
          className={
            percent == null
              ? 'bmchat-file-transfer-bar'
              : 'bmchat-file-transfer-bar is-determinate'
          }
          role='progressbar'
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={percent == null ? undefined : Math.round(percent)}
          aria-valuetext={label}
          style={
            percent == null
              ? undefined
              : ({
                  '--bmchat-transfer-pct': `${percent}%`,
                } as React.CSSProperties)
          }
        >
          <span />
        </div>
      )}
      {canDownload && (
        <button
          type='button'
          tabIndex={tabindexForInteractiveContents}
          onClick={() =>
            BackendRemote.rpc.downloadFullMessage(
              selectedAccountId(),
              message.id
            )
          }
        >
          {tx('download')}
        </button>
      )}
      {(busy || phase === 'failed' || phase === 'send_failed') && (
        <button
          type='button'
          tabIndex={tabindexForInteractiveContents}
          onClick={() => {
            const question = tx(
              'bmchat_transfer_cancel_body' as never
            ) as unknown as string
            if (!window.confirm(question)) return
            void BackendRemote.rpc.deleteMessages(selectedAccountId(), [
              message.id,
            ])
          }}
        >
          {tx('bmchat_transfer_cancel' as never) as unknown as string}
        </button>
      )}
    </div>
  )
}
