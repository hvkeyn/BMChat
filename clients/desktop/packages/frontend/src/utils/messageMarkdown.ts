/**
 * Telegram-style message formatting for BMChat (Markdown / HTML / MarkdownV2).
 * Ported from Android {@code MessageMarkdown} with bmchat-bot:// link support.
 */

export type BotParseMode = 'Markdown' | 'HTML' | 'MarkdownV2' | undefined

export interface FormattedSegment {
  type: 'text' | 'link' | 'bold' | 'italic' | 'strike' | 'underline' | 'code' | 'pre' | 'spoiler' | 'quote'
  value: string
  href?: string
}

const PRE = /```\s*\n([\s\S]+?)\n```/g
const CODE = /`([^`\n]+)`/g
const BOLD = /\*\*(\S(?:[^*\n]*\S)?)\*\*/g
const ITALIC = /__(\S(?:[^_\n]*\S)?)__/g
const STRIKE = /~~(\S(?:[^~\n]*\S)?)~~/g
const UNDERLINE = /\+\+(\S(?:[^+\n]*\S)?)\+\+/g
const SPOILER = /\|\|(\S(?:[^|\n]*\S)?)\|\|/g
/** [label](url) — http(s), bmchat-bot://, mailto:, cmd: */
const LINK =
  /\[([^\]\n]+)\]\(((?:https?:\/\/|bmchat-bot:\/\/|mailto:|cmd:)[^)\s]+)\)/gi
const QUOTE_LINE = /^> ?(.*)$/gm

const HTML_TAG =
  /<(b|strong|i|em|u|ins|s|strike|del|code|pre|a)(\s[^>]*)?>([\s\S]*?)<\/\1>/gi
const HTML_BR = /<br\s*\/?>/gi

export function normalizeParseMode(
  mode: string | undefined | null
): BotParseMode {
  if (!mode) return undefined
  const m = mode.trim().toLowerCase()
  if (m === 'html') return 'HTML'
  if (m === 'markdownv2' || m === 'markdown_v2') return 'MarkdownV2'
  if (m === 'markdown') return 'Markdown'
  return undefined
}

/** Convert Telegram MarkdownV2 escapes to BMChat markdown subset. */
export function telegramMarkdownV2ToMarkdown(text: string): string {
  let s = text
  // Unescape common Telegram escapes
  s = s.replace(/\\([_*[\]()~`>#+\-=|{}.!])/g, '$1')
  // *bold* -> **bold**
  s = s.replace(/\*([^*\n]+)\*/g, '**$1**')
  // _italic_ -> __italic__
  s = s.replace(/_([^_\n]+)_/g, '__$1__')
  // ~strike~ -> ~~strike~~
  s = s.replace(/~([^~\n]+)~/g, '~~$1~~')
  return s
}

function stripHtmlToMarkdown(html: string): string {
  let s = html.replace(HTML_BR, '\n')
  s = s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
  let prev = ''
  while (prev !== s) {
    prev = s
    s = s.replace(HTML_TAG, (_m, tag, attrs, inner) => {
      const t = String(tag).toLowerCase()
      const body = inner.trim()
      if (t === 'b' || t === 'strong') return `**${body}**`
      if (t === 'i' || t === 'em') return `__${body}__`
      if (t === 'u' || t === 'ins') return `++${body}++`
      if (t === 's' || t === 'strike' || t === 'del') return `~~${body}~~`
      if (t === 'code') return `\`${body}\``
      if (t === 'pre') return '```\n' + body + '\n```'
      if (t === 'a') {
        const hrefMatch = attrs ? /href\s*=\s*["']([^"']+)["']/i.exec(attrs) : null
        const href = hrefMatch?.[1] ?? body
        return `[${body}](${href})`
      }
      return body
    })
  }
  return s.replace(/<[^>]+>/g, '')
}

/** Apply parse_mode then BMChat markdown → plain text + link spans for the UI. */
export function formatMessageForDisplay(
  raw: string,
  parseMode?: BotParseMode
): { text: string; links: Array<{ start: number; end: number; label: string; url: string }> } {
  let text = raw
  if (parseMode === 'HTML') {
    text = stripHtmlToMarkdown(text)
  } else if (parseMode === 'MarkdownV2') {
    text = telegramMarkdownV2ToMarkdown(text)
  }
  text = applyMarkdownToPlain(text)
  const links: Array<{ start: number; end: number; label: string; url: string }> = []
  return { text, links }
}

/** Strip markdown markers; keep link labels with URL metadata in `links` array. */
export function applyMarkdownToPlain(input: string): string {
  let text = input

  text = text.replace(PRE, (_m, inner) => inner)
  text = text.replace(CODE, (_m, inner) => inner)
  text = text.replace(LINK, (_m, label) => label)
  text = text.replace(BOLD, (_m, inner) => inner)
  text = text.replace(ITALIC, (_m, inner) => inner)
  text = text.replace(STRIKE, (_m, inner) => inner)
  text = text.replace(UNDERLINE, (_m, inner) => inner)
  text = text.replace(SPOILER, (_m, inner) => inner)
  text = text.replace(QUOTE_LINE, (_m, inner) => inner)

  return text
}

/** Split message into renderable parts (text runs + links). */
export function parseFormattedMessage(
  raw: string,
  parseMode?: BotParseMode
): FormattedSegment[] {
  let text = raw
  if (parseMode === 'HTML') {
    text = stripHtmlToMarkdown(text)
  } else if (parseMode === 'MarkdownV2') {
    text = telegramMarkdownV2ToMarkdown(text)
  }

  const segments: FormattedSegment[] = []
  const linkRe = new RegExp(LINK.source, 'gi')
  let last = 0
  let m: RegExpExecArray | null
  while ((m = linkRe.exec(text)) !== null) {
    if (m.index > last) {
      segments.push({
        type: 'text',
        value: applyMarkdownToPlain(text.slice(last, m.index)),
      })
    }
    segments.push({
      type: 'link',
      value: m[1],
      href: m[2],
    })
    last = m.index + m[0].length
  }
  if (last < text.length) {
    segments.push({ type: 'text', value: applyMarkdownToPlain(text.slice(last)) })
  }
  if (segments.length === 0) {
    segments.push({ type: 'text', value: applyMarkdownToPlain(text) })
  }
  return segments
}

/** Detect bot/API messages that should use rich formatting. */
export function messageLikelyFormatted(text: string): boolean {
  if (!text) return false
  return (
    /\[[^\]]+\]\([^)]+\)/.test(text) ||
    /\*\*[^*]+\*\*/.test(text) ||
    /__[^_]+__/.test(text) ||
    /~~[^~]+~~/.test(text) ||
    /🔘\s*\[/.test(text) ||
    /<[a-z][^>]*>/i.test(text)
  )
}
