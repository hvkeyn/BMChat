// BMChat 2.49.87 (Phase 6 шаг 2, port of Android Phase 2): persistent mini-player that floats
// above the chat content while a voice / audio message is playing. The Android version
// (`BMChatMiniPlayerView`) uses Media3 + MediaSessionService; here we hook the existing
// `MediaPlayerMutexContext` so the bar reflects whichever <audio> the user kicked off in any
// conversation and lets them pause / resume / change speed / close without scrolling back to
// the original message bubble.

import React, { useContext, useEffect, useMemo, useRef, useState } from 'react'

import { MediaPlayerMutexContext } from '../../contexts/MediaPlayerMutexContext'
import styles from './styles.module.scss'

const SPEEDS = [1, 1.5, 2] as const
const STORAGE_KEY_SPEED = 'bmchat.miniplayer.speed.v1'

function formatTime(seconds: number): string {
  if (!isFinite(seconds) || seconds < 0) return '0:00'
  const total = Math.floor(seconds)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${s.toString().padStart(2, '0')}`
}

function readPersistedSpeed(): (typeof SPEEDS)[number] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY_SPEED)
    const parsed = raw == null ? NaN : Number(raw)
    if (SPEEDS.includes(parsed as any)) {
      return parsed as (typeof SPEEDS)[number]
    }
  } catch {
    // ignore
  }
  return 1
}

function fileNameFromSrc(src: string | null): string {
  if (!src) return ''
  try {
    const url = new URL(src, window.location.href)
    const last = decodeURIComponent(url.pathname.split('/').pop() || '')
    return last || src
  } catch {
    const last = src.split(/[\\/]/).pop() || src
    return last
  }
}

export function BMChatMiniPlayer() {
  const ctx = useContext(MediaPlayerMutexContext)
  const audio = ctx.audioElement
  const [currentSrc, setCurrentSrc] = useState<string | null>(ctx.currentSrc)
  const [isPlaying, setIsPlaying] = useState(!audio.paused)
  const [currentTime, setCurrentTime] = useState(audio.currentTime || 0)
  const [duration, setDuration] = useState(audio.duration || 0)
  const [speed, setSpeed] = useState<(typeof SPEEDS)[number]>(readPersistedSpeed())
  const seekingRef = useRef(false)

  useEffect(() => {
    setCurrentSrc(ctx.currentSrc)
  }, [ctx.currentSrc])

  useEffect(() => {
    const onPlay = () => setIsPlaying(true)
    const onPause = () => setIsPlaying(false)
    const onTimeUpdate = () => {
      if (!seekingRef.current) setCurrentTime(audio.currentTime || 0)
    }
    const onDurationChange = () => setDuration(audio.duration || 0)
    const onEnded = () => setIsPlaying(false)
    audio.addEventListener('play', onPlay)
    audio.addEventListener('pause', onPause)
    audio.addEventListener('timeupdate', onTimeUpdate)
    audio.addEventListener('durationchange', onDurationChange)
    audio.addEventListener('ended', onEnded)
    return () => {
      audio.removeEventListener('play', onPlay)
      audio.removeEventListener('pause', onPause)
      audio.removeEventListener('timeupdate', onTimeUpdate)
      audio.removeEventListener('durationchange', onDurationChange)
      audio.removeEventListener('ended', onEnded)
    }
  }, [audio])

  // Apply persisted speed any time the source changes (new track = new <audio> internally on
  // many sites, but here the global audio element survives, so we restore the rate manually).
  useEffect(() => {
    audio.playbackRate = speed
  }, [audio, speed, currentSrc])

  // Persist speed selection across reloads.
  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY_SPEED, String(speed))
    } catch {
      // ignore
    }
  }, [speed])

  const displayName = useMemo(() => fileNameFromSrc(currentSrc), [currentSrc])

  if (!currentSrc) return null

  const togglePlay = () => {
    if (audio.paused) {
      void audio.play()
    } else {
      audio.pause()
    }
  }

  const cycleSpeed = () => {
    const idx = SPEEDS.indexOf(speed)
    const next = SPEEDS[(idx + 1) % SPEEDS.length]
    setSpeed(next)
  }

  const stop = () => {
    audio.pause()
    audio.src = ''
    ctx.stop()
  }

  const onSeekStart = () => {
    seekingRef.current = true
  }
  const onSeek = (e: React.ChangeEvent<HTMLInputElement>) => {
    const t = Number(e.target.value)
    if (!isFinite(t)) return
    setCurrentTime(t)
  }
  const onSeekCommit = (e: React.MouseEvent | React.TouchEvent | React.KeyboardEvent) => {
    seekingRef.current = false
    const target = e.target as HTMLInputElement
    const t = Number(target.value)
    if (isFinite(t)) audio.currentTime = t
  }

  return (
    <div className={styles.miniplayer} role='complementary' aria-label='BMChat audio player'>
      <button
        type='button'
        className={styles.playPause}
        onClick={togglePlay}
        aria-label={isPlaying ? 'Pause' : 'Play'}
      >
        {isPlaying ? '❚❚' : '▶'}
      </button>
      <div className={styles.body}>
        <div className={styles.title} title={displayName}>
          {displayName}
        </div>
        <div className={styles.row}>
          <span className={styles.time}>{formatTime(currentTime)}</span>
          <input
            type='range'
            className={styles.seek}
            min={0}
            max={duration > 0 ? duration : 0}
            step={0.1}
            value={currentTime}
            onMouseDown={onSeekStart}
            onTouchStart={onSeekStart}
            onChange={onSeek}
            onMouseUp={onSeekCommit}
            onTouchEnd={onSeekCommit}
            onKeyUp={onSeekCommit}
          />
          <span className={styles.time}>{formatTime(duration)}</span>
        </div>
      </div>
      <button
        type='button'
        className={styles.speed}
        onClick={cycleSpeed}
        aria-label={`Playback speed ${speed}x`}
      >
        {speed}x
      </button>
      <button
        type='button'
        className={styles.close}
        onClick={stop}
        aria-label='Close player'
      >
        ✕
      </button>
    </div>
  )
}
