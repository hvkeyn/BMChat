import React, { useCallback, useEffect, useId, useState } from 'react'

import Dialog, {
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  FooterActionButton,
  FooterActions,
} from '../../Dialog'
import { runtime } from '@deltachat-desktop/runtime-interface'
import useTranslationFunction from '../../../hooks/useTranslationFunction'
import useConfirmationDialog from '../../../hooks/dialog/useConfirmationDialog'
import { selectedAccountId } from '../../../ScreenController'
import useChat from '../../../hooks/chat/useChat'
import { C } from '@deltachat/jsonrpc-client'
import {
  ensureEmailBotContact,
  openEmailBotChat,
} from '../../../bmchat/emailBots'
import { getLogger } from '../../../../../shared/logger'
import useDialog from '../../../hooks/dialog/useDialog'
import SelectChat from '../SelectChat'
import Button from '../../Button'

import type { DialogProps } from '../../../contexts/DialogContext'

const log = getLogger('renderer/dialogs/EmailBots')

interface CommandEntry {
  k: string
  v: string
}

interface EmailBot {
  id: string
  name: string
  description?: string | null
  ownerAccountId: number
  enabled: boolean
  commands: CommandEntry[]
  webhookUrl?: string | null
  token: string
  displayName?: string | null
  developerEmail?: string | null
  subscribedUsers: string[]
  createdAtMs: number
  lastReplyAtMs: number
  totalReplies: number
  botChatId?: number
  attachedChatIds?: number[]
  relayFromChats?: boolean
}

const EB = {
  list: (): Promise<EmailBot[]> =>
    runtime.bmchatBotsInvoke('bmchat:emailbots:list'),
  save: (bot: Partial<EmailBot>) =>
    runtime.bmchatBotsInvoke('bmchat:emailbots:save', bot),
  remove: (id: string) =>
    runtime.bmchatBotsInvoke('bmchat:emailbots:remove', id),
  setEnabled: (id: string, enabled: boolean) =>
    runtime.bmchatBotsInvoke('bmchat:emailbots:set-enabled', { id, enabled }),
  attachChat: (id: string, chatId: number) =>
    runtime.bmchatBotsInvoke('bmchat:emailbots:attach-chat', { id, chatId }),
}

export default function EmailBots({ onClose }: DialogProps) {
  const tx = useTranslationFunction()
  const openConfirmationDialog = useConfirmationDialog()
  const { openDialog, closeDialog } = useDialog()
  const { selectChat } = useChat()
  const accountId = selectedAccountId()

  const pickChat = (
    headerTitle: string,
    onPick: (chatId: number) => void | Promise<void>
  ) => {
    const dialogId = openDialog(SelectChat, {
      headerTitle,
      listFlags: C.DC_GCL_NO_SPECIALS,
      enableAccountSwitch: false,
      onChatClick: ({ chatId }: { chatId: number }) => {
        closeDialog(dialogId)
        void onPick(chatId)
      },
    })
  }

  const [bots, setBots] = useState<EmailBot[]>([])
  const [editing, setEditing] = useState<Partial<EmailBot> | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadFailed, setLoadFailed] = useState(false)

  const refresh = useCallback(async () => {
    try {
      setBots(await EB.list())
      setLoadFailed(false)
    } catch (err) {
      log.warn('list email bots failed', err)
      setLoadFailed(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const onRemove = async (bot: EmailBot) => {
    const confirmed = await openConfirmationDialog({
      message: tx('bmchat_bots_remove_confirm', bot.name),
      confirmLabel: tx('delete'),
      isConfirmDanger: true,
    })
    if (confirmed) {
      await EB.remove(bot.id)
      await refresh()
    }
  }

  if (editing) {
    return (
      <EmailBotEditor
        initial={editing}
        onClose={onClose}
        onBack={() => {
          setEditing(null)
          refresh()
        }}
      />
    )
  }

  return (
    <Dialog onClose={onClose} dataTestid='email-bots-dialog'>
      <DialogHeader title={tx('bmchat_email_bots_title')} />
      <DialogBody>
        <DialogContent>
          <p style={{ marginBottom: 8 }}>{tx('bmchat_email_bots_explain')}</p>
          {loading ? (
            <p className='bmchat-dialog-hint' role='status'>
              {tx('loading')}
            </p>
          ) : loadFailed ? (
            <p className='bmchat-dialog-error' role='alert'>
              {tx('bmchat_bots_load_failed')}
            </p>
          ) : bots.length === 0 ? (
            <p className='bmchat-dialog-hint'>
              {tx('bmchat_email_bots_empty')}
            </p>
          ) : (
            bots.map(bot => (
              <div
                key={bot.id}
                style={{
                  border: '1px solid var(--separatorColor)',
                  borderRadius: 8,
                  padding: 12,
                  marginBottom: 8,
                }}
              >
                <div style={{ fontWeight: 600 }}>
                  @{bot.name}
                  {bot.enabled ? '' : ' · ⏸'}
                </div>
                {bot.description && (
                  <div className='bmchat-dialog-hint'>{bot.description}</div>
                )}
                <div className='bmchat-dialog-hint' style={{ marginTop: 4 }}>
                  {tx('bmchat_email_bot_replies', String(bot.totalReplies))}
                  {(bot.attachedChatIds?.length ?? 0) > 0
                    ? ` · ${tx('bmchat_email_bot_attached_chats', String(bot.attachedChatIds!.length))}`
                    : ''}
                </div>
                <div
                  style={{
                    display: 'flex',
                    gap: 8,
                    marginTop: 8,
                    flexWrap: 'wrap',
                  }}
                >
                  <Button
                    className='bmchat-dialog-action'
                    disabled={!bot.enabled}
                    onClick={async () => {
                      const ensured = await ensureEmailBotContact(
                        accountId,
                        bot.id
                      )
                      const ok = await openEmailBotChat(
                        accountId,
                        bot.name,
                        chatId => selectChat(accountId, chatId),
                        ensured?.chatId ??
                          (bot as { botChatId?: number }).botChatId
                      )
                      if (ok) {
                        onClose()
                      } else {
                        window.__userFeedback?.({
                          type: 'error',
                          text: tx('bmchat_email_bot_open_failed'),
                        })
                      }
                    }}
                  >
                    {tx('bmchat_email_bot_write')}
                  </Button>
                  <Button
                    className='bmchat-dialog-action'
                    disabled={!bot.enabled}
                    onClick={async () => {
                      const res = await ensureEmailBotContact(accountId, bot.id)
                      if (res?.chatId) {
                        window.__userFeedback?.({
                          type: 'success',
                          text: tx('bmchat_email_bot_add_contact_done'),
                        })
                        await refresh()
                      } else {
                        window.__userFeedback?.({
                          type: 'error',
                          text: tx('bmchat_email_bot_add_contact_failed'),
                        })
                      }
                    }}
                  >
                    {tx('bmchat_email_bot_add_contact')}
                  </Button>
                  <Button
                    className='bmchat-dialog-action'
                    onClick={() =>
                      pickChat(
                        tx('bmchat_email_bot_attach_chat'),
                        async chatId => {
                          const res = await EB.attachChat(bot.id, chatId)
                          if (res?.ok === false) {
                            window.__userFeedback?.({
                              type: 'error',
                              text: tx('bmchat_email_bot_attach_failed'),
                            })
                          } else {
                            window.__userFeedback?.({
                              type: 'success',
                              text: tx('bmchat_email_bot_attach_done'),
                            })
                          }
                          await refresh()
                        }
                      )
                    }
                  >
                    {tx('bmchat_email_bot_attach_chat')}
                  </Button>
                  <Button
                    className='bmchat-dialog-action'
                    onClick={() => setEditing(bot)}
                  >
                    {tx('menu_edit_name')}
                  </Button>
                  <Button
                    className='bmchat-dialog-action'
                    onClick={async () => {
                      await EB.setEnabled(bot.id, !bot.enabled)
                      await refresh()
                    }}
                  >
                    {bot.enabled
                      ? tx('bmchat_bots_pause')
                      : tx('bmchat_bots_resume')}
                  </Button>
                  <Button
                    className='bmchat-dialog-action'
                    styling='danger'
                    onClick={() => onRemove(bot)}
                  >
                    {tx('bmchat_bots_remove')}
                  </Button>
                </div>
              </div>
            ))
          )}
        </DialogContent>
      </DialogBody>
      <DialogFooter>
        <FooterActions align='spaceBetween'>
          <FooterActionButton
            type='button'
            onClick={() =>
              setEditing({
                name: '',
                enabled: true,
                commands: [
                  { k: 'start', v: tx('bmchat_email_bot_default_start') },
                  { k: 'help', v: tx('bmchat_email_bot_default_help') },
                ],
              })
            }
          >
            {tx('bmchat_email_bots_add')}
          </FooterActionButton>
          <FooterActionButton onClick={onClose} type='button' styling='primary'>
            {tx('close')}
          </FooterActionButton>
        </FooterActions>
      </DialogFooter>
    </Dialog>
  )
}

function EmailBotEditor({
  initial,
  onBack,
  onClose,
}: {
  initial: Partial<EmailBot>
  onBack: () => void
  onClose: DialogProps['onClose']
}) {
  const tx = useTranslationFunction()
  const [name, setName] = useState(initial.name ?? '')
  const [displayName, setDisplayName] = useState(initial.displayName ?? '')
  const [description, setDescription] = useState(initial.description ?? '')
  const [developerEmail, setDeveloperEmail] = useState(
    initial.developerEmail ?? ''
  )
  const [webhookUrl, setWebhookUrl] = useState(initial.webhookUrl ?? '')
  const [relayFromChats, setRelayFromChats] = useState(
    initial.relayFromChats !== false
  )
  const [commands, setCommands] = useState<CommandEntry[]>(
    initial.commands ?? []
  )
  const [botId, setBotId] = useState(initial.id)
  const [apiToken, setApiToken] = useState(initial.token ?? '')
  const [tokenCopied, setTokenCopied] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const updateCommand = (idx: number, field: 'k' | 'v', value: string) => {
    setCommands(cs =>
      cs.map((c, i) => (i === idx ? { ...c, [field]: value } : c))
    )
  }

  const onSave = async () => {
    if (busy) return
    setBusy(true)
    setError(null)
    const isFirstSave = !botId
    try {
      const res = await EB.save({
        id: botId,
        name: name.trim(),
        displayName: displayName.trim() || null,
        description: description.trim() || null,
        developerEmail: developerEmail.trim() || null,
        webhookUrl: webhookUrl.trim() || null,
        relayFromChats,
        commands: commands
          .map(c => ({ k: c.k.trim(), v: c.v }))
          .filter(c => c.k.length > 0),
        enabled: initial.enabled !== false,
        ownerAccountId: initial.ownerAccountId ?? selectedAccountId(),
        token: initial.token,
        subscribedUsers: initial.subscribedUsers ?? [],
        createdAtMs: initial.createdAtMs,
        lastReplyAtMs: initial.lastReplyAtMs,
        totalReplies: initial.totalReplies,
      })
      if (res?.ok) {
        if (res.bot?.id) setBotId(res.bot.id)
        if (res.bot?.token) setApiToken(res.bot.token)
        if (isFirstSave && res.bot?.token) {
          return
        }
        onBack()
      } else if (res?.error === 'invalid_name') {
        setError(tx('bmchat_email_bot_name_rule'))
      } else if (res?.error === 'name_taken') {
        setError(tx('bmchat_email_bot_name_taken'))
      } else {
        setError(tx('bmchat_email_bot_name_rule'))
      }
    } catch (err) {
      log.warn('save email bot failed', err)
      setError(`${tx('error')}: ${String(err)}`)
    } finally {
      setBusy(false)
    }
  }

  const idPrefix = useId()
  const fieldId = (field: string) => `${idPrefix}-${field}`

  const inputStyle: React.CSSProperties = {
    width: '100%',
    marginBottom: 8,
  }

  return (
    <Dialog onClose={onClose} dataTestid='email-bot-editor-dialog'>
      <DialogHeader
        title={botId ? tx('menu_edit_name') : tx('bmchat_email_bots_add')}
      />
      <DialogBody>
        <DialogContent>
          <label className='bmchat-dialog-label' htmlFor={fieldId('name')}>
            {tx('bmchat_email_bot_field_name')}
          </label>
          <input
            id={fieldId('name')}
            className='bmchat-dialog-input'
            style={inputStyle}
            spellCheck={false}
            placeholder='myhelperbot'
            value={name}
            onChange={e => setName(e.target.value)}
          />
          <label
            className='bmchat-dialog-label'
            htmlFor={fieldId('displayname')}
          >
            {tx('bmchat_email_bot_field_displayname')}
          </label>
          <input
            id={fieldId('displayname')}
            className='bmchat-dialog-input'
            style={inputStyle}
            value={displayName}
            onChange={e => setDisplayName(e.target.value)}
          />
          <label
            className='bmchat-dialog-label'
            htmlFor={fieldId('description')}
          >
            {tx('bmchat_email_bot_field_description')}
          </label>
          <input
            id={fieldId('description')}
            className='bmchat-dialog-input'
            style={inputStyle}
            value={description}
            onChange={e => setDescription(e.target.value)}
          />
          <label className='bmchat-dialog-label' htmlFor={fieldId('webhook')}>
            {tx('bmchat_email_bot_field_webhook')}
          </label>
          <input
            id={fieldId('webhook')}
            className='bmchat-dialog-input'
            style={inputStyle}
            spellCheck={false}
            placeholder='https://example.com/bot'
            value={webhookUrl}
            onChange={e => setWebhookUrl(e.target.value)}
          />
          <p
            className='bmchat-dialog-hint'
            style={{ marginTop: 0, marginBottom: 12 }}
          >
            {tx('bmchat_email_bot_field_webhook_hint')}
          </p>

          <label
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              marginBottom: 4,
              cursor: 'pointer',
            }}
          >
            <input
              type='checkbox'
              checked={relayFromChats}
              onChange={e => setRelayFromChats(e.target.checked)}
            />
            {tx('bmchat_email_bot_field_relay_from_chats')}
          </label>
          <p
            className='bmchat-dialog-hint'
            style={{ marginTop: 0, marginBottom: 12 }}
          >
            {tx('bmchat_email_bot_field_relay_from_chats_hint')}
          </p>

          <label
            className='bmchat-dialog-label'
            htmlFor={fieldId('developer-email')}
          >
            {tx('bmchat_email_bot_field_developer_email')}
          </label>
          <input
            id={fieldId('developer-email')}
            className='bmchat-dialog-input'
            style={inputStyle}
            spellCheck={false}
            placeholder='developer@example.com'
            value={developerEmail}
            onChange={e => setDeveloperEmail(e.target.value)}
          />
          <p
            className='bmchat-dialog-hint'
            style={{ marginTop: 0, marginBottom: 12 }}
          >
            {tx('bmchat_email_bot_field_developer_email_hint')}
          </p>

          {apiToken ? (
            <div style={{ marginBottom: 12 }}>
              <label className='bmchat-dialog-label' htmlFor={fieldId('token')}>
                {tx('bmchat_email_bot_token_label')}
              </label>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <input
                  id={fieldId('token')}
                  className='bmchat-dialog-input'
                  style={{ flex: 1, fontFamily: 'monospace', fontSize: 12 }}
                  readOnly
                  value={apiToken}
                  onChange={() => {}}
                />
                <Button
                  className='bmchat-dialog-action'
                  onClick={async () => {
                    await runtime.writeClipboardText(apiToken)
                    setTokenCopied(true)
                    setTimeout(() => setTokenCopied(false), 2000)
                  }}
                >
                  {tokenCopied
                    ? tx('bmchat_email_bot_token_copied')
                    : tx('bmchat_email_bot_token_copy')}
                </Button>
              </div>
              <p className='bmchat-dialog-hint' style={{ marginTop: 4 }}>
                {tx('bmchat_email_bot_token_hint')}
              </p>
            </div>
          ) : (
            <p className='bmchat-dialog-hint' style={{ marginBottom: 12 }}>
              {tx('bmchat_email_bot_token_hint')}
            </p>
          )}

          <div style={{ fontWeight: 600, margin: '12px 0 8px' }}>
            {tx('bmchat_email_bot_field_commands')}
          </div>
          {commands.map((c, idx) => (
            <div
              key={idx}
              style={{
                display: 'flex',
                gap: 8,
                marginBottom: 8,
                alignItems: 'center',
              }}
            >
              <label
                className='bmchat-visually-hidden'
                htmlFor={fieldId(`cmd-k-${idx}`)}
              >
                {tx('bmchat_email_bot_command_key')}
              </label>
              <input
                id={fieldId(`cmd-k-${idx}`)}
                className='bmchat-dialog-input'
                style={{ width: 112 }}
                placeholder={tx('bmchat_email_bot_command_key')}
                value={c.k}
                onChange={e => updateCommand(idx, 'k', e.target.value)}
              />
              <label
                className='bmchat-visually-hidden'
                htmlFor={fieldId(`cmd-v-${idx}`)}
              >
                {tx('bmchat_email_bot_command_value')}
              </label>
              <input
                id={fieldId(`cmd-v-${idx}`)}
                className='bmchat-dialog-input'
                style={{ flex: 1 }}
                placeholder={tx('bmchat_email_bot_command_value')}
                value={c.v}
                onChange={e => updateCommand(idx, 'v', e.target.value)}
              />
              <Button
                className='bmchat-dialog-action'
                aria-label={tx('delete')}
                title={tx('delete')}
                onClick={() =>
                  setCommands(cs => cs.filter((_, i) => i !== idx))
                }
              >
                <span aria-hidden='true'>✕</span>
              </Button>
            </div>
          ))}
          <Button
            className='bmchat-dialog-action'
            onClick={() => setCommands(cs => [...cs, { k: '', v: '' }])}
          >
            {tx('bmchat_email_bot_add_command')}
          </Button>

          <p className='bmchat-dialog-hint' style={{ marginTop: 12 }}>
            {tx('bmchat_email_bot_placeholders_hint')}
          </p>
          {error && (
            <p className='bmchat-dialog-error' role='alert'>
              {error}
            </p>
          )}
        </DialogContent>
      </DialogBody>
      <DialogFooter>
        <FooterActions align='spaceBetween'>
          <FooterActionButton type='button' onClick={onBack}>
            {tx('back')}
          </FooterActionButton>
          <FooterActionButton
            type='button'
            styling='primary'
            disabled={busy || name.trim().length === 0}
            onClick={onSave}
          >
            {tx('bmchat_email_bot_save')}
          </FooterActionButton>
        </FooterActions>
      </DialogFooter>
    </Dialog>
  )
}
