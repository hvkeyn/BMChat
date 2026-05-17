// BMChat desktop self-updater.
//
// The renderer process has DNS hard-disabled (`MAP * ^NOTFOUND` in
// `index.ts`), so the manifest fetch happens here, in the Node.js main
// process, against a literal IP that does not require DNS at all.
//
// Flow:
//   1. once per launch (after the main window is ready) check
//      `http://5.187.4.132/desktop-update.json`,
//   2. if it advertises a newer version that has an installer for the
//      current platform/architecture, show a native dialog,
//   3. on confirmation open the installer URL in the system browser
//      (electron-builder produces a re-runnable installer that swaps
//      the existing install in place — no need to re-implement the
//      installation step ourselves).
//
// Things kept deliberately out of scope:
//   * silent install — would need elevated privileges and is bad UX,
//   * delta updates — for a desktop install the installer is small,
//   * cryptographic signature verification — the installer is already
//     code-signed by the user's electron-builder cert chain, which the
//     OS verifies during install.

import { app, dialog, shell } from 'electron'
import * as http from 'http'

import { getLogger } from '@deltachat-desktop/shared/logger.js'
import { BuildInfo } from './get-build-info.js'

const log = getLogger('main/bmchat-updater')

const UPDATE_MANIFEST_URL = 'http://5.187.4.132/desktop-update.json'
const FOREGROUND_DELAY_MS = 10_000
const MIN_INTERVAL_MS = 12 * 60 * 60 * 1000 // 12 hours
const REQUEST_TIMEOUT_MS = 8_000
const MAX_BODY_BYTES = 64 * 1024

interface PlatformVariant {
  url: string
  sha256?: string
  size?: number
}

interface DesktopManifest {
  version: string
  notes?: string
  platforms: Record<string, PlatformVariant>
}

let lastCheckedAt = 0
let inFlight = false

export function scheduleUpdateCheck(): void {
  setTimeout(() => {
    runCheck().catch(err => log.warn('update check failed', err))
  }, FOREGROUND_DELAY_MS)
}

async function runCheck(): Promise<void> {
  if (inFlight) return
  if (Date.now() - lastCheckedAt < MIN_INTERVAL_MS) return
  inFlight = true
  try {
    lastCheckedAt = Date.now()
    const manifest = await fetchManifest()
    if (!manifest) return
    if (compareVersions(manifest.version, BuildInfo.VERSION) <= 0) {
      log.debug(`BMChat is up-to-date (local ${BuildInfo.VERSION}, remote ${manifest.version})`)
      return
    }
    const variant = pickVariantForCurrentPlatform(manifest)
    if (!variant) {
      log.debug(`no installer in manifest for ${process.platform}/${process.arch}`)
      return
    }
    await promptUser(manifest, variant)
  } finally {
    inFlight = false
  }
}

function fetchManifest(): Promise<DesktopManifest | null> {
  return new Promise(resolve => {
    let settled = false
    const finish = (value: DesktopManifest | null) => {
      if (settled) return
      settled = true
      resolve(value)
    }
    let req: http.ClientRequest | undefined
    try {
      req = http.get(
        UPDATE_MANIFEST_URL,
        {
          timeout: REQUEST_TIMEOUT_MS,
          headers: {
            Accept: 'application/json',
            'User-Agent': `BMChat-desktop/${BuildInfo.VERSION}`,
          },
        },
        res => {
          if (res.statusCode !== 200) {
            res.resume()
            finish(null)
            return
          }
          const chunks: Buffer[] = []
          let total = 0
          res.on('data', (c: Buffer) => {
            total += c.length
            if (total > MAX_BODY_BYTES) {
              res.destroy(new Error('manifest too large'))
              return
            }
            chunks.push(c)
          })
          res.on('end', () => {
            try {
              const text = Buffer.concat(chunks).toString('utf8')
              const obj = JSON.parse(text) as DesktopManifest
              if (typeof obj.version !== 'string' || !obj.platforms) {
                finish(null)
                return
              }
              finish(obj)
            } catch (err) {
              log.warn('manifest parse failed', err)
              finish(null)
            }
          })
          res.on('error', () => finish(null))
        }
      )
      req.on('timeout', () => {
        req?.destroy()
        finish(null)
      })
      req.on('error', () => finish(null))
    } catch (err) {
      log.warn('manifest request failed', err)
      finish(null)
    }
  })
}

function compareVersions(a: string, b: string): number {
  const tokensA = a.split(/[.\-+]/).map(s => parseInt(s, 10))
  const tokensB = b.split(/[.\-+]/).map(s => parseInt(s, 10))
  const len = Math.max(tokensA.length, tokensB.length)
  for (let i = 0; i < len; i++) {
    const ai = Number.isFinite(tokensA[i]) ? tokensA[i]! : 0
    const bi = Number.isFinite(tokensB[i]) ? tokensB[i]! : 0
    if (ai > bi) return 1
    if (ai < bi) return -1
  }
  return 0
}

function pickVariantForCurrentPlatform(
  m: DesktopManifest
): PlatformVariant | undefined {
  const { platform, arch } = process
  const candidates = [
    `${platform}-${arch}`,
    platform,
    platform === 'win32' ? `win-${arch}` : null,
    platform === 'win32' ? 'win' : null,
    platform === 'darwin' ? `mac-${arch}` : null,
    platform === 'darwin' ? 'mac' : null,
  ].filter(Boolean) as string[]
  for (const key of candidates) {
    const v = m.platforms[key]
    if (v && typeof v.url === 'string' && v.url.length > 0) return v
  }
  return undefined
}

async function promptUser(
  m: DesktopManifest,
  variant: PlatformVariant
): Promise<void> {
  if (!app.isReady()) await app.whenReady()
  const result = await dialog.showMessageBox({
    type: 'info',
    title: 'BMChat',
    message: `Доступна новая версия BMChat ${m.version}`,
    detail: m.notes && m.notes.length > 0
      ? `${m.notes}\n\nТекущая версия: ${BuildInfo.VERSION}`
      : `Текущая версия: ${BuildInfo.VERSION}`,
    buttons: ['Скачать', 'Позже'],
    defaultId: 0,
    cancelId: 1,
    noLink: true,
  })
  if (result.response === 0) {
    try {
      await shell.openExternal(variant.url)
    } catch (err) {
      log.warn('failed to open installer URL', err)
    }
  }
}
