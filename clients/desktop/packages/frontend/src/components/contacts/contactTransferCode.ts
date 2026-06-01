// BMChat "contact transfer code" — desktop port of the Android
// `ContactTransferCode` class so codes are interchangeable between platforms.
//
// A contact transfer code lets a user hand over a contact (an e-mail address
// plus an optional display name) via a short, copy-pasteable string instead of
// a plaintext address. The payload is encrypted with AES-GCM so the e-mail is
// not visible in the code itself. Every BMChat build shares the same static
// obfuscation key, so any BMChat client (Android or desktop) can decode a code
// produced by another one.
//
// This is a product-level convenience on top of `createContact` — it is NOT a
// SecureJoin invite and does not transfer encryption keys. For full verified
// contact exchange the invite link / QR flow should still be used.
//
// Format: `BMCC1:<base64url(iv[12] || ciphertext+tag)>`

export const CONTACT_CODE_PREFIX = 'BMCC1:'

// Static obfuscation key shared by all BMChat builds — intentionally not a
// secret. Must match `ContactTransferCode.KEY` in the Android client byte for
// byte ("BMChat-contact!_").
const KEY_BYTES = new Uint8Array([
  0x42, 0x4d, 0x43, 0x68, 0x61, 0x74, 0x2d, 0x63, 0x6f, 0x6e, 0x74, 0x61, 0x63,
  0x74, 0x21, 0x5f,
])

const IV_LEN = 12
const TAG_BITS = 128

export interface DecodedContactCode {
  addr: string
  name: string
}

export function looksLikeContactCode(input: string | null | undefined): boolean {
  return !!input && input.trim().startsWith(CONTACT_CODE_PREFIX)
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

function base64UrlDecode(input: string): Uint8Array {
  let b64 = input.replace(/-/g, '+').replace(/_/g, '/')
  while (b64.length % 4 !== 0) {
    b64 += '='
  }
  const binary = atob(b64)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i)
  }
  return out
}

async function importKey(): Promise<CryptoKey> {
  return window.crypto.subtle.importKey('raw', KEY_BYTES, 'AES-GCM', false, [
    'encrypt',
    'decrypt',
  ])
}

/** Builds a transfer code for the given address and (optional) display name. */
export async function encodeContactCode(
  addr: string,
  name?: string | null
): Promise<string | null> {
  if (!addr) {
    return null
  }
  try {
    const payload = `${addr}\n${name ?? ''}`
    const plain = new TextEncoder().encode(payload)
    const iv = new Uint8Array(IV_LEN)
    window.crypto.getRandomValues(iv)
    const key = await importKey()
    const ctBuffer = await window.crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, tagLength: TAG_BITS },
      key,
      plain
    )
    const ct = new Uint8Array(ctBuffer)
    const out = new Uint8Array(iv.length + ct.length)
    out.set(iv, 0)
    out.set(ct, iv.length)
    return CONTACT_CODE_PREFIX + base64UrlEncode(out)
  } catch {
    return null
  }
}

/** Decodes a transfer code; returns null if it is not a valid BMChat code. */
export async function decodeContactCode(
  input: string | null | undefined
): Promise<DecodedContactCode | null> {
  if (!looksLikeContactCode(input)) {
    return null
  }
  try {
    const body = input!.trim().slice(CONTACT_CODE_PREFIX.length)
    const raw = base64UrlDecode(body)
    if (raw.length <= IV_LEN) {
      return null
    }
    const iv = raw.slice(0, IV_LEN)
    const ct = raw.slice(IV_LEN)
    const key = await importKey()
    const plainBuffer = await window.crypto.subtle.decrypt(
      { name: 'AES-GCM', iv, tagLength: TAG_BITS },
      key,
      ct
    )
    const payload = new TextDecoder().decode(plainBuffer)
    const nl = payload.indexOf('\n')
    const addr = nl >= 0 ? payload.slice(0, nl) : payload
    const name = nl >= 0 ? payload.slice(nl + 1) : ''
    if (!addr) {
      return null
    }
    return { addr, name }
  } catch {
    return null
  }
}
