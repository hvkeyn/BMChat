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
import { C } from '@deltachat/jsonrpc-client'
import useTranslationFunction from '../../../hooks/useTranslationFunction'
import useDialog from '../../../hooks/dialog/useDialog'
import useConfirmationDialog from '../../../hooks/dialog/useConfirmationDialog'
import SelectChat from '../SelectChat'
import { getLogger } from '../../../../../shared/logger'

import type { DialogProps } from '../../../contexts/DialogContext'

const log = getLogger('renderer/dialogs/TelegramBots')

interface BotPublic {
  id: string
  displayName: string
  telegramUsername?: string | null
  accountId: number
  targetChatId: number
  paused: boolean
  manualReview: boolean
  lastPolledAtMs: number
  pendingCount: number
}

interface PendingItem {
  id: string
  botId: string
  createdAtMs: number
  preview: string
  hasAttachment: boolean
  viewtype: string | null
}

const TG = {
  list: (): Promise<BotPublic[]> => runtime.bmchatBotsInvoke('bmchat:tgbots:list'),
  add: (p: { token: string; accountId: number; chatId: number }) =>
    runtime.bmchatBotsInvoke('bmchat:tgbots:add', p),
  remove: (id: string) => runtime.bmchatBotsInvoke('bmchat:tgbots:remove', id),
  setPaused: (id: string, paused: boolean) =>
    runtime.bmchatBotsInvoke('bmchat:tgbots:set-paused', { id, paused }),
  setManualReview: (id: string, manualReview: boolean) =>
    runtime.bmchatBotsInvoke('bmchat:tgbots:set-manual-review', {
      id,
      manualReview,
    }),
  setTarget: (id: string, accountId: number, chatId: number) =>
    runtime.bmchatBotsInvoke('bmchat:tgbots:set-target', {
      id,
      accountId,
      chatId,
    }),
  pollNow: (): Promise<{ received: number; published: number; queued: number }> =>
    runtime.bmchatBotsInvoke('bmchat:tgbots:poll-now'),
  pendingList: (botId: string): Promise<PendingItem[]> =>
    runtime.bmchatBotsInvoke('bmchat:tgbots:pending-list', botId),
  pendingPublish: (id: string) =>
    runtime.bmchatBotsInvoke('bmchat:tgbots:pending-publish', id),
  pendingDrop: (id: string) =>
    runtime.bmchatBotsInvoke('bmchat:tgbots:pending-drop', id),
  pendingPublishAll: (botId: string) =>
    runtime.bmchatBotsInvoke('bmchat:tgbots:pending-publish-all', botId),
  pendingClear: (botId: string) =>
    runtime.bmchatBotsInvoke('bmchat:tgbots:pending-clear', botId),
}

export default function TelegramBots({ onClose }: DialogProps) {
  const tx = useTranslationFunction()
  const { openDialog } = useDialog()
  const openConfirmationDialog = useConfirmationDialog()

  const [bots, setBots] = useState<BotPublic[]>([])
  const [token, setToken] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [queueBotId, setQueueBotId] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      setBots(await TG.list())
    } catch (err) {
      log.warn('list bots failed', err)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const pickChat = (
    headerTitle: string,
    onPick: (accountId: number, chatId: number) => void
  ) => {
    openDialog(SelectChat, {
      headerTitle,
      listFlags: C.DC_GCL_NO_SPECIALS,
      enableAccountSwitch: true,
      onChatClick: ({
        targetAccountId,
        chatId,
      }: {
        targetAccountId: number
        chatId: number
      }) => onPick(targetAccountId, chatId),
    })
  }

  const onAddClick = () => {
    const t = token.trim()
    if (!t || busy) return
    if (!/^\d+:[\w-]+$/.test(t)) {
      setError(tx('bmchat_bots_invalid_token'))
      return
    }
    setError(null)
    pickChat(tx('bmchat_bots_pick_target_chat'), async (accountId, chatId) => {
      setBusy(true)
      try {
        const res = await TG.add({ token: t, accountId, chatId })
        if (res?.ok) {
          setToken('')
          await refresh()
        } else {
          setError(
            res?.error === 'already_added'
              ? tx('bmchat_bots_already_added')
              : tx('bmchat_bots_invalid_token')
          )
        }
      } catch (err) {
        setError(tx('bmchat_bots_invalid_token'))
      } finally {
        setBusy(false)
      }
    })
  }

  const onPollNow = async () => {
    setStatus('…')
    try {
      const res = await TG.pollNow()
      setStatus(
        tx('bmchat_bots_poll_result', [
          String(res.received),
          String(res.published),
          String(res.queued),
        ])
      )
      await refresh()
    } catch (err) {
      setStatus(null)
    }
  }

  const onTogglePaused = async (bot: BotPublic) => {
    await TG.setPaused(bot.id, !bot.paused)
    await refresh()
  }
  const onToggleManualReview = async (bot: BotPublic) => {
    await TG.setManualReview(bot.id, !bot.manualReview)
    await refresh()
  }
  const onChangeTarget = (bot: BotPublic) => {
    pickChat(tx('bmchat_bots_pick_target_chat'), async (accountId, chatId) => {
      await TG.setTarget(bot.id, accountId, chatId)
      await refresh()
    })
  }
  const onRemove = async (bot: BotPublic) => {
    const confirmed = await openConfirmationDialog({
      message: tx('bmchat_bots_remove_confirm', bot.displayName),
      confirmLabel: tx('delete'),
      isConfirmDanger: true,
    })
    if (confirmed) {
      await TG.remove(bot.id)
      await refresh()
    }
  }

  if (queueBotId) {
    const bot = bots.find(b => b.id === queueBotId)
    return (
      <PendingQueue
        botId={queueBotId}
        botName={bot?.displayName ?? ''}
        onBack={() => {
          setQueueBotId(null)
          refresh()
        }}
        onClose={onClose}
      />
    )
  }

  return (
    <Dialog onClose={onClose} dataTestid='telegram-bots-dialog'>
      <DialogHeader title={tx('bmchat_bots_title')} />
      <DialogBody>
        <DialogContent>
          <p style={{ marginBottom: 8 }}>{tx('bmchat_bots_explain')}</p>
          <div style={{ display: 'flex', gap: 8, marginBottom: 6 }}>
            <input
              className='search-input'
              style={{ flex: 1 }}
              spellCheck={false}
              placeholder={tx('bmchat_bots_token_hint')}
              value={token}
              onChange={e => {
                setToken(e.target.value)
                if (error) setError(null)
              }}
              data-testid='tgbot-token-input'
            />
            <button
              className='delta-button-round'
              disabled={busy || token.trim().length === 0}
              onClick={onAddClick}
            >
              {tx('bmchat_bots_add')}
            </button>
          </div>
          {error && <p className='input-error'>{error}</p>}

          <div style={{ marginTop: 14 }}>
            {bots.length === 0 ? (
              <p style={{ opacity: 0.7 }}>{tx('bmchat_bots_empty')}</p>
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
                    {bot.displayName}
                    {bot.paused ? ' · ⏸' : ''}
                  </div>
                  {bot.telegramUsername && (
                    <div style={{ fontSize: 12, opacity: 0.7 }}>
                      @{bot.telegramUsername}
                    </div>
                  )}
                  <label
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 6,
                      margin: '8px 0',
                      fontSize: 13,
                    }}
                  >
                    <input
                      type='checkbox'
                      checked={bot.manualReview}
                      onChange={() => onToggleManualReview(bot)}
                    />
                    {tx('bmchat_bots_manual_review')}
                  </label>
                  <div
                    style={{
                      display: 'flex',
                      flexWrap: 'wrap',
                      gap: 6,
                      marginTop: 4,
                    }}
                  >
                    <button
                      className='delta-button-round'
                      onClick={() => onTogglePaused(bot)}
                    >
                      {bot.paused
                        ? tx('bmchat_bots_resume')
                        : tx('bmchat_bots_pause')}
                    </button>
                    <button
                      className='delta-button-round'
                      onClick={() => setQueueBotId(bot.id)}
                    >
                      {tx('bmchat_bots_open_queue')} ({bot.pendingCount})
                    </button>
                    <button
                      className='delta-button-round'
                      onClick={() => onChangeTarget(bot)}
                    >
                      {tx('bmchat_bots_change_chat')}
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
          </div>
          {status && (
            <p style={{ marginTop: 8, fontSize: 12, opacity: 0.8 }}>{status}</p>
          )}
        </DialogContent>
      </DialogBody>
      <DialogFooter>
        <FooterActions align='spaceBetween'>
          <FooterActionButton onClick={onPollNow} type='button'>
            {tx('bmchat_bots_check_now')}
          </FooterActionButton>
          <FooterActionButton onClick={onClose} type='button' styling='primary'>
            {tx('close')}
          </FooterActionButton>
        </FooterActions>
      </DialogFooter>
    </Dialog>
  )
}

function PendingQueue({
  botId,
  botName,
  onBack,
  onClose,
}: {
  botId: string
  botName: string
  onBack: () => void
  onClose: DialogProps['onClose']
}) {
  const tx = useTranslationFunction()
  const [items, setItems] = useState<PendingItem[]>([])

  const refresh = useCallback(async () => {
    try {
      setItems(await TG.pendingList(botId))
    } catch (err) {
      log.warn('pending list failed', err)
    }
  }, [botId])

  useEffect(() => {
    refresh()
  }, [refresh])

  return (
    <Dialog onClose={onClose} dataTestid='telegram-bots-queue-dialog'>
      <DialogHeader title={`${tx('bmchat_bots_open_queue')} · ${botName}`} />
      <DialogBody>
        <DialogContent>
          {items.length === 0 ? (
            <p style={{ opacity: 0.7 }}>{tx('bmchat_bots_queue_empty')}</p>
          ) : (
            items.map(item => (
              <div
                key={item.id}
                style={{
                  border: '1px solid var(--separatorColor, #ddd)',
                  borderRadius: 8,
                  padding: '8px 10px',
                  marginBottom: 8,
                }}
              >
                <div style={{ fontSize: 13, marginBottom: 6 }}>
                  {item.preview}
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button
                    className='delta-button-round'
                    onClick={async () => {
                      await TG.pendingPublish(item.id)
                      await refresh()
                    }}
                  >
                    {tx('bmchat_bots_queue_publish')}
                  </button>
                  <button
                    className='delta-button-round'
                    style={{ color: 'var(--colorDanger, #d9534f)' }}
                    onClick={async () => {
                      await TG.pendingDrop(item.id)
                      await refresh()
                    }}
                  >
                    {tx('bmchat_bots_queue_drop')}
                  </button>
                </div>
              </div>
            ))
          )}
        </DialogContent>
      </DialogBody>
      <DialogFooter>
        <FooterActions align='spaceBetween'>
          <FooterActionButton onClick={onBack} type='button'>
            {tx('back')}
          </FooterActionButton>
          <div style={{ display: 'flex', gap: 6 }}>
            <FooterActionButton
              type='button'
              onClick={async () => {
                await TG.pendingPublishAll(botId)
                await refresh()
              }}
            >
              {tx('bmchat_bots_queue_publish_all')}
            </FooterActionButton>
            <FooterActionButton
              type='button'
              onClick={async () => {
                await TG.pendingClear(botId)
                await refresh()
              }}
            >
              {tx('bmchat_bots_queue_clear')}
            </FooterActionButton>
          </div>
        </FooterActions>
      </DialogFooter>
    </Dialog>
  )
}
