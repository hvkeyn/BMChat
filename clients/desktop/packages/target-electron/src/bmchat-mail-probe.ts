import { ipcMain } from 'electron'
import * as net from 'net'

import { getDCJsonrpcRemote, DCJsonrpcRemoteInitializedP } from './ipc.js'
import { getLogger } from '../../shared/logger.js'

const log = getLogger('main/bmchat-mail-probe')

function tcpReachable(
  host: string | null | undefined,
  port: number,
  timeoutMs = 8000
): Promise<boolean> {
  return new Promise(resolve => {
    const h = (host || '').trim()
    if (!h || !port || port < 1 || port > 65535) {
      resolve(false)
      return
    }
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
    socket.connect(port, h)
  })
}

export function registerMailProbeIpc(): void {
  ipcMain.handle('bmchat:mail-probe', async (_e, accountId: number) => {
    try {
      await DCJsonrpcRemoteInitializedP
      const rpc = getDCJsonrpcRemote().rpc
      const imapHost = await rpc.getConfig(accountId, 'mail_server')
      const imapPort = parseInt(
        (await rpc.getConfig(accountId, 'mail_port')) || '993',
        10
      )
      const smtpHost = await rpc.getConfig(accountId, 'send_server')
      const smtpPort = parseInt(
        (await rpc.getConfig(accountId, 'send_port')) || '465',
        10
      )
      const [imap, smtp] = await Promise.all([
        tcpReachable(imapHost, imapPort),
        tcpReachable(smtpHost, smtpPort),
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
