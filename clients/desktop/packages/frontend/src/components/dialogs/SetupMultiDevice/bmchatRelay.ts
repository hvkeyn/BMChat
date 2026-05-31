const RELAY_BASE_URL = 'http://5.187.4.132/mdrelay'
const RELAY_TTL_SECONDS = 30 * 60

export type RelaySession = {
  sid: string
  keyBase64Url: string
  qrPayload: string
  expiresAt: number
}

export type RelayStatus = {
  uploaded: boolean
  size: number
  code_hash: string | null
  expires_at: number
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = ''
  for (const b of bytes) binary += String.fromCharCode(b)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64Url(text: string): Uint8Array {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4)
  const binary = atob(padded)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
  return out
}

async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return [...new Uint8Array(digest)]
    .map(byte => byte.toString(16).padStart(2, '0'))
    .join('')
}

function assertRelayResponse(response: Response): void {
  if (!response.ok) {
    throw new Error(`relay HTTP ${response.status}`)
  }
}

export async function createRelaySession(): Promise<RelaySession> {
  const sidBytes = new Uint8Array(32)
  const keyBytes = new Uint8Array(32)
  crypto.getRandomValues(sidBytes)
  crypto.getRandomValues(keyBytes)

  const sid = toBase64Url(sidBytes)
  const keyBase64Url = toBase64Url(keyBytes)
  const response = await fetch(`${RELAY_BASE_URL}/session`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sid, expires_in: RELAY_TTL_SECONDS }),
  })
  assertRelayResponse(response)
  const body = await response.json()
  const expiresAt = Number(body.expires_at) || Math.floor(Date.now() / 1000) + RELAY_TTL_SECONDS

  const url = new URL('bmchat://second-device')
  url.searchParams.set('mode', 'relay')
  url.searchParams.set('sid', sid)
  url.searchParams.set('key', keyBase64Url)
  url.searchParams.set('relay', RELAY_BASE_URL)
  url.searchParams.set('v', '1')

  return {
    sid,
    keyBase64Url,
    qrPayload: url.toString(),
    expiresAt,
  }
}

export async function getRelayStatus(session: RelaySession): Promise<RelayStatus> {
  const response = await fetch(`${RELAY_BASE_URL}/session/${session.sid}/status`, {
    cache: 'no-store',
  })
  assertRelayResponse(response)
  const body = await response.json()
  return {
    uploaded: Boolean(body.uploaded),
    size: Number(body.size) || 0,
    code_hash: typeof body.code_hash === 'string' ? body.code_hash : null,
    expires_at: Number(body.expires_at) || session.expiresAt,
  }
}

export async function removeRelaySession(session: RelaySession): Promise<void> {
  try {
    await fetch(`${RELAY_BASE_URL}/session/${session.sid}`, { method: 'DELETE' })
  } catch {
    // Best-effort cleanup. The relay also has TTL garbage collection.
  }
}

export async function verifyRelayCode(
  session: RelaySession,
  code: string,
  codeHash: string
): Promise<boolean> {
  const normalized = code.replace(/\D/g, '')
  if (normalized.length !== 6) return false
  const actual = await sha256Hex(`${session.sid}:${session.keyBase64Url}:${normalized}`)
  return actual.toLowerCase() === codeHash.toLowerCase()
}

export async function downloadAndDecryptRelayBackup(session: RelaySession): Promise<Uint8Array> {
  const response = await fetch(`${RELAY_BASE_URL}/session/${session.sid}/blob`, {
    cache: 'no-store',
  })
  assertRelayResponse(response)
  const encrypted = new Uint8Array(await response.arrayBuffer())
  if (encrypted.length <= 12) throw new Error('relay payload is too small')

  const nonce = encrypted.slice(0, 12)
  const ciphertext = encrypted.slice(12)
  const key = await crypto.subtle.importKey(
    'raw',
    fromBase64Url(session.keyBase64Url),
    'AES-GCM',
    false,
    ['decrypt']
  )
  const decrypted = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce }, key, ciphertext)
  return new Uint8Array(decrypted)
}

export function bytesToBase64(bytes: Uint8Array): string {
  const chunkSize = 0x8000
  let binary = ''
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.slice(i, i + chunkSize))
  }
  return btoa(binary)
}
