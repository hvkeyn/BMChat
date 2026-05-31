import React, { useContext, useState } from 'react'

import Dialog, {
  DialogBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  FooterActionButton,
  FooterActions,
} from '../Dialog'
import useTranslationFunction from '../../hooks/useTranslationFunction'
import useCreateChatByEmail from '../../hooks/chat/useCreateChatByEmail'
import useChat from '../../hooks/chat/useChat'
import { BackendRemote } from '../../backend-com'
import { selectedAccountId } from '../../ScreenController'
import { ScreenContext } from '../../contexts/ScreenContext'
import { getLogger } from '../../../../shared/logger'

import type { DialogProps } from '../../contexts/DialogContext'

const log = getLogger('renderer/dialogs/InviteByEmail')

// Simple, permissive email check (the core does the real validation).
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

type Props = {
  /** The canonical BMChat invite link to send to the invited person. */
  inviteLink: string
}

/**
 * Lets the user invite somebody by typing an email address. We create (or
 * reuse) a chat with that address and send the invitation link as the first
 * message, so the recipient can add the inviter with a single click.
 */
export default function InviteByEmailDialog({
  inviteLink,
  onClose,
}: Props & DialogProps) {
  const tx = useTranslationFunction()
  const accountId = selectedAccountId()
  const createChatByEmail = useCreateChatByEmail()
  const { selectChat } = useChat()
  const { userFeedback } = useContext(ScreenContext)

  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const isValid = EMAIL_RE.test(email.trim())

  const onSubmit = async (ev: React.FormEvent) => {
    ev.preventDefault()
    const address = email.trim()
    if (!EMAIL_RE.test(address) || busy) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      const chatId = await createChatByEmail(accountId, address)
      if (!chatId) {
        // user cancelled the confirmation dialog
        setBusy(false)
        return
      }
      await BackendRemote.rpc.sendMsg(accountId, chatId, {
        text: inviteLink,
      })
      userFeedback({
        type: 'success',
        text: tx('bmchat_invite_by_email_sent'),
      })
      onClose()
      selectChat(accountId, chatId)
    } catch (err) {
      log.error('invite by email failed', err)
      setError(err instanceof Error ? err.message : String(err))
      setBusy(false)
    }
  }

  return (
    <Dialog onClose={onClose} dataTestid='invite-by-email-dialog'>
      <DialogHeader title={tx('bmchat_invite_by_email')} />
      <form onSubmit={onSubmit}>
        <DialogBody>
          <DialogContent>
            <p style={{ marginBottom: '12px' }}>
              {tx('bmchat_invite_by_email_explain')}
            </p>
            <input
              className='search-input'
              style={{ width: '100%' }}
              type='email'
              autoFocus
              spellCheck={false}
              placeholder={tx('email_address')}
              value={email}
              onChange={e => {
                setEmail(e.target.value)
                if (error) setError(null)
              }}
              data-testid='invite-by-email-input'
            />
            {error && <p className='input-error'>{error}</p>}
          </DialogContent>
        </DialogBody>
        <DialogFooter>
          <FooterActions align='spaceBetween'>
            <FooterActionButton onClick={onClose} type='button'>
              {tx('cancel')}
            </FooterActionButton>
            <FooterActionButton
              type='submit'
              styling='primary'
              disabled={!isValid || busy}
              data-testid='invite-by-email-send'
            >
              {tx('perm_continue')}
            </FooterActionButton>
          </FooterActions>
        </DialogFooter>
      </form>
    </Dialog>
  )
}
