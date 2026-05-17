import React, { useMemo, useState } from 'react'

import Dialog, {
  DialogBody,
  DialogContent,
  DialogFooter,
  FooterActionButton,
  FooterActions,
  DialogHeader,
} from '../Dialog'
import useTranslationFunction from '../../hooks/useTranslationFunction'

import type { DialogProps } from '../../contexts/DialogContext'

// BMChat 2.49.84 (Phase 6, port of Android Phase 4B): user-facing date+time picker for
// scheduling a message. We deliberately use the browser's native datetime-local input
// rather than a fancy picker — it works in Electron renderer without extra dependencies
// and matches the look of every other native input in the desktop client.

export type Props = {
  defaultMs?: number
  onConfirm: (epochMillis: number) => void
} & DialogProps

function formatForInput(ms: number): string {
  const d = new Date(ms)
  const pad = (n: number) => n.toString().padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}`
  )
}

export default function ScheduleMessageDialog({
  onClose,
  defaultMs,
  onConfirm,
}: Props) {
  const tx = useTranslationFunction()

  const initial = useMemo(() => {
    const base = defaultMs ?? Date.now() + 60 * 60 * 1000
    return formatForInput(base)
  }, [defaultMs])

  const [value, setValue] = useState(initial)
  const [error, setError] = useState<string | null>(null)

  const submit = () => {
    if (!value) {
      setError(tx('bmchat_schedule_pick_time'))
      return
    }
    const parsed = new Date(value).getTime()
    if (Number.isNaN(parsed)) {
      setError(tx('bmchat_schedule_pick_time'))
      return
    }
    if (parsed <= Date.now()) {
      setError(tx('bmchat_schedule_in_the_past'))
      return
    }
    onConfirm(parsed)
    onClose()
  }

  return (
    <Dialog onClose={onClose} width={420}>
      <DialogHeader title={tx('bmchat_schedule_message')} />
      <DialogBody>
        <DialogContent>
          <p>{tx('bmchat_schedule_pick_time')}</p>
          <input
            type='datetime-local'
            value={value}
            onChange={e => {
              setValue(e.currentTarget.value)
              setError(null)
            }}
            style={{ width: '100%', padding: '6px 8px', marginTop: 8 }}
          />
          {error && (
            <p style={{ color: 'var(--colorDanger, #d33)', marginTop: 8 }}>
              {error}
            </p>
          )}
        </DialogContent>
      </DialogBody>
      <DialogFooter>
        <FooterActions>
          <FooterActionButton onClick={onClose}>
            {tx('cancel')}
          </FooterActionButton>
          <FooterActionButton onClick={submit}>{tx('ok')}</FooterActionButton>
        </FooterActions>
      </DialogFooter>
    </Dialog>
  )
}
