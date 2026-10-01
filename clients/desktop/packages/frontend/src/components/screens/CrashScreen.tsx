import React, { PropsWithChildren } from 'react'

import { runtime } from '@deltachat-desktop/runtime-interface'
import { getLogger } from '../../../../shared/logger'
import { DialogContext } from '../../contexts/DialogContext'
import type { TranslationKey } from '@deltachat-desktop/shared/translationKeyType'
import type { getMessageFunction } from '@deltachat-desktop/shared/localize'

const log = getLogger('renderer/react-crashhandler')

// The crash screen can render before App has loaded the locale data.
function crashText(
  key: TranslationKey,
  fallback: string,
  substitutions?: string | string[]
): string {
  const translate = (
    window as unknown as { static_translate?: getMessageFunction }
  ).static_translate
  if (typeof translate !== 'function') {
    return fallback
  }
  return translate(key, substitutions)
}

interface CrashScreenState {
  hasError: boolean
  error: string
}

export class CrashScreen extends React.Component<
  PropsWithChildren<{}>,
  CrashScreenState
> {
  state = {
    hasError: false,
    error: '',
  }

  componentDidCatch(error: object | Error) {
    log.error('The app encountered an react error', error)
    this.setState({
      hasError: true,
      error: this.errorToText(error),
    })
  }

  // FYI we also have `unknownErrorToString`.
  errorToText(error: object | Error) {
    if (error instanceof Error) {
      // TODO parse the stack and map the sourcemap to provide a useful stacktrace
      return (error.stack || '[no stack trace provided]')
        .replace(/file:\/\/\/[\w\W]+?\/html-dist\//g, '') // for development
        .replace(/\(file:\/\/.*?app.asar./g, '(') // for production (packaged)
    } else {
      return JSON.stringify(error)
    }
  }

  render() {
    if (this.state.hasError) {
      const { VERSION, GIT_REF } = runtime.getRuntimeInfo().buildInfo
      return (
        <div className='crash-screen'>
          <h1>{crashText('bmchat_crash_title', 'Oops, something crashed')}</h1>
          <h2>
            {crashText(
              'bmchat_crash_explain',
              'Please restart BMChat. If the problem persists, please report it to the developers:'
            )}{' '}
            (
            <a
              href='#'
              onClick={_ =>
                runtime.openLink('https://github.com/hvkeyn/BMChat/issues')
              }
            >
              github.com/hvkeyn/BMChat/issues
            </a>
            )
          </h2>
          <p>
            <button type='button' onClick={_ => runtime.reloadWebContent()}>
              {crashText('bmchat_crash_reload', 'Reload')}
            </button>
            <button type='button' onClick={_ => runtime.openLogFile()}>
              {crashText(
                'menu.view.developer.open.current.log.file',
                'Open Current Logfile'
              )}
            </button>
          </p>
          <p>
            <pre className='error-details'>{this.state.error}</pre>
          </p>
          <p>
            {crashText('bmchat_crash_full_log', 'Full log:')}{' '}
            <a href='#' onClick={_ => runtime.openLogFile()}>
              {runtime.getCurrentLogLocation()}
            </a>
          </p>
          <p>
            {crashText(
              'bmchat_crash_version',
              `BMChat version: ${VERSION} (git: ${GIT_REF})`,
              [VERSION, GIT_REF]
            )}
          </p>
        </div>
      )
    } else {
      return this.props.children
    }
  }
}

CrashScreen.contextType = DialogContext
