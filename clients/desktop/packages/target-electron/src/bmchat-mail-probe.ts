import { ipcMain } from 'electron'
import * as dns from 'dns/promises'
import * as net from 'net'
import * as tls from 'tls'

import { getDCJsonrpcRemote, DCJsonrpcRemoteInitializedP } from './ipc.js'
import { getLogger } from '../../shared/logger.js'

const log = getLogger('main/bmchat-mail-probe')

function normalizeHost(raw: string | null | undefined): string {
  return (raw || '').trim().replace(/^[\s.]+|[\s.]+$/g, '')
}

function normalizePort(raw: string | null | undefined, fallback: number): number {
  const n = parseInt((raw || '').trim(), 10)
  if (!Number.isFinite(n) || n < 1 || n > 65535) return fallback
  return n
}

function tcpReachable(
  host: string,
  port: number,
  timeoutMs = 12_000
): Promise<boolean> {
  return new Promise(resolve => {
    const socket = new net.Socket()
    let settled = false
    const finish = (ok: boolean) => {
      if (settled) return
      settled = true
      try {
        socket.destroy()
      } catch {
        /* ignore */
      }
      resolve(ok)
    }
    socket.setTimeout(timeoutMs)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
    socket.connect(port, host)
  })
}

function tlsReachable(
  connectHost: string,
  port: number,
  servername: string,
  timeoutMs = 12_000
): Promise<boolean> {
  return new Promise(resolve => {
    let settled = false
    const finish = (ok: boolean) => {
      if (settled) return
      settled = true
      try {
        socket.destroy()
      } catch {
        /* ignore */
      }
      resolve(ok)
    }
    const socket = tls.connect({
      host: connectHost,
      port,
      servername,
      rejectUnauthorized: false,
      timeout: timeoutMs,
    })
    socket.once('secureConnect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
  })
}

async function resolveIpv4(host: string): Promise<string | null> {
  const h = normalizeHost(host)
  if (!h) return null
  if (net.isIP(h)) return h
  try {
    const res = await dns.lookup(h, { family: 4 })
    return res.address
  } catch {
    return null
  }
}

async function probeEndpoint(
  host: string | null | undefined,
  port: number
): Promise<boolean> {
  const hostname = normalizeHost(host)
  if (!hostname) return false
  const ip = await resolveIpv4(hostname)
  if (!ip) return false
  const tlsPorts = new Set([465, 587, 993, 995])
  if (tlsPorts.has(port)) {
    if (await tlsReachable(ip, port, hostname)) return true
    // Some providers accept plain TCP on TLS ports before handshake.
    return tcpReachable(ip, port)
  }
  return tcpReachable(ip, port)
}

export function registerMailProbeIpc(): void {
  ipcMain.handle('bmchat:mail-probe', async (_e, accountId: number) => {
    try {
      await DCJsonrpcRemoteInitializedP
      const rpc = getDCJsonrpcRemote().rpc
      const imapHost = normalizeHost(await rpc.getConfig(accountId, 'mail_server'))
      const imapPort = normalizePort(await rpc.getConfig(accountId, 'mail_port'), 993)
      const smtpHost = normalizeHost(await rpc.getConfig(accountId, 'send_server'))
      const smtpPort = normalizePort(await rpc.getConfig(accountId, 'send_port'), 465)
      const [imap, smtp] = await Promise.all([
        probeEndpoint(imapHost, imapPort),
        probeEndpoint(smtpHost, smtpPort),
      ])
      return {
        ok: true,
        imap,
        smtp,
        imapHost: imapHost || '',
        smtpHost: smtpHost || '',
        imapPort,
        smtpPort,
      }
    } catch (e) {
      log.warn('mail-probe failed', e)
      return { ok: false, imap: false, smtp: false }
    }
  })
}
