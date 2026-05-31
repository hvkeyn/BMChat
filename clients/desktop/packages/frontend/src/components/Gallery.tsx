import React, {
  ChangeEvent,
  Component,
  createRef,
  useRef,
  useState,
} from 'react'
import AutoSizer from 'react-virtualized-auto-sizer'
import { FixedSizeGrid, FixedSizeList } from 'react-window'
import moment from 'moment'

import {
  AudioAttachment,
  FileAttachmentRow,
  GalleryAttachmentElementProps,
  ImageAttachment,
  VideoAttachment,
  WebxdcAttachment,
} from './attachment/galleryAttachment'
import { parseTgVideoMarker } from './attachment/tgVideoMarker'
import { getLogger } from '../../../shared/logger'
import { BackendRemote, onDCEvent, Type } from '../backend-com'
import { selectedAccountId } from '../ScreenController'
import SettingsStoreInstance, { SettingsStoreState } from '../stores/settings'
import FullscreenMedia, {
  NeighboringMediaMode,
} from './dialogs/FullscreenMedia'
import { DialogContext } from '../contexts/DialogContext'
import type { getMessageFunction } from '@deltachat-desktop/shared/localize'
import {
  RovingTabindexProvider,
  useRovingTabindex,
} from '../contexts/RovingTabindex'
import InfiniteLoader from 'react-window-infinite-loader'
import { T, C } from '@deltachat/jsonrpc-client'
import { useTranslationWritingDirection } from '../hooks/useTranslationFunction'
import { runtime } from '@deltachat-desktop/runtime-interface'
import useMessage from '../hooks/chat/useMessage'

const log = getLogger('renderer/Gallery')

type MediaTabKey =
  | 'webxdc_apps'
  | 'images'
  | 'video'
  | 'audio'
  | 'files'
  | 'links'

type LinkEntry = {
  key: string
  url: string
  label: string
  msgId: number
  chatId: number
  timestamp: number
  senderName: string
  preview: string
}

type GalleryElement = (
  props: GalleryAttachmentElementProps & {
    openFullscreenMedia: (message: Type.Message) => void
  }
) => React.ReactElement

const MediaTabs: Readonly<{
  [key in Exclude<MediaTabKey, 'links'>]: {
    values: Type.Viewtype[]
    element: GalleryElement
  }
}> = {
  webxdc_apps: {
    values: ['Webxdc'],
    element: WebxdcAttachment,
  },
  images: {
    values: ['Gif', 'Image'],
    element: ImageAttachment,
  },
  video: {
    values: ['Video'],
    element: VideoAttachment,
  },
  audio: {
    values: ['Audio', 'Voice'],
    element: AudioAttachment,
  },
  files: {
    values: ['File'],
    element: FileAttachmentRow,
  },
}

const GalleryTabOrder: readonly MediaTabKey[] = [
  'images',
  'video',
  'audio',
  'files',
  'links',
  'webxdc_apps',
]

const URL_RE =
  /\b((?:https?:\/\/|www\.)[^\s<>()]+|(?:[a-z0-9-]+\.)+[a-z]{2,}(?:\/[^\s<>()]*)?)/gi
const TRAILING_URL_PUNCTUATION = /[.,;:!?)]$/

type Props = { chatId: number | 'all' }

const getCurrentDocumentVerticalScrollbarWidth = () => {
  const outer = document.createElement('div')
  outer.style.height = '10px'
  outer.style.width = '100%'
  outer.style.display = 'hidden'
  outer.style.overflowY = 'scroll'

  document.body.appendChild(outer)
  const inner = document.createElement('div')
  inner.style.height = '100px'
  inner.style.width = '100%'
  outer.appendChild(inner)
  const outerWidth = Number(
    // remove "px" from value
    getComputedStyle(outer).width.replace(/[^\d]+$/g, '')
  )
  const innerWidth = Number(
    // remove "px" from value
    getComputedStyle(inner).width.replace(/[^\d]+$/g, '')
  )
  document.body.removeChild(outer)
  return outerWidth - innerWidth
}

function normalizeLink(raw: string): string {
  let cleaned = raw.trim()
  while (TRAILING_URL_PUNCTUATION.test(cleaned)) {
    cleaned = cleaned.slice(0, -1)
  }
  return /^https?:\/\//i.test(cleaned) ? cleaned : `https://${cleaned}`
}

function extractLinks(message: T.Message): LinkEntry[] {
  const text = message.text || ''
  if (!text) return []
  const out: LinkEntry[] = []
  URL_RE.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = URL_RE.exec(text))) {
    const start = match.index
    if (start > 0 && text[start - 1] === '@') continue
    const label = match[1]
    const url = normalizeLink(label)
    out.push({
      key: `${message.id}:${out.length}:${url}`,
      url,
      label,
      msgId: message.id,
      chatId: message.chatId,
      timestamp: message.timestamp,
      senderName: message.sender.displayName || message.sender.address || '',
      preview: text.replace(/\s+/g, ' ').trim(),
    })
  }
  return out
}

async function getLinkMessageIds(
  accountId: number,
  chatId: number | 'all'
): Promise<number[]> {
  if (chatId !== 'all') {
    return BackendRemote.rpc.getMessageIds(accountId, chatId, false, false)
  }

  const chatIds = await BackendRemote.rpc.getChatlistEntries(
    accountId,
    null,
    null,
    null
  )
  const chunks = await Promise.all(
    chatIds
      .filter(id => id !== C.DC_CHAT_ID_TRASH)
      .map(id =>
        BackendRemote.rpc
          .getMessageIds(accountId, id, false, false)
          .catch(err => {
            log.warn('Failed loading link message ids for chat', id, err)
            return []
          })
      )
  )
  return chunks.flat()
}

async function loadLinkEntries(
  accountId: number,
  chatId: number | 'all'
): Promise<LinkEntry[]> {
  const ids = await getLinkMessageIds(accountId, chatId)
  const uniqueIds = [...new Set(ids)].reverse()
  const links: LinkEntry[] = []
  const chunkSize = 80
  for (let i = 0; i < uniqueIds.length; i += chunkSize) {
    const messages = await BackendRemote.rpc.getMessages(
      accountId,
      uniqueIds.slice(i, i + chunkSize)
    )
    for (const result of Object.values(messages)) {
      if (result?.kind === 'message') {
        links.push(...extractLinks(result))
      }
    }
  }
  return links
}

export default class Gallery extends Component<
  Props,
  {
    currentTab: MediaTabKey
    msgTypes: Type.Viewtype[]
    element: GalleryElement
    mediaMessageIds: number[]
    mediaLoadResult: Record<number, Type.MessageLoadResult>
    linkEntries: LinkEntry[]
    loading: boolean
    queryText: string
    galleryImageKeepAspectRatio?: boolean
  }
> {
  static contextType = DialogContext
  declare context: React.ContextType<typeof DialogContext>

  dateHeader = createRef<HTMLDivElement>()
  tabListRef = createRef<HTMLUListElement>()
  galleryItemsRef = createRef<HTMLDivElement>()
  cleanup: Array<() => void> = []
  scrollbarWidth = getCurrentDocumentVerticalScrollbarWidth()
  constructor(props: Props) {
    super(props)

    this.state = {
      currentTab: 'images',
      msgTypes: MediaTabs.images.values,
      element: ImageAttachment,
      mediaMessageIds: [],
      mediaLoadResult: {},
      linkEntries: [],
      loading: true,
      queryText: '',
      galleryImageKeepAspectRatio: false,
    }

    this.settingsStoreListener = this.settingsStoreListener.bind(this)
  }

  reset() {
    this.setState({
      currentTab: 'images',
      msgTypes: MediaTabs.images.values,
      element: ImageAttachment,
      mediaMessageIds: [],
      mediaLoadResult: {},
      linkEntries: [],
      loading: true,
      queryText: '',
    })
  }

  componentDidMount() {
    this.onSelect(this.state.currentTab)
    SettingsStoreInstance.subscribe(this.settingsStoreListener)
    this.setState({
      galleryImageKeepAspectRatio:
        SettingsStoreInstance.state?.desktopSettings
          .galleryImageKeepAspectRatio,
    })

    // It's possible to delete messages right from the gallery,
    // so let's handle this.
    // If we also want to handle newly arriving messages, `MsgsChanged`
    // is probably the way to go.
    const toCleanup = onDCEvent(
      selectedAccountId(),
      'MsgDeleted',
      ({ chatId, msgId: deletedMsgId }) => {
        if (chatId !== this.props.chatId) {
          return
        }

        // There is not really a point to also delete it from
        // `mediaLoadResult` except for removing it from RAM, but let's do it.
        const newMediaLoadResult = { ...this.state.mediaLoadResult }
        delete newMediaLoadResult[deletedMsgId]

        this.setState({
          mediaMessageIds: this.state.mediaMessageIds.filter(
            id => id !== deletedMsgId
          ),
          mediaLoadResult: newMediaLoadResult,
        })
      }
    )
    this.cleanup.push(toCleanup)
  }

  componentWillUnmount() {
    SettingsStoreInstance.unsubscribe(this.settingsStoreListener)
    this.cleanup.forEach(f => f())
  }

  settingsStoreListener(state: SettingsStoreState | null) {
    if (state) {
      this.setState({
        galleryImageKeepAspectRatio:
          state.desktopSettings.galleryImageKeepAspectRatio,
      })
    }
  }

  componentDidUpdate(prevProps: Props) {
    if (this.props.chatId !== prevProps.chatId) {
      // reset
      this.reset()
      this.onSelect('images')
    }
  }

  onSelect(tab: MediaTabKey) {
    if (!this.props.chatId) {
      throw new Error('chat id missing')
    }
    const accountId = selectedAccountId()
    if (tab === 'links') {
      this.setState({ loading: true })
      loadLinkEntries(accountId, this.props.chatId)
        .then(linkEntries => {
          this.setState({
            currentTab: tab,
            msgTypes: [],
            mediaMessageIds: [],
            mediaLoadResult: {},
            linkEntries,
            loading: false,
          })
        })
        .catch(err => {
          log.error('Failed loading links tab', err)
          this.setState({ loading: false })
        })
      return
    }
    const msgTypes = MediaTabs[tab].values
    const newElement = MediaTabs[tab].element
    const chatId = this.props.chatId !== 'all' ? this.props.chatId : null
    this.setState({ loading: true })

    if (tab === 'images' || tab === 'video') {
      // BMChat Telegram-video posters are DC_MSG_IMAGE messages carrying a
      // `[bmchat:tgvideo]` marker. To match the mobile client we keep them out
      // of "Images" and show them under "Video" instead, which requires
      // inspecting the message bodies.
      ;(async () => {
        try {
          const imageIds = await BackendRemote.rpc.getChatMedia(
            accountId,
            chatId,
            'Image',
            'Gif',
            null
          )
          const videoIds = await BackendRemote.rpc.getChatMedia(
            accountId,
            chatId,
            'Video',
            null,
            null
          )
          const imageMessages = await BackendRemote.rpc.getMessages(
            accountId,
            imageIds
          )
          const isPoster = (id: number) => {
            const m = imageMessages[id]
            return m && m.kind === 'message' && !!parseTgVideoMarker(m.text)
          }
          let ids: number[]
          if (tab === 'images') {
            ids = imageIds.filter(id => !isPoster(id))
          } else {
            ids = [...videoIds, ...imageIds.filter(id => isPoster(id))]
            ids.sort((a, b) => a - b)
          }
          ids.reverse() // newest first
          this.setState({
            currentTab: tab,
            msgTypes,
            element: newElement,
            mediaMessageIds: ids,
            mediaLoadResult: {},
            linkEntries: [],
            loading: false,
          })
          this.forceUpdate()
        } catch (err) {
          log.error('Failed loading media tab', err)
          this.setState({ loading: false })
        }
      })()
      return
    }

    BackendRemote.rpc
      .getChatMedia(accountId, chatId, msgTypes[0], msgTypes[1], null)
      .then(async media_ids => {
        const mediaLoadResult =
          tab !== 'files'
            ? // This state is unused if the tab is not 'files'.
              []
            : await BackendRemote.rpc.getMessages(accountId, media_ids)
        media_ids.reverse() // order newest up - if we need different ordering we need to do it in core
        this.setState({
          currentTab: tab,
          msgTypes,
          element: newElement,
          mediaMessageIds: media_ids,
          mediaLoadResult,
          linkEntries: [],
          loading: false,
        })
        this.forceUpdate()
      })
      .catch(log.error.bind(log))
  }

  onChangeInput(ev: ChangeEvent<HTMLInputElement>) {
    this.setState({ queryText: ev.target.value })
  }

  emptyTabMessage(tab: MediaTabKey): string {
    const allMedia = this.props.chatId === 'all'
    const tx = window.static_translate // static because dynamic isn't too important here
    switch (tab) {
      case 'images':
        return allMedia
          ? tx('tab_all_media_empty_hint')
          : tx('tab_image_empty_hint')
      case 'video':
        return allMedia
          ? tx('tab_all_media_empty_hint')
          : tx('tab_video_empty_hint')
      case 'audio':
        return allMedia
          ? tx('tab_all_media_empty_hint')
          : tx('tab_audio_empty_hint')
      case 'webxdc_apps':
        return allMedia
          ? tx('all_apps_empty_hint')
          : tx('tab_webxdc_empty_hint')
      case 'files':
        return allMedia ? tx('all_files_empty_hint') : tx('tab_docs_empty_hint')
      case 'links':
        return tx('bmchat_media_links_empty_hint')
      default:
        return allMedia ? tx('all_files_empty_hint') : tx('tab_docs_empty_hint')
    }
  }

  updateFirstVisibleMessage(message: Type.MessageLoadResult) {
    if (message.kind === 'message') {
      if (this.dateHeader.current)
        this.dateHeader.current.innerText = moment(
          message.timestamp * 1000
        ).format('LL')
    }
  }

  openFullscreenMedia(message: Type.Message) {
    this.context.openDialog(FullscreenMedia, {
      msg: message,
      neighboringMedia:
        this.props.chatId === 'all'
          ? NeighboringMediaMode.Global
          : NeighboringMediaMode.Chat,
    })
  }

  render() {
    const {
      mediaMessageIds,
      mediaLoadResult,
      currentTab,
      loading,
      queryText,
      galleryImageKeepAspectRatio,
      msgTypes,
      linkEntries,
    } = this.state
    const tx = window.static_translate // static because dynamic isn't too important here
    const emptyTabMessage = this.emptyTabMessage(currentTab)

    const filteredMediaMessageIds =
      currentTab !== 'files'
        ? []
        : mediaMessageIds.filter(id => {
            const result = mediaLoadResult[id]
            return (
              result.kind === 'message' &&
              result.fileName
                ?.toLowerCase()
                .indexOf(queryText.toLowerCase()) !== -1
            )
          })

    const showDateHeader =
      currentTab !== 'files' &&
      currentTab !== 'links' &&
      currentTab !== 'webxdc_apps'

    return (
      <div className='media-view'>
        <ul ref={this.tabListRef} className='tab-list .modifier' role='tablist'>
          <RovingTabindexProvider
            wrapperElementRef={this.tabListRef}
            direction='horizontal'
          >
            {GalleryTabOrder.map(tabId => {
              return (
                <li key={tabId}>
                  <GalleryTab
                    tabId={tabId}
                    onClick={() => this.onSelect(tabId)}
                    tx={tx}
                    isSelected={currentTab === tabId}
                  />
                </li>
              )
            })}
          </RovingTabindexProvider>
          {showDateHeader && (
            <div className='tab-item big-date' ref={this.dateHeader}></div>
          )}
          {currentTab === 'files' && (
            <>
              <div style={{ flexGrow: 1 }}></div>
              <div className='searchbar'>
                <input
                  type='search'
                  placeholder={tx('search_files')}
                  onChange={this.onChangeInput.bind(this)}
                />
              </div>
            </>
          )}
          {(currentTab === 'webxdc_apps' || currentTab === 'links') && (
            <div style={{ flexGrow: 1 }}></div>
          )}
        </ul>
        <div
          role='tabpanel'
          key={
            (currentTab === 'links' ? 'links' : msgTypes.join('.')) +
            String(this.props.chatId)
          }
          // TODO a11y: is it fine to only render one `tabpanel`
          // instead of rendering all and applying the `hidden` attribute
          // to the inactive ones?
          // See https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Roles/tab_role#example
          id={`gallery-tabpanel-${currentTab}`}
          aria-labelledby={`gallery-tab-${currentTab}`}
          style={{ flexGrow: 1 }}
        >
          <div
            ref={this.galleryItemsRef}
            className={`gallery gallery-image-object-fit_${
              galleryImageKeepAspectRatio ? 'contain' : 'cover'
            }`}
          >
            {(currentTab === 'links'
              ? linkEntries.length < 1
              : mediaMessageIds.length < 1) &&
              !loading && (
              <div className='empty-screen'>
                {/* IDEA: when we have someone doing illustrations this would be a great place to add some */}
                <p className='no-media-message'>{emptyTabMessage}</p>
              </div>
            )}

            {currentTab === 'files' && (
              <>
                <AutoSizer disableWidth>
                  {({ height }) => (
                    <RovingTabindexProvider
                      wrapperElementRef={this.galleryItemsRef}
                      direction='vertical'
                    >
                      <FileTable
                        width={'100%'}
                        height={height}
                        mediaLoadResult={mediaLoadResult}
                        mediaMessageIds={filteredMediaMessageIds}
                        queryText={queryText}
                      ></FileTable>
                    </RovingTabindexProvider>
                  )}
                </AutoSizer>
                {filteredMediaMessageIds.length === 0 && (
                  <div className='empty-screen'>
                    <p className='no-media-message'>
                      {tx('search_no_result_for_x', queryText)}
                    </p>
                  </div>
                )}
              </>
            )}

            {currentTab === 'links' && (
              <AutoSizer disableWidth>
                {({ height }) => (
                  <RovingTabindexProvider
                    wrapperElementRef={this.galleryItemsRef}
                    direction='vertical'
                  >
                    <LinksTable
                      width='100%'
                      height={height}
                      links={linkEntries}
                    />
                  </RovingTabindexProvider>
                )}
              </AutoSizer>
            )}

            {currentTab !== 'files' && currentTab !== 'links' && (
              <GridGallery
                currentTab={currentTab}
                element={this.state.element}
                openFullscreenMedia={this.openFullscreenMedia.bind(this)}
                mediaMessageIds={mediaMessageIds}
                galleryItemsRef={this.galleryItemsRef}
                updateFirstVisibleMessage={this.updateFirstVisibleMessage.bind(
                  this
                )}
              />
            )}
          </div>
        </div>
      </div>
    )
  }
}

function GridGallery({
  currentTab,
  mediaMessageIds,
  galleryItemsRef,
  updateFirstVisibleMessage,
  element,
  openFullscreenMedia,
}: {
  currentTab: MediaTabKey
  mediaMessageIds: number[]
  galleryItemsRef: React.RefObject<HTMLDivElement | null>
  updateFirstVisibleMessage: (msg: T.MessageLoadResult) => void
  element: GalleryElement
  openFullscreenMedia: (message: Type.Message) => void
}): React.ReactNode {
  const accountId = selectedAccountId()

  const [messageCache, setMessageCache] = useState<{
    [id: number]: T.MessageLoadResult | undefined
  }>({})
  const loadingMessages = useRef(new Set<T.Message['id']>()).current

  const isMessageLoaded: (index: number) => boolean = index =>
    !!messageCache[mediaMessageIds[index]] ||
    loadingMessages.has(mediaMessageIds[index])
  const loadMessages: (startIndex: number, stopIndex: number) => void = (
    startIndex,
    stopIndex
  ) => {
    const ids = mediaMessageIds.slice(startIndex, stopIndex + 1)
    for (const id of ids) {
      loadingMessages.add(id)
    }
    BackendRemote.rpc.getMessages(accountId, ids).then(messages => {
      setMessageCache(cache => ({ ...cache, ...messages }))
      for (const id of ids) {
        loadingMessages.delete(id)
      }
    })
  }

  return (
    <AutoSizer>
      {({ width, height }) => {
        const widthWithoutScrollbar =
          width - getCurrentDocumentVerticalScrollbarWidth()

        let minWidth = 160

        if (currentTab === 'webxdc_apps') {
          minWidth = 265
        } else if (currentTab === 'audio') {
          minWidth = 322
        }

        const itemsPerRow = Math.max(
          Math.floor(widthWithoutScrollbar / minWidth),
          1
        )

        const itemWidth = widthWithoutScrollbar / itemsPerRow

        const rowCount = Math.ceil(mediaMessageIds.length / itemsPerRow)

        let itemHeight = itemWidth

        if (currentTab === 'webxdc_apps') {
          itemHeight = 61
        } else if (currentTab === 'audio') {
          itemHeight = 94
        }

        return (
          <RovingTabindexProvider
            wrapperElementRef={galleryItemsRef}
            // TODO improvement: perhaps we can easily write
            // proper grid navigation,
            // since grid dimensions are known.
            direction='both'
          >
            <InfiniteLoader
              isItemLoaded={isMessageLoaded}
              itemCount={mediaMessageIds.length}
              loadMoreItems={loadMessages}
            >
              {({ onItemsRendered, ref }) => (
                <FixedSizeGrid
                  width={width}
                  height={height}
                  columnWidth={itemWidth}
                  rowHeight={itemHeight}
                  columnCount={itemsPerRow}
                  rowCount={rowCount}
                  overscanRowCount={itemsPerRow * 10}
                  ref={ref}
                  onItemsRendered={props => {
                    const { visibleColumnStartIndex, visibleRowStartIndex } =
                      props
                    const msgId =
                      mediaMessageIds[
                        visibleRowStartIndex * itemsPerRow +
                          visibleColumnStartIndex
                      ]
                    const message = messageCache[msgId]
                    if (message) {
                      updateFirstVisibleMessage(message)
                    }
                    const translatedProps = {
                      overscanStartIndex: Math.min(
                        mediaMessageIds.length - 1,
                        props.overscanRowStartIndex * itemsPerRow +
                          props.overscanColumnStartIndex
                      ),
                      overscanStopIndex: Math.min(
                        mediaMessageIds.length - 1,
                        props.overscanRowStopIndex * itemsPerRow +
                          props.overscanColumnStopIndex
                      ),
                      visibleStartIndex: Math.min(
                        mediaMessageIds.length - 1,
                        visibleRowStartIndex * itemsPerRow +
                          visibleColumnStartIndex
                      ),
                      visibleStopIndex: Math.min(
                        mediaMessageIds.length - 1,
                        props.visibleRowStopIndex * itemsPerRow +
                          props.visibleColumnStopIndex
                      ),
                    }
                    onItemsRendered(translatedProps)
                  }}
                  itemData={{
                    Component: element,
                    mediaMessageIds,
                    messageCache,
                    openFullscreenMedia,
                    itemsPerRow,
                  }}
                  itemKey={({ rowIndex, columnIndex, data }) =>
                    data.mediaMessageIds[rowIndex * itemsPerRow + columnIndex]
                  }
                >
                  {GalleryGridCell}
                </FixedSizeGrid>
              )}
            </InfiniteLoader>
          </RovingTabindexProvider>
        )
      }}
    </AutoSizer>
  )
}

function GalleryGridCell({
  columnIndex,
  rowIndex,
  style,
  data,
}: {
  columnIndex: number
  rowIndex: number
  style: React.CSSProperties
  data: {
    Component: GalleryElement
    mediaMessageIds: number[]
    messageCache: Record<number, Type.MessageLoadResult | undefined>
    openFullscreenMedia: (message: Type.Message) => void
    itemsPerRow: number
  }
}) {
  const {
    Component,
    mediaMessageIds,
    messageCache,
    openFullscreenMedia,
    itemsPerRow,
  } = data

  const msgId = mediaMessageIds[rowIndex * itemsPerRow + columnIndex]
  const message = messageCache[msgId]
  if (!message) {
    // todo skeleton item (for each mode a fitting shape: image, video, audio, webxdc)
    return null
  }
  return (
    <div style={{ ...style }} className='item'>
      <Component
        messageId={msgId}
        loadResult={message}
        openFullscreenMedia={openFullscreenMedia}
      />
    </div>
  )
}

function GalleryTab(props: {
  tabId: MediaTabKey
  isSelected: boolean
  onClick: () => void
  tx: getMessageFunction
}) {
  const { tabId, isSelected, tx, onClick } = props

  const ref = useRef<HTMLButtonElement>(null)
  const rovingTabindex = useRovingTabindex(ref)

  return (
    <button
      type='button'
      ref={ref}
      className={`tab-item ${isSelected ? 'selected' : ''} ${
        rovingTabindex.className
      }`}
      role='tab'
      aria-selected={isSelected}
      id={`gallery-tab-${tabId}`}
      aria-controls={`gallery-tabpanel-${tabId}`}
      onClick={onClick}
      tabIndex={rovingTabindex.tabIndex}
      onFocus={rovingTabindex.setAsActiveElement}
      onKeyDown={rovingTabindex.onKeydown}
    >
      {tx(tabId)}
    </button>
  )
}

function LinksTable({
  width,
  height,
  links,
}: {
  width: number | string
  height: number
  links: LinkEntry[]
}) {
  const writingDirection = useTranslationWritingDirection()
  return (
    <FixedSizeList
      innerElementType='ol'
      className='react-window-list-reset'
      width={width}
      height={height}
      itemSize={74}
      itemCount={links.length}
      overscanCount={10}
      itemData={{ links }}
      direction={writingDirection}
      itemKey={(index, data) => data.links[index].key}
    >
      {LinkRow}
    </FixedSizeList>
  )
}

function LinkRow({
  index,
  style,
  data,
}: {
  index: number
  style: React.CSSProperties
  data: { links: LinkEntry[] }
}) {
  const link = data.links[index]
  const { jumpToMessage } = useMessage()
  const accountId = selectedAccountId()
  const ref = useRef<HTMLLIElement>(null)
  const rovingTabindex = useRovingTabindex(ref)
  const tx = window.static_translate

  return (
    <li
      ref={ref}
      style={style}
      className={`media-attachment-link ${rovingTabindex.className}`}
      tabIndex={rovingTabindex.tabIndex}
      onFocus={rovingTabindex.setAsActiveElement}
      onKeyDown={rovingTabindex.onKeydown}
    >
      <button
        type='button'
        className='link-open'
        title={link.url}
        onClick={() => runtime.openLink(link.url)}
      >
        <span className='link-title'>{link.label}</span>
        <span className='link-preview'>{link.preview}</span>
      </button>
      <span className='link-meta'>
        {link.senderName}
        {' · '}
        {moment(link.timestamp * 1000).format('L')}
      </span>
      <div className='link-actions'>
        <button type='button' onClick={() => runtime.writeClipboardText(link.url)}>
          {tx('copy')}
        </button>
        <button
          type='button'
          onClick={() =>
            jumpToMessage({
              accountId,
              msgId: link.msgId,
              msgChatId: link.chatId,
              focus: true,
              scrollIntoViewArg: { block: 'center' },
            })
          }
        >
          {tx('show_in_chat')}
        </button>
      </div>
    </li>
  )
}

function FileTable({
  width,
  height,
  mediaMessageIds,
  mediaLoadResult,
  queryText,
}: {
  width: number | string
  height: number
  mediaMessageIds: number[]
  mediaLoadResult: Record<number, Type.MessageLoadResult>
  queryText: string
}) {
  const writingDirection = useTranslationWritingDirection()
  return (
    <FixedSizeList
      innerElementType={'ol'}
      className='react-window-list-reset'
      width={width}
      height={height}
      itemSize={60}
      itemCount={mediaMessageIds.length}
      overscanCount={10}
      itemData={{
        mediaMessageIds,
        mediaLoadResult,
        queryText,
      }}
      direction={writingDirection}
      itemKey={(index, data) => data.mediaMessageIds[index]}
    >
      {FileAttachmentRowWrapper}
    </FixedSizeList>
  )
}

function FileAttachmentRowWrapper({
  index,
  style,
  data,
}: {
  index: number
  style: React.CSSProperties
  data: {
    mediaMessageIds: number[]
    mediaLoadResult: Record<number, Type.MessageLoadResult>
    queryText: string
  }
}) {
  const { mediaMessageIds, mediaLoadResult, queryText } = data
  const msgId = mediaMessageIds[index]
  const message = mediaLoadResult[msgId]
  if (!message) {
    return null
  }
  return (
    <li style={style} className='item'>
      <FileAttachmentRow
        messageId={msgId}
        loadResult={message}
        queryText={queryText}
      />
    </li>
  )
}
