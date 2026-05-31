export type TgVideoInfo = {
  url: string
  sizeBytes: number
  durationSeconds: number
  mime: string | null
}

const MARKER_RE = /\u200B?\[bmchat:tgvideo ([^\]]+)]\u200B?/i

export function parseTgVideoMarker(text: string | null | undefined): TgVideoInfo | null {
  if (!text) return null
  const match = MARKER_RE.exec(text)
  if (!match) return null

  let url: string | null = null
  let sizeBytes = 0
  let durationSeconds = 0
  let mime: string | null = null

  for (const part of match[1].split(';')) {
    const eq = part.indexOf('=')
    if (eq <= 0) continue
    const key = part.slice(0, eq).trim()
    const value = part.slice(eq + 1).trim()
    if (!value) continue
    if (key === 'url') url = value
    else if (key === 'size') sizeBytes = Number(value) || 0
    else if (key === 'dur') durationSeconds = Number(value) || 0
    else if (key === 'mime') mime = value
  }

  return url ? { url, sizeBytes, durationSeconds, mime } : null
}

export function stripTgVideoMarker(text: string | null): string | null {
  if (!text) return text
  return text.replace(MARKER_RE, '').replace(/\n{3,}/g, '\n\n').trim()
}

export function tgVideoFileName(info: TgVideoInfo, fallback = 'telegram-video'): string {
  const extension = info.mime?.includes('quicktime')
    ? 'mov'
    : info.mime?.includes('webm')
      ? 'webm'
      : 'mp4'
  return `${fallback}.${extension}`
}
