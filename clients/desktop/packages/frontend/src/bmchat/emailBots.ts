import { runtime } from '@deltachat-desktop/runtime-interface'

export interface EmailBotPublic {
  id: string
  name: string
  displayName?: string | null
  enabled: boolean
  botChatId?: number
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
