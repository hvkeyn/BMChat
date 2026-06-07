import { runtime } from '@deltachat-desktop/runtime-interface'

export interface EmailBotPublic {
  id: string
  name: string
  displayName?: string | null
  enabled: boolean
  botChatId?: number
  botContactId?: number
}

/** Mirrors Android maybeDispatchEmailBotCommand — immediate bot command handling. */
export async function dispatchEmailBotCommand(
  accountId: number,
  chatId: number,
  msgId: number,
  text?: string | null
): Promise<void> {
  if (runtime.getRuntimeInfo().target !== 'electron') return
  if (accountId <= 0 || chatId <= 0 || msgId <= 0) return
  try {
    await runtime.bmchatBotsInvoke('bmchat:emailbots:dispatch-message', {
      accountId,
      chatId,
      msgId,
      text: text?.trim() || undefined,
    })
  } catch {
    /* ignore */
  }
}

export async function resolveEmailBotHomeChat(
  accountId: number,
  chatId: number
): Promise<{ isHome: boolean; name: string | null }> {
  if (runtime.getRuntimeInfo().target !== 'electron') {
    return { isHome: false, name: null }
  }
  try {
    const res = await runtime.bmchatBotsInvoke('bmchat:emailbots:is-home-chat', {
      accountId,
      chatId,
    })
    return {
      isHome: !!res?.isHome,
      name: typeof res?.name === 'string' ? res.name : null,
    }
  } catch {
    return { isHome: false, name: null }
  }
}

export async function listEmailBots(): Promise<EmailBotPublic[]> {
  if (runtime.getRuntimeInfo().target !== 'electron') {
    return []
  }
  try {
    const bots =
      (await runtime.bmchatBotsInvoke('bmchat:emailbots:list-search')) ??
      (await runtime.bmchatBotsInvoke('bmchat:emailbots:list'))
    if (!Array.isArray(bots)) return []
    return bots.map((b: EmailBotPublic) => ({
      id: b.id,
      name: b.name,
      displayName: b.displayName ?? null,
      enabled: b.enabled !== false,
      botChatId: b.botChatId,
      botContactId: b.botContactId,
    }))
  } catch {
    return []
  }
}

/** Match configured email bots against sidebar search (e.g. "@newsbot"). */
export function matchEmailBots(
  queryStr: string,
  bots: EmailBotPublic[]
): EmailBotPublic[] {
  const trimmed = queryStr.trim().toLowerCase()
  if (!trimmed) return []
  const bare = trimmed.startsWith('@') ? trimmed.slice(1) : trimmed
  if (!bare) return []
  return bots.filter(b => {
    if (!b.enabled) return false
    const n = b.name.toLowerCase()
    return n === bare || n.startsWith(bare) || `@${n}` === trimmed
  })
}

async function resolveBotChatId(
  accountId: number,
  botName: string,
  knownChatId?: number
): Promise<number | null> {
  if (knownChatId && knownChatId > 0) {
    return knownChatId
  }
  try {
    const res = await runtime.bmchatBotsInvoke('bmchat:emailbots:open-chat', {
      accountId,
      botName,
    })
    const chatId = Number(res?.chatId)
    return chatId > 0 ? chatId : null
  } catch {
    return null
  }
}

/** Create pseudo-contact + 1:1 chat (Telegram-style «Add bot»). */
export async function ensureEmailBotContact(
  accountId: number,
  botId: string
): Promise<{ contactId: number; chatId: number } | null> {
  if (runtime.getRuntimeInfo().target !== 'electron') return null
  try {
    const res = await runtime.bmchatBotsInvoke('bmchat:emailbots:ensure-contact', {
      accountId,
      botId,
    })
    if (!res?.ok) return null
    return {
      contactId: Number(res.contactId) || 0,
      chatId: Number(res.chatId) || 0,
    }
  } catch {
    return null
  }
}

/** Open the bot's dedicated 1:1 chat (pseudo-contact) with /start prefilled. */
export async function openEmailBotChat(
  accountId: number,
  botName: string,
  selectChat: (chatId: number) => void,
  knownChatId?: number
): Promise<boolean> {
  const chatId = await resolveBotChatId(accountId, botName, knownChatId)
  if (!chatId) {
    return false
  }
  window.__setDraftRequest = {
    accountId,
    chatId,
    text: '/start',
  }
  selectChat(chatId)
  window.__checkSetDraftRequest?.()
  return true
}
