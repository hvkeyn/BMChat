import React, { useCallback, useEffect, useState } from 'react'
import { DialogProps } from '../../../contexts/DialogContext'
import Dialog, {
  DialogBody,
  DialogHeader,
  DialogFooter,
  FooterActions,
  FooterActionButton,
  DialogContent,
} from '../../Dialog'
import { BackendRemote, onDCEvent } from '../../../backend-com'

import useTranslationFunction from '../../../hooks/useTranslationFunction'
import useAlertDialog from '../../../hooks/dialog/useAlertDialog'
import EditAccountAndPasswordDialog from '../EditAccountAndPasswordDialog'
import Button from '../../Button'
import { runtime } from '@deltachat-desktop/runtime-interface'

import styles from './styles.module.scss'

import { TransportListEntry } from '@deltachat/jsonrpc-client/dist/generated/types'
import classNames from 'classnames'
import useDialog from '../../../hooks/dialog/useDialog'

/**
 * Dialog for transports configuration
 */
export default function TransportsDialog(
  props: DialogProps & {
    accountId: number
    newTransport?: string
  }
) {
  const tx = useTranslationFunction()
  const openAlertDialog = useAlertDialog()
  const { accountId, onClose } = props
  // used in  new transport form
  const [transports, setTransports] = useState<
    (TransportListEntry & { isDefault: boolean })[]
  >([])

  const getTransports = useCallback(() => {
    const fetchTransports = async () => {
      const configuredAddress = await BackendRemote.rpc.getConfig(
        accountId,
        'configured_addr'
      )
      const transports = await BackendRemote.rpc.listTransportsEx(accountId)
      setTransports(
        transports.map(t => ({
          ...t,
          isDefault: t.param.addr === configuredAddress,
        }))
      )
    }

    fetchTransports()
  }, [accountId])

  const { openDialog } = useDialog()

  useEffect(() => {
    return onDCEvent(accountId, 'TransportsModified', () => {
      getTransports()
    })
  }, [accountId, getTransports])

  const changeDefaultTransport = useCallback(
    async (transport: TransportListEntry) => {
      // optimistically update UI
      setTransports(prev =>
        prev.map(t => ({
          ...t,
          isDefault: t.param.addr === transport.param.addr,
        }))
      )
      await BackendRemote.rpc.setConfig(
        accountId,
        'configured_addr',
        transport.param.addr
      )
      // now load transports again to be sure
      getTransports()
    },
    [accountId, getTransports]
  )

  const openAddForm = useCallback(() => {
    openDialog(EditAccountAndPasswordDialog, { adding: true })
  }, [openDialog])

  const exportConfig = useCallback(async () => {
    try {
      const mailboxes = await BackendRemote.rpc.listTransports(accountId)
      const json = JSON.stringify(
        { bmchatMailboxConfig: 1, mailboxes },
        null,
        2
      )
      const tempPath = await runtime.writeTempFile(
        'bmchat-mailboxes.json',
        json
      )
      const saved = await runtime.downloadFile(tempPath, 'bmchat-mailboxes.json')
      if (!saved) {
        return
      }
      window.__userFeedback?.({
        type: 'success',
        text: tx('bmchat_mailbox_config_exported'),
      })
    } catch (error) {
      openAlertDialog({
        message: (error as Error).message || String(error),
      })
    }
  }, [accountId, openAlertDialog, tx])

  const importConfig = useCallback(async () => {
    const text = await pickMailboxConfigText()
    if (text == null) {
      return
    }
    let mailboxes: unknown
    try {
      const parsed = JSON.parse(text) as {
        bmchatMailboxConfig?: number
        mailboxes?: unknown
      }
      if (parsed.bmchatMailboxConfig !== 1 || !Array.isArray(parsed.mailboxes)) {
        throw new Error(tx('bmchat_mailbox_config_invalid'))
      }
      mailboxes = parsed.mailboxes
    } catch {
      openAlertDialog({ message: tx('bmchat_mailbox_config_invalid') })
      return
    }
    const entries = mailboxes as { addr?: string; password?: string }[]
    if (entries.length === 0) {
      openAlertDialog({ message: tx('bmchat_mailbox_config_empty') })
      return
    }
    let imported = 0
    try {
      for (const mailbox of entries) {
        if (!mailbox.addr || !mailbox.password) {
          throw new Error(tx('bmchat_mailbox_config_invalid'))
        }
        await BackendRemote.rpc.addOrUpdateTransport(
          accountId,
          mailbox as Parameters<
            typeof BackendRemote.rpc.addOrUpdateTransport
          >[1]
        )
        imported += 1
      }
      window.__userFeedback?.({
        type: 'success',
        text: tx('bmchat_mailbox_config_imported', String(imported)),
      })
    } catch (error) {
      openAlertDialog({
        message: (error as Error).message || String(error),
      })
    }
    getTransports()
  }, [accountId, getTransports, openAlertDialog, tx])

  useEffect(() => {
    getTransports()
  }, [getTransports])

  const deleteTransport = useCallback(
    (transport: TransportListEntry) => {
      openDialog(RemoveOrHideTransportDialog, {
        accountId,
        transport,
        onAction: () => getTransports(),
      })
    },
    [accountId, getTransports, openDialog]
  )

  const editTransport = useCallback(
    (transport: TransportListEntry) => {
      openDialog(EditAccountAndPasswordDialog, {
        addr: transport.param.addr,
      })
    },
    [openDialog]
  )

  return (
    <Dialog
      fixed
      onClose={onClose}
      width={500}
      dataTestid='transports-dialog'
      canOutsideClickClose={true}
    >
      <DialogHeader
        title={tx('transports')}
        onClose={onClose}
        dataTestid='transports-settings'
      />
      <DialogBody className={styles.transportDialogBody}>
        <div className={styles.container}>
          <div className={styles.transportList}>
            {transports.map((transport, index) => (
              <div className={styles.transportRow} key={transport.param.addr}>
                <div
                  onClick={() => changeDefaultTransport(transport)}
                  className={styles.transportItem}
                >
                  <span className={styles.transportRadioButton}>
                    <input
                      id={`transport-${index}`}
                      name='transport-selection'
                      type='radio'
                      value={transport.param.addr}
                      checked={transport.isDefault}
                      className={styles.radioButton}
                      aria-labelledby={`transport-label-${index}`}
                      readOnly
                    />
                  </span>
                  <label id={`transport-label-${index}`}>
                    <strong>{transport.param.addr.split('@')[1]}</strong>
                    <br />
                    {transport.param.addr.split('@')[0]}
                    {transport.isDefault ? (
                      <>
                        {' · '}
                        {tx('used_for_sending')}
                      </>
                    ) : (
                      <>
                        {' · '}
                        {tx('bmchat_mailbox_backup')}
                      </>
                    )}
                    {transport.isUnpublished && (
                      <>
                        {' · '}
                        {tx('hidden_from_contacts')}
                      </>
                    )}
                  </label>
                </div>
                <div>
                  <Button
                    onClick={() => editTransport(transport)}
                    aria-label={`${tx('edit_transport')}`}
                    title={tx('edit_transport')}
                    styling='borderless'
                    className={styles.editButton}
                  >
                    <i
                      className={classNames(
                        'material-svg-icon',
                        'material-icon-edit',
                        styles.edit
                      )}
                      aria-hidden='true'
                    />
                  </Button>
                  {!transport.isDefault && (
                    <Button
                      onClick={() => deleteTransport(transport)}
                      aria-label={`${tx('delete')}`}
                      title={tx('delete')}
                      styling='borderless'
                      className={styles.deleteButton}
                    >
                      <i
                        className={classNames(
                          'material-svg-icon',
                          'material-icon-trash',
                          styles.trash
                        )}
                        aria-hidden='true'
                      />
                    </Button>
                  )}
                </div>
              </div>
            ))}
          </div>
          <p>{tx('transport_list_hint')}</p>
        </div>
      </DialogBody>
      <DialogFooter>
        <div className={styles.footerButtons}>
          <Button
            className={styles.addTransportButton}
            styling='primary'
            onClick={openAddForm}
            aria-label={tx('add_transport')}
            title={tx('add_transport')}
          >
            {tx('add_transport')}
          </Button>
          <Button
            className={styles.addTransportButton}
            onClick={exportConfig}
            aria-label={tx('bmchat_mailbox_config_export')}
          >
            {tx('bmchat_mailbox_config_export')}
          </Button>
          <Button
            className={styles.addTransportButton}
            onClick={importConfig}
            aria-label={tx('bmchat_mailbox_config_import')}
          >
            {tx('bmchat_mailbox_config_import')}
          </Button>
        </div>
      </DialogFooter>
    </Dialog>
  )
}

/**
 * Dialog shown when removing a transport,
 * offering to hide it from contacts as an alternative.
 */
function RemoveOrHideTransportDialog(
  props: DialogProps & {
    accountId: number
    transport: TransportListEntry
    onAction: () => void
  }
) {
  const tx = useTranslationFunction()
  const { onClose, accountId, transport, onAction } = props

  const hideFromContacts = useCallback(async () => {
    await BackendRemote.rpc.setTransportUnpublished(
      accountId,
      transport.param.addr,
      true
    )
    onAction()
    onClose()
  }, [accountId, transport.param.addr, onAction, onClose])

  const removeTransport = useCallback(async () => {
    await BackendRemote.rpc.deleteTransport(accountId, transport.param.addr)
    onAction()
    onClose()
  }, [accountId, transport.param.addr, onAction, onClose])

  return (
    <Dialog onClose={onClose}>
      <DialogHeader title={tx('remove_transport')} onClose={onClose} />
      <DialogBody>
        <DialogContent>
          <p style={{ whiteSpace: 'pre-line' }}>
            {tx('confirm_remove_or_hide_transport_x', transport.param.addr)}
          </p>
        </DialogContent>
      </DialogBody>
      <DialogFooter>
        <FooterActions align='spaceBetween'>
          <FooterActionButton onClick={onClose}>
            {tx('cancel')}
          </FooterActionButton>
          <FooterActionButton onClick={hideFromContacts}>
            {tx('hide_from_contacts')}
          </FooterActionButton>
          <FooterActionButton styling='danger' onClick={removeTransport}>
            {tx('remove_transport')}
          </FooterActionButton>
        </FooterActions>
      </DialogFooter>
    </Dialog>
  )
}

function pickMailboxConfigText(): Promise<string | null> {
  return new Promise(resolve => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.json,application/json'
    input.onchange = () => {
      const file = input.files?.[0]
      if (!file) {
        resolve(null)
        return
      }
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result ?? ''))
      reader.onerror = () => resolve(null)
      reader.readAsText(file)
    }
    input.addEventListener('cancel', () => resolve(null))
    input.click()
  })
}
