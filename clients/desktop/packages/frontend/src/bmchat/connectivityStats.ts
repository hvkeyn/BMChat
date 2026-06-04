import { C } from '@deltachat/jsonrpc-client'

import { BackendRemote } from '../backend-com'

interface Stats {
  contacts: number
  verifiedContacts: number
  chats: number
  singleChats: number
  groups: number
  channels: number
  mailingLists: number
  archivedChats: number
  pinnedChats: number
  mutedChats: number
  contactRequests: number
  encryptedChats: number
  messages: number
  incoming: number
  outgoing: number
  delivered: number
  read: number
  failed: number
  pending: number
  attachments: number
}

function fmt(n: number): string {
  return n.toLocaleString()
}

function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

async function countRealContacts(
  accountId: number,
  flags: number
): Promise<number> {
  const ids = await BackendRemote.rpc.getContacts(accountId, flags, null)
  return ids.filter(id => id > C.DC_CONTACT_ID_LAST_SPECIAL).length
}

async function collectChatStats(
  accountId: number,
  flag: number,
  stats: Stats,
  seen: Set<number>
): Promise<void> {
  const entries = await BackendRemote.rpc.getChatlistEntries(
    accountId,
    flag,
    null,
    null
  )
  for (const entry of entries) {
    const chatId = entry.id
    if (chatId <= C.DC_CHAT_ID_LAST_SPECIAL || seen.has(chatId)) continue
    seen.add(chatId)
    const chat = await BackendRemote.rpc.getBasicChatInfo(accountId, chatId)
    stats.chats++
    if (chat.isEncrypted) stats.encryptedChats++
    if (chat.isMuted) stats.mutedChats++
    if (chat.isContactRequest) stats.contactRequests++
    if (chat.visibility === C.DC_CHAT_VISIBILITY_PINNED) stats.pinnedChats++
    if (chat.visibility === C.DC_CHAT_VISIBILITY_ARCHIVED) {
      stats.archivedChats++
    }
    switch (chat.chatType) {
      case 'Group':
        stats.groups++
        break
      case 'Mailinglist':
        stats.mailingLists++
        break
      case 'Broadcast':
        stats.channels++
        break
      default:
        stats.singleChats++
    }
    const listItems = await BackendRemote.rpc.getMessageListItems(
      accountId,
      chatId,
      false,
      false
    )
    for (const item of listItems) {
      if (item.kind !== 'message') continue
      const msgId = item.msg_id
      if (msgId <= C.DC_MSG_ID_DAYMARKER) continue
      const msg = await BackendRemote.rpc.getMessage(accountId, msgId)
      if (!msg) continue
      if (msg.isInfo) continue
      stats.messages++
      if (msg.fromId === C.DC_CONTACT_ID_SELF) {
        stats.outgoing++
      } else {
        stats.incoming++
      }
      if (msg.file) stats.attachments++
      switch (msg.state) {
        case 'OutDelivered':
          stats.delivered++
          break
        case 'OutMdnRcvd':
          stats.read++
          break
        case 'OutFailed':
          stats.failed++
          break
        case 'OutPending':
        case 'OutPreparing':
        case 'OutDraft':
          stats.pending++
          break
      }
    }
  }
}

export async function buildBmchatStatisticsHtml(
  accountId: number,
  labels: {
    title: string
    hint: string
    contacts: string
    contactsHint: string
    verified: string
    verifiedHint: string
    chats: string
    chatsHint: string
    groups: string
    groupsHint: string
    messages: string
    messagesHint: string
    attachments: string
    attachmentsHint: string
    incoming: string
    incomingHint: string
    outgoing: string
    outgoingHint: string
    delivered: string
    deliveredHint: string
    read: string
    readHint: string
    failed: string
    failedHint: string
    pending: string
    pendingHint: string
    chatMix: string
    chatMixValue: (
      direct: string,
      groups: string,
      channels: string,
      archive: string,
      pinned: string,
      requests: string,
      protectedChats: string
    ) => string
  }
): Promise<string> {
  const stats: Stats = {
    contacts: 0,
    verifiedContacts: 0,
    chats: 0,
    singleChats: 0,
    groups: 0,
    channels: 0,
    mailingLists: 0,
    archivedChats: 0,
    pinnedChats: 0,
    mutedChats: 0,
    contactRequests: 0,
    encryptedChats: 0,
    messages: 0,
    incoming: 0,
    outgoing: 0,
    delivered: 0,
    read: 0,
    failed: 0,
    pending: 0,
    attachments: 0,
  }
  const seen = new Set<number>()
  await collectChatStats(accountId, C.DC_GCL_NO_SPECIALS, stats, seen)
  await collectChatStats(
    accountId,
    C.DC_GCL_ARCHIVED_ONLY | C.DC_GCL_NO_SPECIALS,
    stats,
    seen
  )
  stats.contacts = await countRealContacts(accountId, 0)
  stats.verifiedContacts = await countRealContacts(
    accountId,
    C.DC_GCL_VERIFIED_ONLY
  )

  const stat = (title: string, value: number, hint: string) =>
    `<div class="bmchat-stat"><b>${esc(title)}</b><strong>${fmt(value)}</strong><span>${esc(hint)}</span></div>`

  const mix = labels.chatMixValue(
    fmt(stats.singleChats),
    fmt(stats.groups),
    fmt(stats.channels),
    fmt(stats.archivedChats),
    fmt(stats.pinnedChats),
    fmt(stats.contactRequests),
    fmt(stats.encryptedChats)
  )

  return (
    `<section class="bmchat-stats"><h3>${esc(labels.title)}</h3>` +
    `<p>${esc(labels.hint)}</p><div class="bmchat-stats-grid">` +
    stat(labels.contacts, stats.contacts, labels.contactsHint) +
    stat(labels.verified, stats.verifiedContacts, labels.verifiedHint) +
    stat(labels.chats, stats.chats, labels.chatsHint) +
    stat(labels.groups, stats.groups, labels.groupsHint) +
    stat(labels.messages, stats.messages, labels.messagesHint) +
    stat(labels.attachments, stats.attachments, labels.attachmentsHint) +
    stat(labels.incoming, stats.incoming, labels.incomingHint) +
    stat(labels.outgoing, stats.outgoing, labels.outgoingHint) +
    stat(labels.delivered, stats.delivered, labels.deliveredHint) +
    stat(labels.read, stats.read, labels.readHint) +
    stat(labels.failed, stats.failed, labels.failedHint) +
    stat(labels.pending, stats.pending, labels.pendingHint) +
    `<div class="bmchat-stat bmchat-stat-wide"><b>${esc(labels.chatMix)}</b><span>${esc(mix)}</span></div>` +
    `</div></section>`
  )
}

/** Strip misleading IMAP-only provider line (ported from Android). */
export function sanitizeConnectivityHtml(html: string): string {
  return html
    .replace(
      /<[^>]*>\s*Не поддерживается вашим провайдером\.?\s*<\/[^>]*>/gis,
      ''
    )
    .replace(
      /<[^>]*>\s*Not supported by your provider\.?\s*<\/[^>]*>/gis,
      ''
    )
    .replace(/<h3[^>]*>[^<]*<\/h3>\s*(?=<h3|\/body)/gis, '')
}

export interface MailProbeResult {
  ok: boolean
  imap: boolean
  smtp: boolean
  imapHost?: string
  smtpHost?: string
  imapPort?: number
  smtpPort?: number
}

export function buildMailProbeHtml(
  probe: MailProbeResult,
  labels: {
    title: string
    hint: string
    imapOk: (host: string, port: number) => string
    imapFail: (host: string, port: number) => string
    smtpOk: (host: string, port: number) => string
    smtpFail: (host: string, port: number) => string
    unavailable: string
  }
): string {
  if (!probe.ok) {
    return (
      `<section class="bmchat-mail-probe"><h3>${esc(labels.title)}</h3>` +
      `<p>${esc(labels.unavailable)}</p></section>`
    )
  }
  const imapLine = probe.imap
    ? labels.imapOk(probe.imapHost || '', probe.imapPort || 993)
    : labels.imapFail(probe.imapHost || '', probe.imapPort || 993)
  const smtpLine = probe.smtp
    ? labels.smtpOk(probe.smtpHost || '', probe.smtpPort || 465)
    : labels.smtpFail(probe.smtpHost || '', probe.smtpPort || 465)
  const dot = (ok: boolean) => (ok ? 'ok' : 'fail')
  return (
    `<section class="bmchat-mail-probe"><h3>${esc(labels.title)}</h3>` +
    `<p>${esc(labels.hint)}</p>` +
    `<p class="bmchat-probe-line ${dot(probe.imap)}">${esc(imapLine)}</p>` +
    `<p class="bmchat-probe-line ${dot(probe.smtp)}">${esc(smtpLine)}</p></section>`
  )
}

export const BMCHAT_MAIL_PROBE_CSS =
  ' .bmchat-mail-probe{margin-top:1.5rem;padding-top:.7rem;border-top:1px solid rgba(128,128,128,.35)}' +
  ' .bmchat-probe-line.ok::before{content:"● ";color:#2e7d32}' +
  ' .bmchat-probe-line.fail::before{content:"● ";color:#c62828}'

export const BMCHAT_STATS_CSS =
  ' .bmchat-stats{margin-top:2rem;padding-top:.7rem;border-top:1px solid rgba(128,128,128,.35)}' +
  ' .bmchat-stats-grid{display:grid;grid-template-columns:1fr 1fr;gap:.7rem;margin-top:.8rem}' +
  ' .bmchat-stat{border:1px solid rgba(128,128,128,.35);border-radius:10px;padding:.75rem;background:rgba(128,128,128,.08)}' +
  ' .bmchat-stat b{display:block;font-size:.95rem;margin-bottom:.25rem}' +
  ' .bmchat-stat strong{display:block;font-size:1.45rem;line-height:1.2}' +
  ' .bmchat-stat span{display:block;margin-top:.2rem;opacity:.75;font-size:.9rem}' +
  ' .bmchat-stat-wide{grid-column:1/-1}'
