import { runtime } from '@deltachat-desktop/runtime-interface'
import type { T } from '@deltachat/jsonrpc-client'

export type BotContactDisplay = {
  slug: string
  description?: string | null
  avatarPath?: string | null
}

function slugFromPseudoEmail(addr: string): string | null {
  const lower = addr.toLowerCase().trim()
  if (!lower.endsWith('@bots.bmchat.local')) return null
  const at = lower.indexOf('@')
  if (at <= 0) return null
  const local = lower.slice(0, at)
  if (local.startsWith('emailbot.')) return local.slice('emailbot.'.length)
  if (local.startsWith('tgbot.')) return local.slice('tgbot.'.length)
  return null
}

export async function resolveBotContactDisplay(
  accountId: number,
  contact: T.Contact
): Promise<BotContactDisplay | null> {
  const slug = slugFromPseudoEmail(contact.address || '')
  if (!slug || runtime.getRuntimeInfo().target !== 'electron') return null

  let description: string | null = null
  let avatarPath: string | null = null

  try {
    const rawEmail = await runtime.bmchatBotsInvoke('bmchat:emailbots:list')
    const emailBots = Array.isArray(rawEmail) ? rawEmail : []
    const emailBot = emailBots.find(
      (b: {
        ownerAccountId?: number
        name?: string
        botContactId?: number
        description?: string | null
        avatarPath?: string | null
      }) =>
        b.ownerAccountId === accountId &&
        (Number(b.botContactId) === contact.id ||
          String(b.name || '').toLowerCase() === slug)
    )
    if (emailBot) {
      description = emailBot.description?.trim() || null
      avatarPath = emailBot.avatarPath || null
    }
  } catch {
    /* ignore */
  }

  if (!description || !avatarPath) {
    try {
      const rawTg = await runtime.bmchatBotsInvoke('bmchat:tgbots:list-config')
      const tgBots = Array.isArray(rawTg) ? rawTg : []
      const tgBot = tgBots.find(
        (b: {
          accountId?: number
          botContactId?: number
          telegramUsername?: string | null
          description?: string | null
          shortDescription?: string | null
          avatarPath?: string | null
        }) =>
          b.accountId === accountId &&
          (Number(b.botContactId) === contact.id ||
            String(b.telegramUsername || '').toLowerCase() === slug)
      )
      if (tgBot) {
        description =
          description ||
          tgBot.description?.trim() ||
          tgBot.shortDescription?.trim() ||
          null
        avatarPath = avatarPath || tgBot.avatarPath || null
      }
    } catch {
      /* ignore */
    }
  }

  return { slug, description, avatarPath }
}