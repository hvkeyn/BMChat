import React, { useEffect, useState, useCallback } from 'react'

import type { SettingsStoreState } from '../../stores/settings'
import { ExperimentalFeatures } from './ExperimentalFeatures'
import ImapFolderHandling from './ImapFolderHandling'
import SettingsHeading from './SettingsHeading'
import SettingsSeparator from './SettingsSeparator'
import useTranslationFunction from '../../hooks/useTranslationFunction'
import useDialog from '../../hooks/dialog/useDialog'
import { confirmDialog } from '../message/messageFunctions'
import SettingsButton from './SettingsButton'
import { runtime } from '@deltachat-desktop/runtime-interface'
import CoreSettingsSwitch from './CoreSettingsSwitch'
import DesktopSettingsSwitch from './DesktopSettingsSwitch'
import { AutostartState } from '@deltachat-desktop/shared/shared-types'
import ProxyConfiguration from '../dialogs/ProxyConfiguration'
import { selectedAccountId } from '../../ScreenController'
import TransportsDialog from '../dialogs/Transports'
import { LogDialog } from '../dialogs/Log'
import TelegramBots from '../dialogs/TelegramBots'
import EmailBots from '../dialogs/EmailBots'
import { DialogProps } from '../../contexts/DialogContext'
import { getLogger } from '../../../../shared/logger'

type Props = {
  settingsStore: SettingsStoreState
  onClose: DialogProps['onClose']
}

const log = getLogger('renderer/settings/advanced')

export default function Advanced({ onClose, settingsStore }: Props) {
  const tx = useTranslationFunction()
  const { openDialog } = useDialog()
  const openProxySettings = () => {
    openDialog(ProxyConfiguration, {
      accountId: selectedAccountId(),
      configured: true,
    })
  }
  const confirmDisableMultiDevice = async (
    newValue: boolean // (true => multi-device enabled)
  ): Promise<boolean> => {
    // Show a warning when user wants to disable multi-device
    if (!newValue) {
      const confirmed = await confirmDialog(
        openDialog,
        tx('pref_multidevice_change_warn'),
        tx('perm_continue'),
        true
      )
      // If user didn't confirm, return false to prevent the change
      return confirmed === true
    }
    return true
  }

  const openTransportSettings = () => {
    openDialog(TransportsDialog, {
      accountId: selectedAccountId(),
    })
  }

  const viewLog = () => {
    openDialog(LogDialog)
    // Close the settings dialog so that it doesn't interfere with sharing the log.
    onClose()
  }

  const checkForUpdates = async () => {
    try {
      await runtime.bmchatCheckForUpdates()
    } catch (err) {
      log.warn('Manual update check failed', err)
    }
  }

  return (
    <>
      {runtime.getRuntimeInfo().target === 'electron' && (
        <SettingsButton
          onClick={checkForUpdates}
          dataTestid='bmchat-check-for-updates'
        >
          {tx('bmchat_check_for_updates')}
        </SettingsButton>
      )}

      <SettingsButton onClick={() => viewLog()}>
        {tx('pref_view_log')}
      </SettingsButton>

      {runtime.getRuntimeInfo().target === 'electron' && (
        <>
          <SettingsSeparator />
          <SettingsHeading>{tx('bmchat_bots_section')}</SettingsHeading>
          <SettingsButton
            onClick={() => openDialog(TelegramBots)}
            dataTestid='open-telegram-bots'
          >
            {tx('bmchat_bots_title')}
          </SettingsButton>
          <SettingsButton
            onClick={() => openDialog(EmailBots)}
            dataTestid='open-email-bots'
          >
            {tx('bmchat_email_bots_title')}
          </SettingsButton>
        </>
      )}

      <SettingsSeparator />
      <SettingsHeading>{tx('pref_server')}</SettingsHeading>
      <SettingsButton
        onClick={openTransportSettings}
        dataTestid='open-transport-settings'
      >
        {tx('transports')}
      </SettingsButton>
      <SettingsButton
        onClick={() => {
          openProxySettings()
        }}
        dataTestid='open-proxy-settings'
      >
        {tx('proxy_settings')}
      </SettingsButton>
      <CoreSettingsSwitch
        label={tx('pref_multidevice')}
        settingsKey='bcc_self'
        description={tx('pref_multidevice_explain')}
        beforeChange={confirmDisableMultiDevice}
      />

      <SettingsSeparator />

      <SettingsHeading>{tx('pref_experimental_features')}</SettingsHeading>
      <ExperimentalFeatures />

      {runtime.getRuntimeInfo().target !== 'browser' && (
        <>
          <SettingsSeparator />
          <SettingsAutoStart />
        </>
      )}

      {settingsStore.settings.is_chatmail === '0' && (
        <>
          <SettingsSeparator />
          <SettingsHeading>Legacy Options</SettingsHeading>
          <ImapFolderHandling settingsStore={settingsStore} />
        </>
      )}
    </>
  )
}

function SettingsAutoStart() {
  const tx = useTranslationFunction()

  const [autostartState, setAutostartState] = useState<AutostartState | null>(
    null
  )
  const update = useCallback(() => {
    runtime
      .getAutostartState()
      .then(setAutostartState)
      .catch(error => {
        log.warn('Failed to load autostart state', error)
        setAutostartState({ isSupported: false, isRegistered: null })
      })
  }, [])

  useEffect(() => {
    update()
  }, [update])

  return (
    <>
      <DesktopSettingsSwitch
        // force react to rerender element, so that the switch does not animate
        key={String(!autostartState)}
        settingsKey={
          runtime.getRuntimeInfo().target === 'electron'
            ? 'autostartElectron'
            : 'autostart'
        }
        label={tx('pref_autostart')}
        description={
          autostartState
            ? autostartState.isSupported
              ? autostartState.isRegistered
                ? tx('pref_autostart_registered')
                : autostartState.isRegistered == null
                  ? undefined // no description if status can't be determined
                  : tx('pref_autostart_not_registered')
              : tx('pref_autostart_not_supported')
            : undefined // don't show description while it is loading
        }
        disabled={!autostartState?.isSupported}
        disabledValue={false}
        callback={update}
      />
    </>
  )
}
