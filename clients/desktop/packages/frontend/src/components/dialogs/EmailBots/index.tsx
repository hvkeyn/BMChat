import React, { useCallback, useEffect, useState } from 'react'

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
import { openEmailBotChat } from '../../../bmchat/emailBots'
import { getLogger } from '../../../../../shared/logger'

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
}

export default function EmailBots({ onClose }: DialogProps) {
  const tx = useTranslationFunction()
  const openConfirmationDialog = useConfirmationDialog()
  const { selectChat } = useChat()
  const accountId = selectedAccountId()

  const [bots, setBots] = useState<EmailBot[]>([])
  const [editing, setEditing] = useState<Partial<EmailBot> | null>(null)

  const refresh = useCallback(async () => {
    try {
      setBots(await EB.list())
    } catch (err) {
      log.warn('list email bots failed', err)
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
          <p style={{ marginBottom: 10 }}>{tx('bmchat_email_bots_explain')}</p>
          {bots.length === 0 ? (
            <p style={{ opacity: 0.7 }}>{tx('bmchat_email_bots_empty')}</p>
          ) : (
            bots.map(bot => (
              <div
                key={bot.id}
                style={{
                  border: '1px solid var(--separatorColor, #ddd)',
                  borderRadius: 8,
                  padding: '10px 12px',
                  marginBottom: 10,
                }}
              >
                <div style={{ fontWeight: 600 }}>
                  @{bot.name}
                  {bot.enabled ? '' : ' · ⏸'}
                </div>
                {bot.description && (
                  <div style={{ fontSize: 12, opacity: 0.7 }}>
                    {bot.description}
                  </div>
                )}
                <div style={{ fontSize: 12, opacity: 0.6, marginTop: 4 }}>
                  {tx('bmchat_email_bot_replies', String(bot.totalReplies))}
                </div>
                <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
                  <button
                    className='delta-button-round'
                    disabled={!bot.enabled}
                    onClick={async () => {
                      const ok = await openEmailBotChat(
                        accountId,
                        bot.name,
                        chatId => selectChat(accountId, chatId)
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
                  </button>
                  <button
                    className='delta-button-round'
                    onClick={() => setEditing(bot)}
                  >
                    {tx('menu_edit_name')}
                  </button>
                  <button
                    className='delta-button-round'
                    onClick={async () => {
                      await EB.setEnabled(bot.id, !bot.enabled)
                      await refresh()
                    }}
                  >
                    {bot.enabled
                      ? tx('bmchat_bots_pause')
                      : tx('bmchat_bots_resume')}
                  </button>
                  <button
                    className='delta-button-round'
                    style={{ color: 'var(--colorDanger, #d9534f)' }}
                    onClick={() => onRemove(bot)}
                  >
                    {tx('bmchat_bots_remove')}
                  </button>
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
                  { k: 'start', v: 'Привет, {{from}}! Я бот @{{bot}}.' },
                  { k: 'help', v: 'Команды: /start, /help' },
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
      setError(String(err))
    } finally {
      setBusy(false)
    }
  }

  const inputStyle: React.CSSProperties = {
    width: '100%',
    marginBottom: 10,
  }

  return (
    <Dialog onClose={onClose} dataTestid='email-bot-editor-dialog'>
      <DialogHeader
        title={botId ? tx('menu_edit_name') : tx('bmchat_email_bots_add')}
      />
      <DialogBody>
        <DialogContent>
          <label>{tx('bmchat_email_bot_field_name')}</label>
          <input
            className='search-input'
            style={inputStyle}
            spellCheck={false}
            placeholder='myhelperbot'
            value={name}
            onChange={e => setName(e.target.value)}
          />
          <label>{tx('bmchat_email_bot_field_displayname')}</label>
          <input
            className='search-input'
            style={inputStyle}
            value={displayName}
            onChange={e => setDisplayName(e.target.value)}
          />
          <label>{tx('bmchat_email_bot_field_description')}</label>
          <input
            className='search-input'
            style={inputStyle}
            value={description}
            onChange={e => setDescription(e.target.value)}
          />
          <label>{tx('bmchat_email_bot_field_webhook')}</label>
          <input
            className='search-input'
            style={inputStyle}
            spellCheck={false}
            placeholder='https://example.com/bot'
            value={webhookUrl}
            onChange={e => setWebhookUrl(e.target.value)}
          />
          <p style={{ fontSize: 12, opacity: 0.7, marginTop: -6, marginBottom: 10 }}>
            {tx('bmchat_email_bot_field_webhook_hint')}
          </p>

          <label>{tx('bmchat_email_bot_field_developer_email')}</label>
          <input
            className='search-input'
            style={inputStyle}
            spellCheck={false}
            placeholder='developer@example.com'
            value={developerEmail}
            onChange={e => setDeveloperEmail(e.target.value)}
          />
          <p style={{ fontSize: 12, opacity: 0.7, marginTop: -6, marginBottom: 10 }}>
            {tx('bmchat_email_bot_field_developer_email_hint')}
          </p>

          {apiToken ? (
            <div style={{ marginBottom: 12 }}>
              <label>{tx('bmchat_email_bot_token_label')}</label>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <input
                  className='search-input'
                  style={{ flex: 1, fontFamily: 'monospace', fontSize: 12 }}
                  readOnly
                  value={apiToken}
                  onChange={() => {}}
                />
                <button
                  type='button'
                  className='delta-button-round'
                  onClick={async () => {
                    await runtime.writeClipboardText(apiToken)
                    setTokenCopied(true)
                    setTimeout(() => setTokenCopied(false), 2000)
                  }}
                >
                  {tokenCopied
                    ? tx('bmchat_email_bot_token_copied')
                    : tx('bmchat_email_bot_token_copy')}
                </button>
              </div>
              <p style={{ fontSize: 12, opacity: 0.7, marginTop: 4 }}>
                {tx('bmchat_email_bot_token_hint')}
              </p>
            </div>
          ) : (
            <p style={{ fontSize: 12, opacity: 0.7, marginBottom: 12 }}>
              {tx('bmchat_email_bot_token_hint')}
            </p>
          )}

          <div style={{ fontWeight: 600, margin: '12px 0 6px' }}>
            {tx('bmchat_email_bot_field_commands')}
          </div>
          {commands.map((c, idx) => (
            <div
              key={idx}
              style={{ display: 'flex', gap: 6, marginBottom: 6 }}
            >
              <input
                className='search-input'
                style={{ width: 110 }}
                placeholder={tx('bmchat_email_bot_command_key')}
                value={c.k}
                onChange={e => updateCommand(idx, 'k', e.target.value)}
              />
              <input
                className='search-input'
                style={{ flex: 1 }}
                placeholder={tx('bmchat_email_bot_command_value')}
                value={c.v}
                onChange={e => updateCommand(idx, 'v', e.target.value)}
              />
              <button
                className='delta-button-round'
                onClick={() =>
                  setCommands(cs => cs.filter((_, i) => i !== idx))
                }
              >
                ✕
              </button>
            </div>
          ))}
          <button
            className='delta-button-round'
            onClick={() => setCommands(cs => [...cs, { k: '', v: '' }])}
          >
            {tx('bmchat_email_bot_add_command')}
          </button>

          <p style={{ fontSize: 12, opacity: 0.7, marginTop: 12 }}>
            {tx('bmchat_email_bot_placeholders_hint')}
          </p>
          {error && <p className='input-error'>{error}</p>}
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
