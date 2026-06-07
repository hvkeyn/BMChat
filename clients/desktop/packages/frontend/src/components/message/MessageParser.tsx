import React, { useContext } from 'react'

import * as linkify from 'linkifyjs'
import 'linkify-plugin-hashtag'
import '../../utils/linkify/plugin-bot-command/index.js'

import { Link } from './Link.js'
import { parseElements } from '../../utils/linkify/parseElements.js'
import {
  applyMarkdownToPlain,
  messageLikelyFormatted,
  parseFormattedMessage,
  telegramMarkdownToMarkdown,
} from '../../utils/messageMarkdown.js'
import { getLogger } from '@deltachat-desktop/shared/logger'
import { ActionEmitter, KeybindAction } from '../../keybindings'
import { BackendRemote } from '../../backend-com'
import { selectedAccountId } from '../../ScreenController'
import { MessagesDisplayContext } from '../../contexts/MessagesDisplayContext'
import useChat from '../../hooks/chat/useChat'
import useCreateChatByEmail from '../../hooks/chat/useCreateChatByEmail'
import useDialog from '../../hooks/dialog/useDialog'
import useMessage from '../../hooks/chat/useMessage'
import { runtime } from '@deltachat-desktop/runtime-interface'
import { resolveEmailBotHomeChat } from '../../bmchat/emailBots'

const log = getLogger('renderer/message-parser')

async function isEmailBotChat(
  accountId: number,
  chatId: number
): Promise<boolean> {
  if (runtime.getRuntimeInfo().target === 'electron') {
    try {
      const resolved = await resolveEmailBotHomeChat(accountId, chatId)
      if (resolved.isHome) return true
    } catch {
      /* fall through */
    }
  }
  try {
    const chat = await BackendRemote.rpc.getBasicChatInfo(accountId, chatId)
    if (chat.chatType !== 'Single' || chat.contactIds.length < 1) return false
    const contact = await BackendRemote.rpc.getContact(
      accountId,
      chat.contactIds[0]
    )
    const addr = (contact.address || '').toLowerCase()
    return contact.isBot || addr.endsWith('@bots.bmchat.local')
  } catch {
    return false
  }
}

function renderElement(
  elm: linkify.MultiToken,
  tabindexForInteractiveContents: -1 | 0,
  key?: number
): React.ReactElement {
  switch (elm.t) {
    case 'hashtag':
      return (
        <TagLink
          key={key}
          tag={elm.v}
          tabIndex={tabindexForInteractiveContents}
        />
      )

    /**
     * linkifyJS does even identify URLs without scheme as URL, e.g.
     * "www.example.com" or "example.com/test" or "example.com?param=value" etc.
     * It does only identify valid TLDs based on https://data.iana.org/TLD/tlds-alpha-by-domain.txt
     */
    case 'url': {
      let fullUrl = elm.v
      // no token for scheme?
      if (!elm.tk.find(t => ['SLASH_SCHEME', 'SCHEME'].includes(t.t))) {
        // no scheme so we add https as default
        // be aware that custom protocols may not
        // have a SLASH_SCHEME but just a SCHEME
        // see https://github.com/nfrasser/linkifyjs/blob/3abe9abbcb4e069aeadde2f42de7dfcc2371c0f0/packages/linkifyjs/src/text.mjs#L24
        fullUrl = 'https://' + fullUrl
      }
      const url = new URL(fullUrl)
      let suspicousUrl = false
      const stripLastSlash = (url: string) => {
        if (url.endsWith('/')) {
          url = url.slice(0, -1)
        }
        return url
      }
      // according to https://developer.mozilla.org/docs/Web/API/URL/hostname
      // domain names will be transformed to punycode automatically
      // so we just need to check if the original hostname is different
      // from the punycode one
      if (stripLastSlash(url.href) !== stripLastSlash(fullUrl)) {
        suspicousUrl = true
      }
      const destination = {
        target: fullUrl,
        hostname: url.hostname,
        punycode: suspicousUrl
          ? {
              ascii_hostname: url.hostname,
              punycode_encoded_url: url.href,
              original_hostname_or_full_url: elm.v,
            }
          : null,
        scheme: url.protocol.replace(':', ''),
        linkText: elm.v,
      }
      return (
        <Link
          destination={destination}
          key={key}
          tabIndex={tabindexForInteractiveContents}
        />
      )
    }

    case 'email': {
      const email = elm.v
      return (
        <EmailLink
          key={key}
          email={email}
          tabIndex={tabindexForInteractiveContents}
        />
      )
    }

    case 'botcommand':
      return (
        <BotCommandSuggestion
          key={key}
          suggestion={elm.v}
          tabIndex={tabindexForInteractiveContents}
        />
      )

    case 'nl':
      return <span key={key}>{'\n'}</span>

    case 'text':
      return <span key={key}>{elm.v}</span>
    default:
      log.error(`type ${elm.t} not known/implemented yet`, elm)
      return (
        <span key={key} style={{ color: 'red' }}>
          {elm.v}
        </span>
      )
  }
}

/**
 * parse message text (for links and interactive elements)
 * and render as React elements
 *
 * @param preview - render in preview mode for ChatListItem summary
 * and for quoted messages, without interactive elements
 * (links can not be clicked etc.)
 */
export function parseAndRenderMessage(
  message: string,
  preview: boolean,
  /**
   * Has no effect if `{@link preview} === true`, because there should be
   * no interactive elements in the first place
   */
  tabindexForInteractiveContents: -1 | 0
): React.ReactElement {
  if (preview) {
    const plain = messageLikelyFormatted(message)
      ? applyMarkdownToPlain(telegramMarkdownToMarkdown(message))
      : message
    return <div className='truncated'>{plain}</div>
  }
  try {
    if (messageLikelyFormatted(message)) {
      return (
        <>
          {parseFormattedMessage(message).map((seg, index) => {
            if (seg.type === 'link' && seg.href) {
              if (seg.href.startsWith('bmchat-bot://')) {
                return (
                  <BmchatBotLink
                    key={index}
                    label={seg.value}
                    url={seg.href}
                    tabIndex={tabindexForInteractiveContents}
                  />
                )
              }
              if (
                seg.href.startsWith('http://') ||
                seg.href.startsWith('https://')
              ) {
                let hostname: string | null = null
                try {
                  hostname = new URL(seg.href).hostname
                } catch {
                  /* ignore */
                }
                return (
                  <Link
                    key={index}
                    destination={{
                      target: seg.href,
                      hostname,
                      punycode: null,
                      scheme: seg.href.startsWith('https') ? 'https' : 'http',
                      linkText: seg.value,
                    }}
                    tabIndex={tabindexForInteractiveContents}
                  />
                )
              }
              return (
                <a
                  key={index}
                  href={seg.href}
                  tabIndex={tabindexForInteractiveContents}
                  onClick={ev => {
                    ev.preventDefault()
                    ev.stopPropagation()
                    window.open(seg.href, '_blank', 'noopener')
                  }}
                >
                  {seg.value}
                </a>
              )
            }
            if (seg.type === 'bold') {
              return <strong key={index}>{seg.value}</strong>
            }
            if (seg.type === 'italic') {
              return <em key={index}>{seg.value}</em>
            }
            if (seg.type === 'strike') {
              return <s key={index}>{seg.value}</s>
            }
            if (seg.type === 'underline') {
              return <u key={index}>{seg.value}</u>
            }
            if (seg.type === 'code') {
              return (
                <code key={index} className='message-inline-code'>
                  {seg.value}
                </code>
              )
            }
            if (seg.type === 'spoiler') {
              return (
                <span key={index} className='message-spoiler'>
                  {seg.value}
                </span>
              )
            }
            const elements = parseElements(seg.value)
            return elements.map((el, i) =>
              renderElement(el, tabindexForInteractiveContents, index * 1000 + i)
            )
          })}
        </>
      )
    }
    const elements = parseElements(message)
    return (
      <>
        {elements.map((el, index) =>
          renderElement(el, tabindexForInteractiveContents, index)
        )}
      </>
    )
  } catch (error) {
    log.error('parseAndRenderMessage failed:', { input: message, error })
    return <>{message}</>
  }
}

function EmailLink({
  email,
  tabIndex,
}: {
  email: string
  tabIndex: -1 | 0
}): React.ReactElement {
  const accountId = selectedAccountId()
  const createChatByEmail = useCreateChatByEmail()
  const { selectChat } = useChat()
  const { closeAllDialogs } = useDialog()

  const handleClick: React.MouseEventHandler<HTMLAnchorElement> = async ev => {
    ev.preventDefault()
    ev.stopPropagation()
    const chatId = await createChatByEmail(accountId, email)
    if (chatId) {
      selectChat(accountId, chatId)
      closeAllDialogs()
    }
  }

  return (
    <a
      href={`mailto:${email}`}
      x-not-a-link='email'
      x-target-email={email}
      onClick={handleClick}
      tabIndex={tabIndex}
    >
      {email}
    </a>
  )
}

function TagLink({ tag, tabIndex }: { tag: string; tabIndex: -1 | 0 }) {
  const setSearch = () => {
    log.debug(
      `Clicked on a hashtag, this should open search for the text "${tag}"`
    )
    if (window.__chatlistSetSearch) {
      window.__chatlistSetSearch(tag, null)
      ActionEmitter.emitAction(KeybindAction.ChatList_FocusSearchInput)
    }
  }

  return (
    <a href={'#'} x-not-a-link='tag' onClick={setSearch} tabIndex={tabIndex}>
      {tag}
    </a>
  )
}

function BmchatBotLink({
  label,
  url,
  tabIndex,
}: {
  label: string
  url: string
  tabIndex: -1 | 0
}) {
  const accountId = selectedAccountId()
  const messageDisplay = useContext(MessagesDisplayContext)
  const chatId =
    messageDisplay?.context === 'chat_messagelist'
      ? messageDisplay.chatId
      : undefined

  const onClick: React.MouseEventHandler<HTMLAnchorElement> = async ev => {
    ev.preventDefault()
    ev.stopPropagation()
    if (runtime.getRuntimeInfo().target !== 'electron') return
    try {
      const res = await runtime.bmchatBotsInvoke('bmchat:emailbots:callback', {
        accountId,
        url,
        chatId,
      })
      if (res && typeof res === 'object' && 'ok' in res && !res.ok) {
        const err = (res as { error?: string }).error
        const text =
          err === 'no_bot'
            ? window.static_translate('bmchat_email_bot_cb_no_bot')
            : err === 'no_webhook'
              ? window.static_translate('bmchat_email_bot_cb_no_webhook')
              : window.static_translate('bmchat_email_bot_cb_no_reply')
        window.__userFeedback?.({ type: 'error', text })
      }
    } catch {
      window.__userFeedback?.({
        type: 'error',
        text: window.static_translate('bmchat_email_bot_cb_no_reply'),
      })
    }
  }

  return (
    <a
      href={url}
      x-not-a-link='bmchat-bot'
      className='bmchat-bot-inline-btn'
      onClick={onClick}
      tabIndex={tabIndex}
      style={{
        display: 'inline-block',
        margin: '2px 4px 2px 0',
        padding: '4px 10px',
        borderRadius: 14,
        background: 'var(--colorPrimary, #4a90d9)',
        color: '#fff',
        textDecoration: 'none',
        fontSize: '0.92em',
      }}
    >
      {label.replace(/^🔘\s*/, '')}
    </a>
  )
}

function BotCommandSuggestion({
  suggestion,
  tabIndex,
}: {
  suggestion: string
  tabIndex: -1 | 0
}) {
  const messageDisplay = useContext(MessagesDisplayContext)
  const accountId = selectedAccountId()
  const { selectChat } = useChat()
  const { sendMessage } = useMessage()

  const applySuggestion = async (ev: React.MouseEvent) => {
    ev.preventDefault()
    ev.stopPropagation()
    if (!messageDisplay) {
      return
    }

    let chatId
    if (messageDisplay.context == 'contact_profile_status') {
      // Bot command was clicked inside of a contact status
      chatId = await BackendRemote.rpc.createChatByContactId(
        accountId,
        messageDisplay.contact_id
      )
      // also select the chat and close the profile window if this is the case
      selectChat(accountId, chatId)
      messageDisplay.closeProfileDialog()
    } else if (messageDisplay.context == 'chat_messagelist') {
      chatId = messageDisplay.chatId
    } else {
      log.error(
        'Error applying BotCommandSuggestion: MessageDisplayContext.type is not implemented: ',
        //@ts-ignore
        messageDisplay.type
      )
      return
    }

    const cmd = suggestion.trim()
    if (cmd && (await isEmailBotChat(accountId, chatId))) {
      await sendMessage(accountId, chatId, { text: cmd })
      return
    }

    // Copy-pasted from `useCreateDraftMesssage`.
    if (window.__setDraftRequest != undefined) {
      log.error('previous BotCommandSuggestion has not worked?')
    }
    window.__setDraftRequest = {
      accountId,
      chatId,
      text: suggestion,
    }
    window.__checkSetDraftRequest?.()
  }

  return (
    <a
      href='#'
      x-not-a-link='bcs'
      onClick={applySuggestion}
      tabIndex={tabIndex}
    >
      {suggestion}
    </a>
  )
}
