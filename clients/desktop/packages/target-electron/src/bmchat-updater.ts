// BMChat desktop self-updater.
//
// The renderer process has DNS hard-disabled (`MAP * ^NOTFOUND` in
// `index.ts`), so the manifest fetch happens here, in the Node.js main
// process, against a literal IP that does not require DNS at all.
//
// Flow (2.49.97 onwards):
//   1. once per launch (after the main window is ready) check
//      `http://5.187.4.132/desktop-update.json`,
//   2. if it advertises a newer version that has an installer for the
//      current platform/architecture, show a native dialog with three
//      options: «Установить сейчас», «Открыть страницу скачивания»,
//      «Позже»,
//   3. on «Установить сейчас» — download the installer to a temp
//      file with a determinate progress dialog, validate SHA-256 and
//      then spawn the installer detached from the app while quitting
//      the running BMChat instance so the installer can replace files
//      cleanly,
//   4. on «Открыть страницу» — fall back to the previous behaviour
//      (`shell.openExternal`) for users who'd rather download via
//      browser.
//
// Things kept deliberately out of scope:
//   * silent install — would need elevated privileges and is bad UX,
//   * delta updates — for a desktop install the installer is small,
//   * cryptographic signature verification — the installer is already
//     code-signed by the user's electron-builder cert chain, which the
//     OS verifies during install.

import { app, BrowserWindow, dialog, ipcMain, shell } from 'electron'
import * as crypto from 'crypto'
import * as fs from 'fs'
import * as http from 'http'
import * as path from 'path'
import { spawn } from 'child_process'

import { getLogger } from '@deltachat-desktop/shared/logger.js'
import { BuildInfo } from './get-build-info.js'

const log = getLogger('main/bmchat-updater')

const UPDATE_MANIFEST_URL = 'http://5.187.4.132/desktop-update.json'
const FOREGROUND_DELAY_MS = 10_000
const MIN_INTERVAL_MS = 12 * 60 * 60 * 1000 // 12 hours
const REQUEST_TIMEOUT_MS = 8_000
const DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000 // 10 min hard cap on installer fetch
const MAX_BODY_BYTES = 64 * 1024
const MAX_INSTALLER_BYTES = 512 * 1024 * 1024 // sanity cap

interface PlatformVariant {
  url: string
  sha256?: string
  size?: number
  versionedFile?: string
}

interface DesktopManifest {
  version: string
  notes?: string
  platforms: Record<string, PlatformVariant>
}

let lastCheckedAt = 0
let inFlight = false
let downloadInProgress = false

export function scheduleUpdateCheck(): void {
  setTimeout(() => {
    runCheck().catch(err => log.warn('update check failed', err))
  }, FOREGROUND_DELAY_MS)
}

/**
 * Wire the manual "Check for updates" button (Settings → Advanced).
 * The renderer calls `runtime.bmchatCheckForUpdates()` which invokes this.
 */
export function registerUpdaterIpc(): void {
  ipcMain.handle('bmchat:check-for-updates', async () => {
    await checkForUpdatesNow()
  })
}

/**
 * Manual update check triggered from the UI. Unlike `runCheck`, it ignores
 * the 12h debounce and always gives the user explicit feedback: a native
 * dialog telling them either that an update is available (reusing the normal
 * download/install flow) or that they are already on the latest version.
 */
async function checkForUpdatesNow(): Promise<void> {
  if (inFlight) return
  inFlight = true
  try {
    lastCheckedAt = Date.now()
    const manifest = await fetchManifest()
    if (!app.isReady()) await app.whenReady()
    if (!manifest) {
      await dialog.showMessageBox({
        type: 'warning',
        title: 'BMChat',
        message: 'Не удалось проверить обновления',
        detail:
          'Сервер обновлений недоступен. Проверьте подключение к интернету и попробуйте позже.',
        buttons: ['ОК'],
        noLink: true,
      })
      return
    }
    if (compareVersions(manifest.version, BuildInfo.VERSION) <= 0) {
      await dialog.showMessageBox({
        type: 'info',
        title: 'BMChat',
        message: 'У вас установлена последняя версия',
        detail: `Текущая версия: ${BuildInfo.VERSION}`,
        buttons: ['ОК'],
        noLink: true,
      })
      return
    }
    const variant = pickVariantForCurrentPlatform(manifest)
    if (!variant) {
      await dialog.showMessageBox({
        type: 'info',
        title: 'BMChat',
        message: `Доступна новая версия BMChat ${manifest.version}`,
        detail: `Для вашей платформы пока нет готового установщика. Откройте сайт BMChat, чтобы скачать вручную.\n\nТекущая версия: ${BuildInfo.VERSION}`,
        buttons: ['ОК'],
        noLink: true,
      })
      return
    }
    await promptUser(manifest, variant)
  } catch (err) {
    log.warn('manual update check failed', err)
  } finally {
    inFlight = false
  }
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
      log.debug(
        `BMChat is up-to-date (local ${BuildInfo.VERSION}, remote ${manifest.version})`
      )
      return
    }
    const variant = pickVariantForCurrentPlatform(manifest)
    if (!variant) {
      log.debug(
        `no installer in manifest for ${process.platform}/${process.arch}`
      )
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
  const candidates: string[] = []
  if (platform === 'win32') {
    candidates.push(
      `win-${arch}-installer`,
      `win-${arch}`,
      'win-x64-installer',
      `${platform}-${arch}`,
      platform
    )
  } else if (platform === 'darwin') {
    candidates.push(
      `mac-${arch}`,
      'mac-x64',
      'mac',
      `${platform}-${arch}`,
      platform
    )
  } else {
    // linux: prefer .deb on Debian/Ubuntu, AppImage otherwise
    const debian = fs.existsSync('/etc/debian_version')
    if (debian) {
      candidates.push(
        `linux-${arch}-deb`,
        'linux-x64-deb',
        `linux-${arch}-appimage`,
        'linux-x64-appimage'
      )
    } else {
      candidates.push(
        `linux-${arch}-appimage`,
        'linux-x64-appimage',
        `linux-${arch}-deb`,
        'linux-x64-deb'
      )
    }
    candidates.push(`${platform}-${arch}`, platform)
  }
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
  // Default behaviour: offer in-app download. Linux AppImage isn't a
  // self-installing executable, so for that variant we keep the legacy
  // "open in browser" path as the primary action.
  const isAppImage = /AppImage/i.test(variant.url)
  const buttons = isAppImage
    ? ['Открыть страницу скачивания', 'Позже']
    : ['Установить сейчас', 'Открыть страницу скачивания', 'Позже']
  const cancelId = buttons.length - 1

  const result = await dialog.showMessageBox({
    type: 'info',
    title: 'BMChat',
    message: `Доступна новая версия BMChat ${m.version}`,
    detail:
      m.notes && m.notes.length > 0
        ? `${m.notes}\n\nТекущая версия: ${BuildInfo.VERSION}`
        : `Текущая версия: ${BuildInfo.VERSION}`,
    buttons,
    defaultId: 0,
    cancelId,
    noLink: true,
  })

  if (result.response === cancelId) return

  if (isAppImage) {
    // AppImage path → open in browser, user replaces the file manually.
    if (result.response === 0) {
      try {
        await shell.openExternal(variant.url)
      } catch (err) {
        log.warn('failed to open installer URL', err)
      }
    }
    return
  }

  if (result.response === 0) {
    try {
      await downloadAndInstall(m, variant)
    } catch (err) {
      log.warn('in-app installer flow failed', err)
      const fallback = await dialog.showMessageBox({
        type: 'warning',
        title: 'BMChat',
        message: 'Не удалось скачать обновление',
        detail: `${(err as Error)?.message || err}\n\nОткрыть страницу скачивания в браузере?`,
        buttons: ['Открыть в браузере', 'Закрыть'],
        defaultId: 0,
        cancelId: 1,
        noLink: true,
      })
      if (fallback.response === 0) {
        try {
          await shell.openExternal(variant.url)
        } catch (e) {
          log.warn('failed to open installer URL', e)
        }
      }
    }
  } else if (result.response === 1) {
    try {
      await shell.openExternal(variant.url)
    } catch (err) {
      log.warn('failed to open installer URL', err)
    }
  }
}

async function downloadAndInstall(
  m: DesktopManifest,
  variant: PlatformVariant
): Promise<void> {
  if (downloadInProgress) {
    log.warn('download already in progress, ignoring duplicate trigger')
    return
  }
  downloadInProgress = true
  let progressWindow: BrowserWindow | null = null
  let dest: string | null = null
  try {
    const tmpDir = app.getPath('temp')
    const fileName =
      variant.versionedFile ||
      path.basename(new URL(variant.url).pathname) ||
      `BMChat-${m.version}-Setup.exe`
    dest = path.join(tmpDir, fileName)

    progressWindow = createProgressWindow(m.version)

    await downloadWithProgress(variant, dest, progressWindow)

    // SHA-256 verification (best effort: only if manifest provided one).
    if (variant.sha256) {
      updateProgress(progressWindow, {
        statusText: 'Проверяем подпись файла…',
      })
      const computed = await sha256OfFile(dest)
      if (computed.toLowerCase() !== variant.sha256.toLowerCase()) {
        throw new Error(
          `SHA-256 mismatch: expected ${variant.sha256}, got ${computed}`
        )
      }
    }

    updateProgress(progressWindow, {
      statusText: 'Запускаем установщик…',
      percent: 100,
    })

    await launchInstaller(dest)

    // Close window and quit so the installer can replace files. NSIS
    // opens a new GUI of its own; the user will re-launch BMChat from
    // the installer's "Run BMChat" checkbox at the end.
    setTimeout(() => {
      try {
        progressWindow?.close()
      } catch {}
      app.quit()
    }, 800)
  } catch (err) {
    if (dest) {
      try {
        fs.unlinkSync(dest)
      } catch {}
    }
    try {
      progressWindow?.close()
    } catch {}
    throw err
  } finally {
    downloadInProgress = false
  }
}

interface ProgressUpdate {
  receivedBytes?: number
  totalBytes?: number
  percent?: number
  statusText?: string
}

function createProgressWindow(version: string): BrowserWindow {
  const win = new BrowserWindow({
    width: 460,
    height: 200,
    resizable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    title: `BMChat ${version}`,
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  })
  // Strip the menu so the dialog feels like a system-level update prompt.
  win.setMenuBarVisibility(false)
  const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><title>BMChat — обновление</title>
<style>
  body { margin:0; padding:24px 22px; font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; background:#fff7f8; color:#1f050b; }
  h2 { font-size:15px; margin:0 0 10px; font-weight:600; }
  .status { font-size:12.5px; color:#5a3a44; margin:0 0 14px; min-height:18px; }
  progress { width:100%; height:18px; }
  .meta { font-size:11.5px; color:#7a4f5b; margin-top:10px; font-variant-numeric: tabular-nums; }
</style></head><body>
  <h2>BMChat ${escapeHtml(version)}</h2>
  <p class="status" id="status">Подготовка скачивания…</p>
  <progress id="bar" max="100" value="0"></progress>
  <p class="meta" id="meta"></p>
<script>
  window.addEventListener('message', e => {
    const d = e.data || {};
    if (typeof d.statusText === 'string')
      document.getElementById('status').textContent = d.statusText;
    if (typeof d.percent === 'number') {
      const v = Math.max(0, Math.min(100, d.percent));
      document.getElementById('bar').value = v;
    }
    if (typeof d.receivedBytes === 'number' && typeof d.totalBytes === 'number') {
      const r = (d.receivedBytes / 1024 / 1024).toFixed(1);
      const t = (d.totalBytes / 1024 / 1024).toFixed(1);
      document.getElementById('meta').textContent = r + ' / ' + t + ' MB';
    }
  });
</script></body></html>`
  win.loadURL(
    'data:text/html;charset=utf-8,' + encodeURIComponent(html)
  )
  return win
}

function updateProgress(
  win: BrowserWindow | null,
  data: ProgressUpdate
): void {
  if (!win || win.isDestroyed()) return
  try {
    win.webContents.executeJavaScript(
      `window.postMessage(${JSON.stringify(data)}, '*')`,
      true
    ).catch(() => {})
  } catch {
    // window may have been closed mid-flight
  }
}

function downloadWithProgress(
  variant: PlatformVariant,
  dest: string,
  progressWindow: BrowserWindow
): Promise<void> {
  return new Promise((resolve, reject) => {
    let req: http.ClientRequest | undefined
    let stream: fs.WriteStream | undefined
    let received = 0
    let total = variant.size || 0
    let done = false

    const finish = (err?: Error) => {
      if (done) return
      done = true
      try {
        stream?.close()
      } catch {}
      if (err) reject(err)
      else resolve()
    }

    try {
      stream = fs.createWriteStream(dest)
    } catch (err) {
      reject(err as Error)
      return
    }

    updateProgress(progressWindow, {
      statusText: 'Скачиваем установщик…',
      percent: 0,
    })

    const doRequest = (url: string, redirectsLeft: number) => {
      try {
        req = http.get(
          url,
          {
            timeout: REQUEST_TIMEOUT_MS,
            headers: {
              'User-Agent': `BMChat-desktop/${BuildInfo.VERSION}`,
            },
          },
          res => {
            if (
              redirectsLeft > 0 &&
              res.statusCode &&
              res.statusCode >= 300 &&
              res.statusCode < 400 &&
              res.headers.location
            ) {
              res.resume()
              const next = new URL(res.headers.location, url).toString()
              doRequest(next, redirectsLeft - 1)
              return
            }
            if (res.statusCode !== 200) {
              res.resume()
              finish(new Error(`HTTP ${res.statusCode}`))
              return
            }
            const cl = parseInt(res.headers['content-length'] || '', 10)
            if (Number.isFinite(cl) && cl > 0) total = cl
            res.on('data', (chunk: Buffer) => {
              received += chunk.length
              if (received > MAX_INSTALLER_BYTES) {
                res.destroy(new Error('installer too large'))
                return
              }
              if (stream) stream.write(chunk)
              const percent =
                total > 0 ? Math.floor((received / total) * 100) : 0
              updateProgress(progressWindow, {
                receivedBytes: received,
                totalBytes: total,
                percent,
              })
            })
            res.on('end', () => {
              try {
                stream?.end()
              } catch {}
              finish()
            })
            res.on('error', finish)
          }
        )
        req.setTimeout(DOWNLOAD_TIMEOUT_MS, () => {
          req?.destroy()
          finish(new Error('download timeout'))
        })
        req.on('error', finish)
      } catch (err) {
        finish(err as Error)
      }
    }

    doRequest(variant.url, 5)
  })
}

function sha256OfFile(p: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256')
    const r = fs.createReadStream(p)
    r.on('error', reject)
    r.on('data', (c: Buffer | string) =>
      h.update(typeof c === 'string' ? Buffer.from(c) : c)
    )
    r.on('end', () => resolve(h.digest('hex')))
  })
}

async function launchInstaller(installerPath: string): Promise<void> {
  if (process.platform === 'win32') {
    // NSIS one-click=false installer: spawn with `/UPDATE` env so it
    // closes the existing instance after a brief grace period (we
    // already quit). We pass no flags so the user sees the regular
    // wizard — keeps it transparent and won't touch %ProgramFiles%
    // without confirmation.
    const child = spawn(installerPath, [], {
      detached: true,
      stdio: 'ignore',
      windowsHide: false,
    })
    child.unref()
    return
  }
  if (process.platform === 'darwin') {
    // .dmg or .pkg — open with the system default handler. The user
    // mounts the dmg and drags the .app over Applications.
    const child = spawn('open', [installerPath], {
      detached: true,
      stdio: 'ignore',
    })
    child.unref()
    return
  }
  // linux .deb — request a graphical install via xdg-open; falls back
  // to the system file manager / package manager. We don't try to
  // sudo-install ourselves to avoid prompting for the password from
  // a GUI without proper polkit integration.
  const child = spawn('xdg-open', [installerPath], {
    detached: true,
    stdio: 'ignore',
  })
  child.unref()
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
