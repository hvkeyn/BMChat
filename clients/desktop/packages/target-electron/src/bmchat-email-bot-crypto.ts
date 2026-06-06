import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'crypto'

import { getDCJsonrpcRemote } from './ipc.js'

export const ENC_PREFIX = 'BMCHAT-ENC1:'

async function resolveAddr(accountId: number): Promise<string> {
  try {
    return (
      (await getDCJsonrpcRemote().rpc.getConfig(accountId, 'configured_addr')) ||
      ''
    )
      .trim()
      .toLowerCase()
  } catch {
    return ''
  }
}

function deriveKeyV2(addr: string): Buffer {
  return createHash('sha256')
    .update(`bmchat-email-bot-v2|${addr}`)
    .digest()
}

function deriveKeyV1(accountId: number, addr: string): Buffer {
  return createHash('sha256')
    .update(`bmchat-email-bot-v1|${accountId}|${addr}`)
    .digest()
}

async function deriveKey(accountId: number): Promise<Buffer> {
  const addr = await resolveAddr(accountId)
  return deriveKeyV2(addr)
}

async function decryptWithKeys(
  stored: string,
  keys: Buffer[]
): Promise<string | null> {
  if (!stored.startsWith(ENC_PREFIX)) return stored
  try {
    const raw = Buffer.from(stored.slice(ENC_PREFIX.length), 'base64')
    if (raw.length < 13) return null
    const iv = raw.subarray(0, 12)
    const tag = raw.subarray(raw.length - 16)
    const ct = raw.subarray(12, raw.length - 16)
    for (const key of keys) {
      try {
        const decipher = createDecipheriv('aes-256-gcm', key, iv)
        decipher.setAuthTag(tag)
        return Buffer.concat([decipher.update(ct), decipher.final()]).toString(
          'utf8'
        )
      } catch {
        /* try next key */
      }
    }
  } catch {
    /* ignore */
  }
  return null
}

export async function encryptForAccount(
  accountId: number,
  plain: string
): Promise<string> {
  try {
    const key = await deriveKey(accountId)
    const iv = randomBytes(12)
    const cipher = createCipheriv('aes-256-gcm', key, iv)
    const ct = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()])
    const tag = cipher.getAuthTag()
    const out = Buffer.concat([iv, ct, tag])
    return ENC_PREFIX + out.toString('base64')
  } catch {
    return plain
  }
}

export async function decryptForAccount(
  accountId: number,
  stored: string
): Promise<string> {
  const addr = await resolveAddr(accountId)
  const plain = await decryptWithKeys(stored, [
    deriveKeyV2(addr),
    deriveKeyV1(accountId, addr),
  ])
  return plain ?? stored
}

export async function sealJson(
  accountId: number,
  json: string
): Promise<string> {
  return JSON.stringify({ v: 1, enc: await encryptForAccount(accountId, json) })
}

export async function openJson(
  accountId: number,
  raw: string | null | undefined
): Promise<string | null> {
  if (!raw) return null
  if (raw.startsWith(ENC_PREFIX)) {
    return decryptForAccount(accountId, raw)
  }
  try {
    const parsed = JSON.parse(raw)
    if (parsed?.enc && typeof parsed.enc === 'string') {
      return decryptForAccount(accountId, parsed.enc)
    }
    return raw
  } catch {
    return raw
  }
}
