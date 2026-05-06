import type { QrWithUrl } from '../../../backend/qr-types'

// Empty until BMChat owns a relay directory. Do not fall back to Delta infrastructure.
export const CHATMAIL_INSTANCES_LIST_URL = ''

// Hostname of the default chatmail instance
export const DEFAULT_CHATMAIL_HOSTNAME = 'chatmail.bmchat.example'

// URL to privacy policy of default BMChat Chatmail instance
export const DEFAULT_INSTANCE_PRIVACY_POLICY_URL = `https://${DEFAULT_CHATMAIL_HOSTNAME}/privacy.html`

export function isDefaultInstance(value: string): boolean {
  return value.includes(DEFAULT_CHATMAIL_HOSTNAME)
}

export function isQRWithDefaultInstance(qrWithUrl?: QrWithUrl): boolean {
  if (qrWithUrl && qrWithUrl.qr.kind === 'account') {
    return isDefaultInstance(qrWithUrl.qr.domain)
  }

  return true
}
