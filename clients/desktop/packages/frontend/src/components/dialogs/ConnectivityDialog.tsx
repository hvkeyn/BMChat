import React, { useEffect, useMemo, useState } from 'react'

import { debounceWithInit } from '../chat/ChatListHelpers'
import { BackendRemote, onDCEvent } from '../../backend-com'
import { selectedAccountId } from '../../ScreenController'
import Dialog, { DialogBody, DialogContent, DialogHeader } from '../Dialog'
import useTranslationFunction from '../../hooks/useTranslationFunction'

import type { DialogProps } from '../../contexts/DialogContext'
import { runtime } from '@deltachat-desktop/runtime-interface'
import {
  buildBmchatStatisticsHtml,
  buildMailProbeHtml,
  injectConnectivityStyles,
  sanitizeConnectivityHtml,
  wrapConnectivityDocument,
  type MailProbeResult,
} from '../../bmchat/connectivityStats'

const OverwrittenStyles =
  'font-family: Arial, Helvetica, sans-serif;font-variant-ligatures: none;'

export default function ConnectivityDialog({ onClose }: DialogProps) {
  const tx = useTranslationFunction()

  return (
    <Dialog onClose={onClose} canOutsideClickClose={true}>
      <DialogHeader title={tx('connectivity')} onClose={onClose} />
      <ConnectivityDialogInner />
    </Dialog>
  )
}

function ConnectivityDialogInner() {
  const tx = useTranslationFunction()
  const accountId = selectedAccountId()
  const [connectivityHTML, setConnectivityHTML] = useState('')
  const [mailProbeBusy, setMailProbeBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [statsLoading, setStatsLoading] = useState(false)

  const style = window.getComputedStyle(document.body)
  const bgColor = style.getPropertyValue('--bgPrimary')
  const textColor = style.getPropertyValue('--textPrimary')
  const stylesToInject = `background-color: ${bgColor}; color: ${textColor};`

  const canInjectStyles = runtime.getRuntimeInfo().target !== 'tauri'
  const isElectron = runtime.getRuntimeInfo().target === 'electron'

  const updateConnectivity = useMemo(
    () =>
      debounceWithInit(async (forceMailProbe = false) => {
        setLoading(true)
        try {
          let cHTML = await BackendRemote.rpc.getConnectivityHtml(accountId)
          cHTML = sanitizeConnectivityHtml(cHTML)
          if (canInjectStyles) {
            cHTML = injectConnectivityStyles(cHTML, `${stylesToInject}${OverwrittenStyles}`)
          } else {
            cHTML = injectConnectivityStyles(cHTML)
          }
          setConnectivityHTML(wrapConnectivityDocument(cHTML))
          setLoading(false)

          setStatsLoading(true)
          try {
            const withExtras = await appendConnectivityExtras(
              accountId,
              cHTML,
              isElectron,
              forceMailProbe,
              canInjectStyles ? stylesToInject : undefined
            )
            setConnectivityHTML(wrapConnectivityDocument(withExtras))
          } finally {
            setStatsLoading(false)
          }
        } catch (e) {
          const msg =
            e instanceof Error ? e.message : String(e ?? 'unknown error')
          setConnectivityHTML(
            wrapConnectivityDocument(
              `<p><b>${tx('error')}</b></p><p>${msg}</p>`,
              canInjectStyles ? stylesToInject : undefined
            )
          )
          setLoading(false)
          setStatsLoading(false)
        }
      }, 240),
    [accountId, canInjectStyles, stylesToInject, isElectron, tx]
  )

  useEffect(() => {
    updateConnectivity(false)
    return onDCEvent(accountId, 'ConnectivityChanged', () =>
      updateConnectivity(false)
    )
  }, [accountId, updateConnectivity])

  const runMailProbe = async () => {
    if (!isElectron) return
    setMailProbeBusy(true)
    try {
      await updateConnectivity(true)
    } finally {
      setMailProbeBusy(false)
    }
  }

  const iframeDoc =
    connectivityHTML ||
    wrapConnectivityDocument(
      `<p>${loading ? tx('connectivity_connecting') : '…'}</p>`,
      canInjectStyles ? stylesToInject : undefined
    )

  return (
    <DialogBody>
      <DialogContent>
        <iframe
          style={{
            border: 0,
            height: isElectron ? '560px' : '620px',
            width: '100%',
            maxHeight: '70vh',
            backgroundColor: bgColor,
            color: textColor,
          }}
          srcDoc={iframeDoc}
          sandbox={''}
          title={tx('connectivity')}
        />
        {statsLoading && (
          <p style={{ marginTop: 8, opacity: 0.75, fontSize: '0.9rem' }}>
            {tx('bmchat_connectivity_stats_loading')}
          </p>
        )}
        {isElectron && (
          <div style={{ marginTop: 12, display: 'flex', gap: 8 }}>
            <button
              type='button'
              className='delta-button-round'
              disabled={mailProbeBusy || loading}
              onClick={runMailProbe}
            >
              {mailProbeBusy
                ? tx('bmchat_connectivity_mail_probe_running')
                : tx('bmchat_connectivity_mail_probe')}
            </button>
          </div>
        )}
      </DialogContent>
    </DialogBody>
  )
}

async function appendConnectivityExtras(
  accountId: number,
  cHTML: string,
  isElectron: boolean,
  forceMailProbe: boolean,
  _stylesToInject?: string
): Promise<string> {
  const tx = window.static_translate

  const statsHtml = await buildBmchatStatisticsHtml(accountId, {
    title: tx('bmchat_connectivity_stats_title'),
    hint: tx('bmchat_connectivity_stats_hint'),
    contacts: tx('bmchat_stats_contacts'),
    contactsHint: tx('bmchat_stats_contacts_hint'),
    verified: tx('bmchat_stats_verified_contacts'),
    verifiedHint: tx('bmchat_stats_verified_contacts_hint'),
    chats: tx('bmchat_stats_chats'),
    chatsHint: tx('bmchat_stats_chats_hint'),
    groups: tx('bmchat_stats_groups'),
    groupsHint: tx('bmchat_stats_groups_hint'),
    messages: tx('bmchat_stats_messages'),
    messagesHint: tx('bmchat_stats_messages_hint'),
    attachments: tx('bmchat_stats_attachments'),
    attachmentsHint: tx('bmchat_stats_attachments_hint'),
    incoming: tx('bmchat_stats_incoming'),
    incomingHint: tx('bmchat_stats_incoming_hint'),
    outgoing: tx('bmchat_stats_outgoing'),
    outgoingHint: tx('bmchat_stats_outgoing_hint'),
    delivered: tx('bmchat_stats_delivered'),
    deliveredHint: tx('bmchat_stats_delivered_hint'),
    read: tx('bmchat_stats_read'),
    readHint: tx('bmchat_stats_read_hint'),
    failed: tx('bmchat_stats_failed'),
    failedHint: tx('bmchat_stats_failed_hint'),
    pending: tx('bmchat_stats_pending'),
    pendingHint: tx('bmchat_stats_pending_hint'),
    chatMix: tx('bmchat_stats_chat_mix'),
    chatMixValue: (
      direct,
      groups,
      channels,
      archive,
      pinned,
      requests,
      protectedChats
    ) =>
      tx('bmchat_stats_chat_mix_value', [
        direct,
        groups,
        channels,
        archive,
        pinned,
        requests,
        protectedChats,
      ]),
  })

  let mailProbeHtml = ''
  if (isElectron) {
    const shouldProbe = forceMailProbe || connectivityLooksDegraded(cHTML)
    if (shouldProbe) {
      try {
        const probe = (await runtime.bmchatBotsInvoke(
          'bmchat:mail-probe',
          accountId
        )) as MailProbeResult
        mailProbeHtml = buildMailProbeHtml(probe, {
          title: tx('bmchat_connectivity_mail_probe_title'),
          hint: tx('bmchat_connectivity_mail_probe_hint'),
          imapOk: (host, port) =>
            tx('bmchat_connectivity_mail_imap_ok', [host, String(port)]),
          imapFail: (host, port) =>
            tx('bmchat_connectivity_mail_imap_fail', [host, String(port)]),
          smtpOk: (host, port) =>
            tx('bmchat_connectivity_mail_smtp_ok', [host, String(port)]),
          smtpFail: (host, port) =>
            tx('bmchat_connectivity_mail_smtp_fail', [host, String(port)]),
          unavailable: tx('bmchat_connectivity_mail_probe_unavailable'),
        })
      } catch {
        /* ignore */
      }
    }
  }

  const tail = statsHtml + mailProbeHtml
  if (cHTML.includes('</body>')) {
    return cHTML.replace('</body>', tail + '</body>')
  }
  return cHTML + tail
}

/** When BM servers look down, also probe IMAP/SMTP (email transport). */
function connectivityLooksDegraded(html: string): boolean {
  const lower = html.toLowerCase()
  if (
    lower.includes('соединение...') ||
    lower.includes('connecting...') ||
    lower.includes('не в сети') ||
    lower.includes('offline') ||
    lower.includes('disconnected') ||
    lower.includes('ошибка') ||
    lower.includes('error') ||
    lower.includes('не поддерживается')
  ) {
    return true
  }
  const redDots =
    (html.match(/background-color:\s*#?f{2}0000|background:\s*red/gi) || [])
      .length +
    (html.match(/color:\s*#?f{2}0000/gi) || []).length
  return redDots > 0
}
