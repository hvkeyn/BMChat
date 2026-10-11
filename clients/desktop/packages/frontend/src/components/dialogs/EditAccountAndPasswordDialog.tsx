import React, { useState, useEffect, useCallback } from 'react'

import { BackendRemote } from '../../backend-com'
import LoginForm from '../LoginForm'
import { defaultCredentials, Credentials } from '../Settings/DefaultCredentials'
import { ConfigureProgressDialog } from './ConfigureProgressDialog'
import Dialog, {
  DialogBody,
  DialogContent,
  DialogHeader,
  OkCancelFooterAction,
} from '../Dialog'
import useTranslationFunction from '../../hooks/useTranslationFunction'
import useDialog from '../../hooks/dialog/useDialog'

import type { DialogProps } from '../../contexts/DialogContext'
import AlertDialog from './AlertDialog'
import { T } from '@deltachat/jsonrpc-client'
import { useSettingsStore } from '../../stores/settings'
import { getLogger } from '@deltachat-desktop/shared/logger'

type AccountAndPasswordDialogProps = DialogProps & {
  addr?: string
  /** Empty form that adds another mailbox on the current account. */
  adding?: boolean
}

const log = getLogger('renderer/EditAccountAndPasswordDialog')

/**
 * uses a prefilled LoginForm with existing
 * credentials to edit transport settings
 */
export default function EditAccountAndPasswordDialog({
  onClose,
  addr,
  adding,
}: AccountAndPasswordDialogProps) {
  const tx = useTranslationFunction()

  const settingsStore = useSettingsStore()[0]
  const isChatmail = settingsStore?.settings.is_chatmail === '1'

  return (
    <Dialog canOutsideClickClose={false} onClose={onClose}>
      <DialogHeader
        title={
          adding
            ? tx('add_transport')
            : isChatmail
              ? tx('edit_transport')
              : tx('manual_account_setup_option')
        }
      />
      <EditAccountInner {...{ onClose, addr, adding }} />
    </Dialog>
  )
}

function EditAccountInner({
  onClose,
  addr,
  adding,
}: {
  onClose: DialogProps['onClose']
  addr?: string
  adding?: boolean
}) {
  const [initialSettings, setInitialAccountSettings] =
    useState<Credentials>(defaultCredentials())

  const [accountSettings, setAccountSettings] =
    useState<Credentials>(defaultCredentials())

  const { openDialog } = useDialog()
  const tx = useTranslationFunction()

  useEffect(() => {
    let cancelled = false

    const loadSettings = async () => {
      if (adding) {
        return
      }
      if (window.__selectedAccountId === undefined) {
        throw new Error('can not load settings when no account is selected')
      }
      const accountId = window.__selectedAccountId
      const transports = await BackendRemote.rpc.listTransports(accountId)
      if (cancelled) return
      if (transports.length === 0) {
        throw new Error('no transport found')
      }
      const configuredAddress =
        addr ||
        (await BackendRemote.rpc.getConfig(accountId, 'configured_addr'))
      if (cancelled) return
      const accountSettings: T.EnteredLoginParam | undefined = transports.find(
        t => t.addr === configuredAddress
      )

      if (!accountSettings) {
        throw new Error('configured transport not found in transport list')
      }

      setInitialAccountSettings(accountSettings)
      setAccountSettings(accountSettings)
    }

    loadSettings().catch(error => {
      log.error('Failed to load settings:', error)
    })

    return () => {
      cancelled = true
    }
  }, [addr, adding])

  const onUpdate = useCallback(async () => {
    const onSuccess = () => onClose()

    const update = () => {
      openDialog(ConfigureProgressDialog, {
        credentials: accountSettings,
        onSuccess,
        onFail: error => {
          openDialog(AlertDialog, { message: error })
        },
      })
    }

    if (!adding && initialSettings.addr !== accountSettings.addr) {
      log.error('changing email addres of transport is not allowed')
      return
    }
    if (adding && (!accountSettings.addr || !accountSettings.password)) {
      openDialog(AlertDialog, { message: tx('login_error_required_fields') })
      return
    }
    update()
  }, [accountSettings, adding, initialSettings, onClose, openDialog, tx])

  const onOk = useCallback(async () => {
    await onUpdate()
  }, [onUpdate])

  if (accountSettings === null) return null
  return (
    <>
      <DialogBody>
        <DialogContent>
          {accountSettings && (
            <LoginForm
              credentials={accountSettings}
              setCredentials={setAccountSettings}
              isEdit={adding ? undefined : true}
            />
          )}
        </DialogContent>
      </DialogBody>
      <OkCancelFooterAction
        onCancel={() => onClose()}
        onOk={onOk}
        confirmLabel={tx('login_title')}
      />
    </>
  )
}
