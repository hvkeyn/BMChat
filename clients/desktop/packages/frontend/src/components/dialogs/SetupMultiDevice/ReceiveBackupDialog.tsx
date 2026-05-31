import React, { useRef, useCallback, useEffect, useState } from 'react'

import { DialogBody, DialogFooter, FooterActions } from '../../Dialog'
import FooterActionButton from '../../Dialog/FooterActionButton'
import { QrReader } from '../../QrReader'
import {
  fileToBase64,
  base64ToImageData,
} from '../../QrReader/helper'
// @ts-ignore: virtual import resolved by esbuild-plugin-inline-worker
import QrWorker from '../../QrReader/qr.worker'
import useProcessQr from '../../../hooks/useProcessQr'
import { selectedAccountId } from '../../../ScreenController'
import { DialogWithHeader } from '../../Dialog'
import useTranslationFunction from '../../../hooks/useTranslationFunction'
import { getLogger } from '../../../../../shared/logger'
import { BackendRemote } from '../../../backend-com'

import styles from './styles.module.scss'

import type { DialogProps } from '../../../contexts/DialogContext'
import useAlertDialog from '../../../hooks/dialog/useAlertDialog'
import { runtime } from '@deltachat-desktop/runtime-interface'
import { SCAN_CONTEXT_TYPE } from '../../../hooks/useProcessQr'
import { DeltaProgressBar } from '../../Login-Styles'
import {
  bytesToBase64,
  createRelaySession,
  downloadAndDecryptRelayBackup,
  getRelayStatus,
  removeRelaySession,
  verifyRelayCode,
  type RelaySession,
  type RelayStatus,
} from './bmchatRelay'

/**
 * BMChat: deep-link payload encoded into the helper QR rendered on this
 * desktop. Mobile BMChat has an intent-filter on this scheme that opens
 * "Settings → Add Second Device" directly. Desktops without a working
 * webcam can therefore still bootstrap the multi-device flow without
 * making the user dig through the mobile menu.
 */
const HELPER_QR_DEEPLINK = 'bmchat://second-device'

/**
 * BMChat: `BackendRemote.rpc.createQrSvg` paints the upstream Delta
 * logo on top of every QR it produces (see
 * `deltachat-core-rust/src/qr_code_generator.rs`). The helper QR is
 * generic — it does not need the Delta brand mark — so we strip the
 * overlay group client-side. The SVG layout from core is stable:
 * one <rect> for the white background, one <g> for the QR pixels and
 * one <g> for the centred logo. Removing the last top-level <g> keeps
 * the QR code intact while replacing the logo footprint with plain
 * white space — which scanners actually prefer over a coloured
 * overlay.
 */
function stripQrLogo(svg: string): string {
  if (!svg) return svg
  try {
    const doc = new DOMParser().parseFromString(svg, 'image/svg+xml')
    const root = doc.documentElement
    if (!root || root.nodeName.toLowerCase() !== 'svg') return svg
    const groups = Array.from(root.children).filter(
      el => el.tagName.toLowerCase() === 'g'
    )
    if (groups.length >= 2) {
      // Last <g> is the logo overlay (qr_overlay_delta.svg-part).
      const logo = groups[groups.length - 1]
      logo.parentNode?.removeChild(logo)
    }
    return new XMLSerializer().serializeToString(root)
  } catch {
    return svg
  }
}

const log = getLogger('renderer/dialogs/SetupMultiDevice/ReceiveBackup')

type Props = {
  subtitle: string
}

export function ReceiveBackupDialog({ onClose }: Props & DialogProps) {
  const tx = useTranslationFunction()
  const accountId = selectedAccountId()
  const processQr = useProcessQr()
  const processingQrCode = useRef(false)
  const openAlertDialog = useAlertDialog()

  // BMChat: toggles between the standard camera scanner and the helper
  // QR screen that mobile clients can scan to be redirected to their
  // own "Add Second Device" flow.
  const [showHelperQr, setShowHelperQr] = useState(false)
  const [helperQrSvg, setHelperQrSvg] = useState<string | null>(null)
  const [helperQrUrl, setHelperQrUrl] = useState<string | null>(null)
  const [relaySession, setRelaySession] = useState<RelaySession | null>(null)
  const [relayStatus, setRelayStatus] = useState<RelayStatus | null>(null)
  const [relayCode, setRelayCode] = useState('')
  const [relayImporting, setRelayImporting] = useState(false)
  const [relayImportProgress, setRelayImportProgress] = useState<number | null>(null)

  // BMChat: dedicated worker + hidden file input so the helper-QR view
  // can decode QR codes from clipboard / screenshots without spinning
  // up the camera (which would otherwise prompt for permissions every
  // time the user toggles into this mode on a webcam-less PC).
  const helperFileInputRef = useRef<HTMLInputElement>(null)
  const helperWorkerRef = useRef<Worker | null>(null)
  if (helperWorkerRef.current === null && showHelperQr) {
    helperWorkerRef.current = new QrWorker() as Worker
  }
  useEffect(() => {
    return () => {
      helperWorkerRef.current?.terminate()
      helperWorkerRef.current = null
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    if (!showHelperQr || helperQrSvg) {
      return
    }
    createRelaySession()
      .then(async session => {
        const svg = await BackendRemote.rpc.createQrSvg(session.qrPayload)
        if (!cancelled) {
          setRelaySession(session)
          setHelperQrSvg(stripQrLogo(svg))
        }
      })
      .catch(err => {
        log.errorWithoutStackTrace('create relay session failed', err)
        if (!cancelled) {
          BackendRemote.rpc
            .createQrSvg(HELPER_QR_DEEPLINK)
            .then(svg => {
              if (!cancelled) setHelperQrSvg(stripQrLogo(svg))
            })
            .catch(qrErr => {
              log.errorWithoutStackTrace('createQrSvg fallback failed', qrErr)
            })
        }
      })
    return () => {
      cancelled = true
    }
  }, [showHelperQr, helperQrSvg])

  useEffect(() => {
    if (!showHelperQr || !relaySession) return
    let cancelled = false
    const poll = async () => {
      try {
        const status = await getRelayStatus(relaySession)
        if (!cancelled) setRelayStatus(status)
      } catch (err) {
        log.warn('relay status failed', err)
      }
    }
    void poll()
    const timer = window.setInterval(poll, 2500)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [showHelperQr, relaySession])

  useEffect(() => {
    return () => {
      if (relaySession) void removeRelaySession(relaySession)
    }
  }, [relaySession])

  useEffect(() => {
    const emitter = BackendRemote.getContextEvents(accountId)
    const onImexProgress = ({ progress }: { progress: number }) => {
      setRelayImportProgress(progress)
    }
    emitter.on('ImexProgress', onImexProgress)
    return () => {
      emitter.off('ImexProgress', onImexProgress)
    }
  }, [accountId])

  useEffect(() => {
    if (!helperQrSvg) {
      setHelperQrUrl(null)
      return
    }
    const url = URL.createObjectURL(
      new Blob([helperQrSvg], { type: 'image/svg+xml' })
    )
    setHelperQrUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [helperQrSvg])

  const onDone = useCallback(() => {
    onClose()
    processingQrCode.current = false
  }, [onClose])

  const handleError = useCallback(
    (error: any) => {
      log.errorWithoutStackTrace('QrReader process error: ', error)
      const errorMessage = error?.message || error.toString()
      openAlertDialog({
        message: `${tx('qrscan_failed')} ${errorMessage}`,
      })
    },
    [openAlertDialog, tx]
  )

  const handleScan = useCallback(
    async (data: string) => {
      if (data && !processingQrCode.current) {
        processingQrCode.current = true
        try {
          await processQr(
            accountId,
            data,
            SCAN_CONTEXT_TYPE.TRANSFER_BACKUP,
            onDone
          )
        } catch (error: any) {
          log.errorWithoutStackTrace('QrReader process error: ', error)
          handleError(error)
        }
        processingQrCode.current = false
      } else if (processingQrCode.current === true) {
        log.debug('Already processing a qr code')
      }
    },
    [accountId, processQr, onDone, handleError]
  )

  // BMChat: decode a QR code out of arbitrary ImageData via the same
  // jsqr-based worker the camera scanner uses.
  const decodeImageData = useCallback(
    async (imageData: ImageData): Promise<string | null> => {
      if (!helperWorkerRef.current) {
        helperWorkerRef.current = new QrWorker() as Worker
      }
      const worker = helperWorkerRef.current
      return new Promise(resolve => {
        worker.addEventListener(
          'message',
          ev => resolve((ev.data as string | null) ?? null),
          { once: true }
        )
        worker.postMessage(imageData)
      })
    },
    []
  )

  // BMChat: clipboard fallback for camera-less desktops. Mirrors
  // QrReader.handlePasteFromClipboard so the user can paste either an
  // image (e.g. a screenshot they took on the phone) or the raw
  // `DCBACKUP:` / `DCLOGIN:` / `DCACCOUNT:` URL text from any other
  // messenger (Telegram, e-mail, …).
  const handleHelperPaste = useCallback(async () => {
    try {
      let base64: string | null = null
      try {
        base64 = await runtime.readClipboardImage()
      } catch (err) {
        log.warn('helper paste: readClipboardImage failed', err)
      }
      if (base64) {
        const imageData = await base64ToImageData(base64)
        const decoded = await decodeImageData(imageData)
        if (decoded) {
          await handleScan(decoded.trim())
          return
        }
      }
      const text = await runtime.readClipboardText()
      if (text && text.trim()) {
        await handleScan(text.trim())
        return
      }
      openAlertDialog({
        message: tx('bmchat_multidevice_helper_qr_paste_empty'),
      })
    } catch (err) {
      handleError(err)
    }
  }, [decodeImageData, handleScan, handleError, openAlertDialog, tx])

  const handleHelperFileChange = useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      if (!event.target.files || event.target.files.length === 0) return
      const file = event.target.files[0]
      try {
        const base64 = await fileToBase64(file)
        const imageData = await base64ToImageData(base64)
        const decoded = await decodeImageData(imageData)
        if (decoded) {
          await handleScan(decoded.trim())
        } else {
          openAlertDialog({
            message: tx('bmchat_multidevice_helper_qr_paste_empty'),
          })
        }
      } catch (err) {
        handleError(err)
      } finally {
        if (helperFileInputRef.current) helperFileInputRef.current.value = ''
      }
    },
    [decodeImageData, handleScan, handleError, openAlertDialog, tx]
  )

  const handleHelperOpenFile = useCallback(() => {
    helperFileInputRef.current?.click()
  }, [])

  const handleRelayImport = useCallback(async () => {
    if (!relaySession || !relayStatus?.uploaded || !relayStatus.code_hash) return
    try {
      setRelayImporting(true)
      setRelayImportProgress(0)
      const ok = await verifyRelayCode(
        relaySession,
        relayCode,
        relayStatus.code_hash
      )
      if (!ok) {
        openAlertDialog({
          message: tx('bmchat_multidevice_relay_code_wrong'),
        })
        return
      }
      const decrypted = await downloadAndDecryptRelayBackup(relaySession)
      const backupPath = await runtime.writeTempFileFromBase64(
        `bmchat-relay-${relaySession.sid}.tar`,
        bytesToBase64(decrypted)
      )
      await BackendRemote.rpc.importBackup(accountId, backupPath, null)
      await removeRelaySession(relaySession)
      onDone()
      window.__selectAccount(accountId)
    } catch (err) {
      handleError(err)
    } finally {
      setRelayImporting(false)
      setRelayImportProgress(null)
    }
  }, [
    accountId,
    handleError,
    onDone,
    openAlertDialog,
    relayCode,
    relaySession,
    relayStatus,
    tx,
  ])

  return (
    <DialogWithHeader
      title={tx('multidevice_receiver_title')}
      onClose={onClose}
    >
      <DialogBody>
        {!showHelperQr && (
          <>
            <p className={styles.receiveSteps}>
              {tx('multidevice_open_settings_on_other_device')}
            </p>
            <QrReader onScanSuccess={handleScan} onError={handleError} />
          </>
        )}
        {showHelperQr && (
          <div className={styles.helperQrContainer}>
            <p className={styles.receiveSteps}>
              {tx('bmchat_multidevice_helper_qr_intro')}
            </p>
            {helperQrUrl ? (
              <img
                className={styles.helperQrImage}
                src={helperQrUrl}
                alt='QR'
              />
            ) : (
              <div className={styles.helperQrImage}>…</div>
            )}
            <ol className={styles.helperQrSteps}>
              <li>{tx('bmchat_multidevice_helper_qr_step1')}</li>
              <li>{tx('bmchat_multidevice_helper_qr_step2')}</li>
            </ol>
            <div className={styles.relayStatusBox}>
              <div>
                {relayStatus?.uploaded
                  ? tx('bmchat_multidevice_relay_uploaded')
                  : tx('bmchat_multidevice_relay_waiting')}
              </div>
              {relayStatus?.uploaded && (
                <div className={styles.relayCodeRow}>
                  <input
                    className={styles.relayCodeInput}
                    value={relayCode}
                    inputMode='numeric'
                    maxLength={6}
                    placeholder='000000'
                    onChange={ev =>
                      setRelayCode(ev.target.value.replace(/\D/g, '').slice(0, 6))
                    }
                    disabled={relayImporting}
                  />
                  <button
                    type='button'
                    className={styles.helperQrInputButton}
                    onClick={handleRelayImport}
                    disabled={relayCode.length !== 6 || relayImporting}
                  >
                    {relayImporting
                      ? tx('transferring')
                      : tx('bmchat_multidevice_relay_import_button')}
                  </button>
                </div>
              )}
              {relayImportProgress !== null && (
                <DeltaProgressBar
                  progress={relayImportProgress}
                  intent='success'
                />
              )}
            </div>
            <div className={styles.helperQrInputs}>
              <button
                type='button'
                className={styles.helperQrInputButton}
                onClick={handleHelperPaste}
              >
                {tx('bmchat_multidevice_helper_qr_paste_button')}
              </button>
              <button
                type='button'
                className={styles.helperQrInputButton}
                onClick={handleHelperOpenFile}
              >
                {tx('bmchat_multidevice_helper_qr_file_button')}
              </button>
              <input
                ref={helperFileInputRef}
                type='file'
                accept='image/*'
                style={{ display: 'none' }}
                onChange={handleHelperFileChange}
              />
            </div>
            <p className={styles.helperQrFallbackHint}>
              {tx('bmchat_multidevice_helper_qr_no_lan_hint')}
            </p>
          </div>
        )}
      </DialogBody>
      <DialogFooter>
        <FooterActions align='spaceBetween'>
          <FooterActionButton
            onClick={() => runtime.openHelpWindow('multiclient')}
          >
            {tx('troubleshooting')}
          </FooterActionButton>
          <FooterActionButton onClick={() => setShowHelperQr(v => !v)}>
            {showHelperQr
              ? tx('bmchat_multidevice_back_to_camera_button')
              : tx('bmchat_multidevice_show_helper_qr_button')}
          </FooterActionButton>
        </FooterActions>
      </DialogFooter>
    </DialogWithHeader>
  )
}
