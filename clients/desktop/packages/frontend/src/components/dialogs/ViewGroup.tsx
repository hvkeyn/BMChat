import React, { useCallback, useEffect, useState, useRef } from 'react'
import { parseAndRenderMessage } from '../message/MessageParser'
import { C } from '@deltachat/jsonrpc-client'
import type { T } from '@deltachat/jsonrpc-client'

import { QrCodeShowQrInner } from './QrCode'
import { ContactList } from '../contact/ContactList'
import {
  PseudoListItemShowQrCode,
  PseudoListItemAddMember,
} from '../helpers/PseudoListItem'
import ViewProfile from './ViewProfile'
import { avatarInitial } from '@deltachat-desktop/shared/avatarInitial'
import { shouldDisableClickForFullscreen as shouldDisableFullscreenAvatar } from '../Avatar'
import { DeltaInput, DeltaTextarea } from '../Login-Styles'
import { BackendRemote, onDCEvent } from '../../backend-com'
import { selectedAccountId } from '../../ScreenController'
import Dialog, {
  DialogBody,
  DialogContent,
  DialogHeader,
  OkCancelFooterAction,
} from '../Dialog'
import useConfirmationDialog from '../../hooks/dialog/useConfirmationDialog'
import useDialog from '../../hooks/dialog/useDialog'
import useTranslationFunction from '../../hooks/useTranslationFunction'
import { LastUsedSlot } from '../../utils/lastUsedPaths'
import ProfileInfoHeader from '../ProfileInfoHeader'
import ImageSelector from '../ImageSelector'
import { modifyGroup } from '../../backend/group'

import type { DialogProps } from '../../contexts/DialogContext'
import ImageCropper from '../ImageCropper'
import { AddMemberDialog } from './AddMember/AddMemberDialog'
import { RovingTabindexProvider } from '../../contexts/RovingTabindex'
import { copyToBlobDir } from '../../utils/copyToBlobDir'
import AlertDialog from './AlertDialog'
import { unknownErrorToString } from '@deltachat-desktop/shared/unknownErrorToString'
import { getLogger } from '@deltachat-desktop/shared/logger'
import { listEmailBots, resolveEmailBotHomeChat } from '../../bmchat/emailBots'
import { resolveBotContactDisplay } from '../../bmchat/botContacts'
import { rewriteInviteLink } from '@deltachat-desktop/shared/util'
import { createChatByContactId } from '../../backend/chat'
import { runtime } from '@deltachat-desktop/runtime-interface'
const log = getLogger('ViewGroup')

/**
 * This dialog is used to for groups of various types:
 * - encrypted groups
 * - non encrypted groups (email groups)
 * - channels if the current account is the sender (chatType == "OutBroadcast")
 *
 * Mailinglists and channels (receiver side) have an own dialog
 * since you don't see other receivers in those chats
 * (see MailingListProfile)
 */
export default function ViewGroup(
  props: {
    chat: Parameters<typeof ViewGroupInner>[0]['chat']
  } & DialogProps
) {
  const { chat, onClose } = props
  return (
    <Dialog width={400} onClose={onClose} fixed dataTestid='view-group-dialog'>
      <ViewGroupInner onClose={onClose} chat={chat} />
    </Dialog>
  )
}

/**
 * manages changes to the group name, image and members
 * and updates the group in the backend
 */
export const useGroup = (accountId: number, chat: T.FullChat) => {
  const [group, setGroup] = useState(chat)
  const [groupName, setGroupName] = useState(chat.name)
  const [groupDescription, setGroupDescription] = useState<string | null>(null)
  const [groupImage, setGroupImage] = useState(chat.profileImage)
  const firstLoad = useRef(true)
  const { openDialog } = useDialog()
  const tx = useTranslationFunction()

  useEffect(() => {
    const fetchGroupDescription = async () => {
      const groupDescription = await BackendRemote.rpc.getChatDescription(
        accountId,
        chat.id
      )
      setGroupDescription(groupDescription)
    }
    fetchGroupDescription()
  }, [chat.id, accountId])

  useEffect(() => {
    if (groupDescription === null) return // Not loaded yet
    if (firstLoad.current) {
      firstLoad.current = false
      return
    }
    modifyGroup(accountId, chat.id, groupName, groupDescription, groupImage)
  }, [groupName, groupDescription, groupImage, chat.id, accountId])

  const [attachedBotContacts, setAttachedBotContacts] = useState<T.Contact[]>(
    []
  )
  type AttachedBotRef = { type: 'email' | 'tg'; botId: string }
  const [attachedBotIdByContact, setAttachedBotIdByContact] = useState<
    Map<number, AttachedBotRef>
  >(new Map())
  const [attachedBotDisplayOverrides, setAttachedBotDisplayOverrides] =
    useState<
      Map<number, { address?: string; profileImage?: string | null }>
    >(new Map())

  const loadAttachedBotContacts = useCallback(async () => {
    const showBots =
      chat.chatType === 'OutBroadcast' ||
      (chat.chatType === 'Group' && chat.canSend)
    if (!showBots || runtime.getRuntimeInfo().target !== 'electron') {
      setAttachedBotContacts([])
      setAttachedBotIdByContact(new Map())
      setAttachedBotDisplayOverrides(new Map())
      return
    }
    try {
      const idMap = new Map<number, AttachedBotRef>()
      const contactIds: number[] = []

      const rawEmail = await runtime.bmchatBotsInvoke('bmchat:emailbots:list')
      const emailBots = Array.isArray(rawEmail) ? rawEmail : []
      for (const b of emailBots) {
        const row = b as {
          enabled?: boolean
          attachedChatIds?: number[]
          botContactId?: number
          id?: string
        }
        if (row.enabled === false) continue
        if (!Array.isArray(row.attachedChatIds) || !row.attachedChatIds.includes(chat.id)) {
          continue
        }
        let cid = Number(row.botContactId) || 0
        if (cid <= 0 && row.id) {
          const ensured = await runtime.bmchatBotsInvoke(
            'bmchat:emailbots:ensure-contact',
            { id: row.id }
          )
          cid = Number(ensured?.contactId) || 0
        }
        if (cid > 0 && row.id) {
          contactIds.push(cid)
          idMap.set(cid, { type: 'email', botId: String(row.id) })
        }
      }

      const rawTg = await runtime.bmchatBotsInvoke('bmchat:tgbots:list-config')
      const tgBots = Array.isArray(rawTg) ? rawTg : []
      for (const b of tgBots) {
        const row = b as {
          accountId?: number
          attachedChatIds?: number[]
          botContactId?: number
          id?: string
        }
        if (row.accountId !== accountId) continue
        if (!Array.isArray(row.attachedChatIds) || !row.attachedChatIds.includes(chat.id)) {
          continue
        }
        const cid = Number(row.botContactId) || 0
        if (cid > 0 && row.id) {
          if (!contactIds.includes(cid)) contactIds.push(cid)
          idMap.set(cid, { type: 'tg', botId: String(row.id) })
        }
      }

      setAttachedBotIdByContact(idMap)
      if (contactIds.length === 0) {
        setAttachedBotContacts([])
        return
      }
      const loaded = await BackendRemote.rpc.getContactsByIds(
        accountId,
        contactIds
      )
      const isBotPseudoContact = (c: T.Contact) =>
        (c.address || '').toLowerCase().endsWith('@bots.bmchat.local')
      const bots = contactIds
        .map(id => loaded[id])
        .filter((c): c is T.Contact => !!c && isBotPseudoContact(c))
      setAttachedBotContacts(bots)
      const overrides = new Map<
        number,
        { address?: string; profileImage?: string | null }
      >()
      for (const contact of bots) {
        const display = await resolveBotContactDisplay(accountId, contact)
        if (display) {
          const base = tx('bmchat_bot_profile_username', display.slug)
          overrides.set(contact.id, {
            address: display.description
              ? `${base} - ${display.description}`
              : base,
            profileImage: display.avatarPath,
          })
        }
      }
      setAttachedBotDisplayOverrides(overrides)
    } catch {
      setAttachedBotContacts([])
      setAttachedBotIdByContact(new Map())
      setAttachedBotDisplayOverrides(new Map())
    }
  }, [accountId, chat.chatType, chat.canSend, chat.id, tx])

  useEffect(() => {
    void loadAttachedBotContacts()
  }, [loadAttachedBotContacts])

  const addMembers = useCallback(
    async (members: number[]) => {
      if (!members || members.length === 0) {
        return
      }

      const isBroadcast = chat.chatType === 'OutBroadcast'

      const attachBotToChat = async (
        contactId: number
      ): Promise<'attached' | 'already' | false> => {
        if (runtime.getRuntimeInfo().target !== 'electron') return false
        let emailBots: any[] = []
        let tgBots: any[] = []
        try {
          const raw = await runtime.bmchatBotsInvoke('bmchat:emailbots:list')
          emailBots = Array.isArray(raw) ? raw : []
        } catch {
          emailBots = await listEmailBots()
        }
        try {
          const rawTg = await runtime.bmchatBotsInvoke('bmchat:tgbots:list-config')
          tgBots = Array.isArray(rawTg) ? rawTg : []
        } catch {
          tgBots = []
        }

        const emailBot = emailBots.find(
          (b: {
            enabled?: boolean
            botContactId?: number
            attachedChatIds?: number[]
            id?: string
          }) =>
            b.enabled !== false &&
            Number(b.botContactId) === contactId &&
            b.id
        )
        if (emailBot?.id) {
          if (emailBot.attachedChatIds?.includes(chat.id)) return 'already'
          const res = await runtime.bmchatBotsInvoke('bmchat:emailbots:attach-chat', {
            id: emailBot.id,
            chatId: chat.id,
          })
          return res?.ok ? 'attached' : false
        }

        const tgBot = tgBots.find(
          (b: {
            accountId?: number
            botContactId?: number
            attachedChatIds?: number[]
            id?: string
          }) =>
            b.accountId === accountId &&
            Number(b.botContactId) === contactId &&
            b.id
        )
        if (tgBot?.id) {
          if (tgBot.attachedChatIds?.includes(chat.id)) return 'already'
          const res = await runtime.bmchatBotsInvoke('bmchat:tgbots:attach-chat', {
            id: tgBot.id,
            chatId: chat.id,
          })
          return res?.ok ? 'attached' : false
        }

        try {
          const contact = await BackendRemote.rpc.getContact(accountId, contactId)
          const addr = (contact?.address || '').toLowerCase()
          if (!addr.endsWith('@bots.bmchat.local')) return false
          const slug = addr
            .replace(/^emailbot\./, '')
            .replace(/^tgbot\./, '')
            .replace(/@bots\.bmchat\.local$/, '')
          const byEmailName = emailBots.find(
            (b: {
              enabled?: boolean
              name?: string
              attachedChatIds?: number[]
              id?: string
            }) =>
              b.enabled !== false &&
              String(b.name || '').toLowerCase() === slug &&
              b.id
          )
          if (byEmailName?.id) {
            if (byEmailName.attachedChatIds?.includes(chat.id)) return 'already'
            const res = await runtime.bmchatBotsInvoke('bmchat:emailbots:attach-chat', {
              id: byEmailName.id,
              chatId: chat.id,
            })
            return res?.ok ? 'attached' : false
          }
          const byTgName = tgBots.find(
            (b: {
              accountId?: number
              telegramUsername?: string | null
              attachedChatIds?: number[]
              id?: string
            }) =>
              b.accountId === accountId &&
              String(b.telegramUsername || '').toLowerCase() === slug &&
              b.id
          )
          if (byTgName?.id) {
            if (byTgName.attachedChatIds?.includes(chat.id)) return 'already'
            const res = await runtime.bmchatBotsInvoke('bmchat:tgbots:attach-chat', {
              id: byTgName.id,
              chatId: chat.id,
            })
            return res?.ok ? 'attached' : false
          }
        } catch {
          return false
        }
        return false
      }

      if (isBroadcast) {
        let inviteUrl = ''
        try {
          const [qrCode] = await BackendRemote.rpc.getChatSecurejoinQrCodeSvg(
            accountId,
            chat.id
          )
          inviteUrl = rewriteInviteLink(qrCode)
        } catch (error) {
          openDialog(AlertDialog, {
            title: tx('error'),
            message: tx(
              'error_x',
              `Failed to get channel invite link: ${unknownErrorToString(error)}`
            ),
          })
          return
        }
        const channelName = chat.name || ''
        const body = tx('bmchat_channel_invite_body_fmt', channelName, inviteUrl)

        let sent = 0
        let skipped = 0
        let botsAttached = 0
        for (const contactId of members) {
          if (contactId <= 0 || contactId === C.DC_CONTACT_ID_SELF) {
            skipped++
            continue
          }
          const attachResult = await attachBotToChat(contactId)
          if (attachResult === 'attached') {
            botsAttached++
            continue
          }
          if (attachResult === 'already') {
            window.__userFeedback?.({
              type: 'info',
              text: tx('bmchat_email_bot_already_on_channel'),
            })
            continue
          }
          try {
            const dmChatId = await createChatByContactId(accountId, contactId)
            if (dmChatId <= 0) {
              skipped++
              continue
            }
            await BackendRemote.rpc.sendMsg(accountId, dmChatId, { text: body })
            sent++
          } catch {
            skipped++
          }
        }

        if (botsAttached > 0 && sent === 0 && skipped === 0) {
          await loadAttachedBotContacts()
          window.__userFeedback?.({
            type: 'success',
            text: tx('bmchat_email_bot_attached_to_channel', String(botsAttached)),
          })
        } else if (sent > 0 && skipped === 0) {
          window.__userFeedback?.({
            type: 'success',
            text: tx('bmchat_channel_invite_sent', String(sent)),
          })
        } else if (sent > 0) {
          window.__userFeedback?.({
            type: 'info',
            text: tx('bmchat_channel_invite_partial_fmt', String(sent), String(skipped)),
          })
        } else if (botsAttached > 0) {
          await loadAttachedBotContacts()
          window.__userFeedback?.({
            type: 'success',
            text: tx('bmchat_email_bot_attached_to_channel', String(botsAttached)),
          })
        } else {
          window.__userFeedback?.({
            type: 'error',
            text: tx('bmchat_channel_invite_failed'),
          })
        }
        return
      }

      let botsAttached = 0
      const humanMembers: number[] = []
      for (const contactId of members) {
        if (contactId <= 0 || contactId === C.DC_CONTACT_ID_SELF) continue
        const attachResult = await attachBotToChat(contactId)
        if (attachResult === 'attached') {
          botsAttached++
        } else if (attachResult === 'already') {
          window.__userFeedback?.({
            type: 'info',
            text: tx('bmchat_email_bot_already_on_channel'),
          })
        } else {
          humanMembers.push(contactId)
        }
      }
      if (botsAttached > 0) {
        await loadAttachedBotContacts()
        window.__userFeedback?.({
          type: 'success',
          text: tx('bmchat_email_bot_attached_to_channel', String(botsAttached)),
        })
      }

      try {
        await Promise.all(
          humanMembers.map(id =>
            BackendRemote.rpc.addContactToChat(accountId, chat.id, id)
          )
        )
      } catch (error) {
        openDialog(AlertDialog, {
          title: tx('error'),
          message: tx(
            'error_x',
            `Failed to modify group members: ${unknownErrorToString(error)}`
          ),
        })
        return
      }

      log.info(
        `Account ${accountId} added ${members.length} members to group ${chat.id} (${members.join(
          ', '
        )})`
      )
    },
    [tx, openDialog, chat.id, chat.name, chat.chatType, accountId, loadAttachedBotContacts]
  )

  const removeMember = useCallback(
    async (userId: number) => {
      const botRef = attachedBotIdByContact.get(userId)
      if (botRef && runtime.getRuntimeInfo().target === 'electron') {
        try {
          const channel =
            botRef.type === 'email'
              ? 'bmchat:emailbots:detach-chat'
              : 'bmchat:tgbots:detach-chat'
          const res = await runtime.bmchatBotsInvoke(channel, {
            id: botRef.botId,
            chatId: chat.id,
          })
          if (res?.ok) {
            await loadAttachedBotContacts()
            log.info(
              `Account ${accountId} detached ${botRef.type} bot ${botRef.botId} from chat ${chat.id}`
            )
            return
          }
        } catch (error) {
          openDialog(AlertDialog, {
            title: tx('error'),
            message: tx(
              'error_x',
              `Failed to detach bot: ${unknownErrorToString(error)}`
            ),
          })
          return
        }
      }

      try {
        await BackendRemote.rpc.removeContactFromChat(
          accountId,
          chat.id,
          userId
        )
      } catch (error) {
        openDialog(AlertDialog, {
          title: tx('error'),
          message: tx(
            'error_x',
            `Failed to modify group members: ${unknownErrorToString(error)}`
          ),
        })
        return
      }

      log.info(
        `Account ${accountId} removed member ${userId} from group ${chat.id})`
      )
    },
    [
      tx,
      openDialog,
      chat.id,
      chat.chatType,
      accountId,
      attachedBotIdByContact,
      loadAttachedBotContacts,
    ]
  )

  const [pastContacts, setPastContacts] = useState<T.Contact[]>([])

  useEffect(() => {
    BackendRemote.rpc
      .getContactsByIds(accountId, group.pastContactIds)
      .then((pastContacts: { [id: number]: T.Contact }) => {
        setPastContacts(
          group.pastContactIds.map((id: number) => pastContacts[id])
        )
      })
  }, [accountId, group.pastContactIds])

  const [groupContacts, setGroupContacts] = useState<T.Contact[]>([])

  useEffect(() => {
    BackendRemote.rpc
      .getContactsByIds(accountId, group.contactIds)
      .then((groupContacts: { [id: number]: T.Contact }) => {
        setGroupContacts(
          group.contactIds.map((id: number) => groupContacts[id])
        )
      })
  }, [accountId, group.contactIds])

  useEffect(() => {
    return onDCEvent(
      accountId,
      'ContactsChanged',
      ({ contactId: changedContactId }) => {
        // update contacts in case a contact changed
        // while this dialog is open (e.g. contact got blocked)
        //
        // Loading the initial `pastContacts`
        // and `groupContacts` is taken care of in different places.

        let contactIdsToReload: Array<T.Contact['id']>
        if (changedContactId == null) {
          contactIdsToReload = [...group.pastContactIds, ...group.contactIds]
        } else {
          if (
            !group.pastContactIds.includes(changedContactId) &&
            !group.contactIds.includes(changedContactId)
          ) {
            // No need to do anything, the contact has nothing to do
            // with this group. For performance.
            return
          }

          contactIdsToReload = [changedContactId]
        }

        BackendRemote.rpc
          .getContactsByIds(accountId, contactIdsToReload)
          .then((contactsToUpdate: { [id: string]: T.Contact }) => {
            // Making sure to only update the contacts
            // that are already present in the lists,
            // because we're doing it in an async way.
            setGroupContacts(groupContacts =>
              groupContacts.map(
                (oldContact: T.Contact) =>
                  contactsToUpdate[oldContact.id] ?? oldContact
              )
            )
            setPastContacts(pastContacts =>
              pastContacts.map(
                oldContact => contactsToUpdate[oldContact.id] ?? oldContact
              )
            )
          })
      }
    )
  }, [accountId, group])

  useEffect(() => {
    return onDCEvent(accountId, 'ChatModified', ({ chatId }) => {
      if (chatId === group.id) {
        BackendRemote.rpc.getFullChatById(accountId, group.id).then(setGroup)
        BackendRemote.rpc
          .getChatDescription(accountId, chatId)
          .then(setGroupDescription)
        void loadAttachedBotContacts()
      }
    })
  }, [accountId, group.id, loadAttachedBotContacts])

  return {
    group,
    groupName,
    groupDescription,
    groupImage,
    setGroupName,
    setGroupDescription,
    groupContacts,
    attachedBotContacts,
    attachedBotDisplayOverrides,
    addMembers,
    removeMember,
    setGroupImage,
    pastContacts,
  }
}

function ViewGroupInner(
  props: {
    chat: T.FullChat & {
      chatType: 'Group' | 'OutBroadcast'
    }
  } & DialogProps
) {
  const { chat, onClose } = props
  const isBroadcast = chat.chatType === 'OutBroadcast'
  const { openDialog } = useDialog()
  const accountId = selectedAccountId()
  const openConfirmationDialog = useConfirmationDialog()
  const tx = useTranslationFunction()

  const chatDisabled = !chat.canSend

  const groupMemberContactListWrapperRef = useRef<HTMLDivElement>(null)
  const groupPastMemberContactListWrapperRef = useRef<HTMLDivElement>(null)

  const {
    group,
    groupName,
    groupDescription,
    groupImage,
    setGroupName,
    setGroupDescription,
    groupContacts,
    attachedBotContacts,
    attachedBotDisplayOverrides,
    pastContacts,
    addMembers,
    removeMember,
    setGroupImage,
  } = useGroup(accountId, chat)

  const showRemoveGroupMemberConfirmationDialog = useCallback(
    async (contact: T.Contact) => {
      const confirmed = await openConfirmationDialog({
        message: !isBroadcast
          ? tx('ask_remove_members', contact.displayName)
          : tx('ask_remove_from_channel', contact.displayName),
        confirmLabel: tx('delete'),
        dataTestid: 'remove-group-member-dialog',
      })

      if (confirmed) {
        removeMember(contact.id)
      }
    },
    [isBroadcast, openConfirmationDialog, removeMember, tx]
  )

  const onClickEdit = () => {
    if (groupDescription === null) {
      // just in case the group description is not yet loaded
      // (it defaults to an empty string)
      return
    }
    openDialog(EditGroupNameDialog, {
      groupName,
      groupDescription,
      groupImage,
      groupColor: chat.color,
      onOk: (
        groupName: string,
        groupDescription: string,
        groupImage: string | null
      ) => {
        // TODO this check should be way earlier, you should not be able to "OK" the dialog if there is no group name
        if (groupName.length > 1) {
          setGroupName(groupName)
        }
        // Description can be empty, so always set it
        setGroupDescription(groupDescription)
        setGroupImage(groupImage)
      },
      isBroadcast,
    })
  }

  const listFlags = C.DC_GCL_ADD_SELF

  // We don't allow editing of non encrypted groups (email groups)
  // i.e. changing name, avatar or recipients
  // since it cannot be guaranteed that the recipients will adapt
  // these changes (image is not shown at all in MTAs, group name is
  // just the subject and recipients are basically just an email
  // distribution list)
  const allowEdit = !chatDisabled && group.isEncrypted

  const showAddMemberDialog = () => {
    openDialog(AddMemberDialog, {
      listFlags,
      groupMembers: group.contactIds,
      onOk: addMembers,
    })
  }

  const showQRDialog = async () => {
    const [qrCode, svg] = await BackendRemote.rpc.getChatSecurejoinQrCodeSvg(
      accountId,
      chat.id
    )

    openDialog(ShowQRDialog, {
      qrCode,
      qrCodeSVG: svg,
      groupName,
    })
  }

  const [profileContact, setProfileContact] = useState<T.Contact | null>(null)
  const [isEmailBotHome, setIsEmailBotHome] = useState(false)
  const [emailBotName, setEmailBotName] = useState<string | null>(null)

  useEffect(() => {
    if (!isBroadcast) {
      setIsEmailBotHome(false)
      setEmailBotName(null)
      return
    }
    let cancelled = false
    ;(async () => {
      try {
        const resolved = await resolveEmailBotHomeChat(accountId, chat.id)
        if (cancelled) return
        setIsEmailBotHome(resolved.isHome)
        setEmailBotName(resolved.name)
      } catch {
        if (!cancelled) {
          setIsEmailBotHome(false)
          setEmailBotName(null)
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [isBroadcast, chat.id, groupName, accountId])

  const broadcastTitle = isEmailBotHome ? tx('bot') : tx('channel')

  return (
    <>
      {!profileContact && (
        <>
          {allowEdit && (
            <DialogHeader
              title={!isBroadcast ? tx('tab_group') : broadcastTitle}
              onClickEdit={onClickEdit}
              onClose={onClose}
              dataTestid='view-group-dialog-header'
            />
          )}
          {!allowEdit && (
            <DialogHeader
              title={tx('tab_group')}
              onClose={onClose}
              dataTestid='view-group-dialog-header'
            />
          )}
          <DialogBody>
            <DialogContent>
              <ProfileInfoHeader
                avatarPath={groupImage ? groupImage : undefined}
                color={chat.color}
                displayName={groupName}
                disableFullscreen={shouldDisableFullscreenAvatar(chat)}
              />
              <div className='group-profile-subtitle'>
                {isEmailBotHome && emailBotName
                  ? tx('bmchat_bot_profile_username', [emailBotName])
                  : !isBroadcast
                    ? group.contactIds.length > 1 || group.selfInGroup
                      ? tx('n_members', group.contactIds.length.toString(), {
                          quantity: group.contactIds.length,
                        })
                      : ''
                    : tx(
                        'n_recipients',
                        Math.max(
                          1,
                          group.contactIds.length + attachedBotContacts.length
                        ).toString(),
                        {
                          quantity: Math.max(
                            1,
                            group.contactIds.length + attachedBotContacts.length
                          ),
                        }
                      )}
              </div>
              {groupDescription && (
                <div className='group-profile-description'>
                  {parseAndRenderMessage(groupDescription, false, 0)}
                </div>
              )}
            </DialogContent>
            <div
              className='group-member-contact-list-wrapper'
              ref={groupMemberContactListWrapperRef}
              data-testid='group-member-list'
            >
              <RovingTabindexProvider
                wrapperElementRef={groupMemberContactListWrapperRef}
              >
                {!chatDisabled &&
                  !isEmailBotHome &&
                  (group.isEncrypted || isBroadcast || attachedBotContacts.length > 0) && (
                  <>
                    <PseudoListItemAddMember
                      onClick={() => showAddMemberDialog()}
                    />
                    <PseudoListItemShowQrCode onClick={() => showQRDialog()} />
                  </>
                )}
                {groupContacts.length === 0 &&
                  attachedBotContacts.length === 0 &&
                  group.contactIds.length > 0 && (
                  <div /* placeholder to keep layout from jumping around while contact info is loaded */
                    style={{
                      height:
                        (group.contactIds.length + attachedBotContacts.length) *
                        64 /* 64px is the height of a contact list item */,
                    }}
                    aria-busy
                  ></div>
                )}
                {attachedBotContacts.length > 0 && (
                  <>
                    <div className='group-separator'>{tx('bot')}</div>
                    <ContactList
                      contacts={attachedBotContacts}
                      contactOverrides={attachedBotDisplayOverrides}
                      showRemove={!chatDisabled}
                      onClick={contact => {
                        if (contact.id === C.DC_CONTACT_ID_SELF) {
                          return
                        }
                        setProfileContact(contact)
                      }}
                      onRemoveClick={showRemoveGroupMemberConfirmationDialog}
                    />
                  </>
                )}
                {isBroadcast && (() => {
                  const recipients = groupContacts.filter(
                    c => !attachedBotContacts.some(b => b.id === c.id)
                  )
                  return recipients.length > 0 ? (
                    <div className='group-separator'>
                      {tx('n_recipients', recipients.length.toString(), {
                        quantity: recipients.length,
                      })}
                    </div>
                  ) : null
                })()}
                {
                  <ContactList
                    contacts={groupContacts.filter(
                      c => !attachedBotContacts.some(b => b.id === c.id)
                    )}
                    showRemove={!chatDisabled && group.isEncrypted}
                    onClick={contact => {
                      if (contact.id === C.DC_CONTACT_ID_SELF) {
                        return
                      }
                      setProfileContact(contact)
                    }}
                    onRemoveClick={showRemoveGroupMemberConfirmationDialog}
                    olElementAttrs={{
                      'aria-labelledby': 'group-profile-subtitle',
                    }}
                  />
                }
              </RovingTabindexProvider>
            </div>
            {group.pastContactIds.length != 0 && pastContacts.length == 0 && (
              <div /* placeholder to keep layout from jumping around while contact info is loaded */
                style={{
                  height:
                    group.pastContactIds.length *
                    64 /* 64px is the height of a contact list item */,
                }}
                aria-busy
              ></div>
            )}
            {pastContacts.length > 0 && (
              <>
                <div
                  id='view-group-past-members-title'
                  className='group-separator'
                >
                  {tx('past_members')}
                </div>
                <div
                  className='group-member-contact-list-wrapper'
                  ref={groupPastMemberContactListWrapperRef}
                >
                  <RovingTabindexProvider
                    wrapperElementRef={groupPastMemberContactListWrapperRef}
                  >
                    <ContactList
                      contacts={pastContacts}
                      showRemove={false}
                      onClick={contact => {
                        if (contact.id === C.DC_CONTACT_ID_SELF) {
                          return
                        }
                        setProfileContact(contact)
                      }}
                      olElementAttrs={{
                        'aria-labelledby': 'view-group-past-members-title',
                      }}
                    />
                  </RovingTabindexProvider>
                </div>
              </>
            )}
          </DialogBody>
        </>
      )}
      {profileContact && (
        <ViewProfile
          onBack={() => setProfileContact(null)}
          onClose={onClose}
          contact={profileContact}
        />
      )}
    </>
  )
}

export function ShowQRDialog({
  qrCode,
  groupName,
  qrCodeSVG,
  onClose,
}: { qrCode: string; groupName: string; qrCodeSVG?: string } & DialogProps) {
  const tx = useTranslationFunction()

  return (
    <Dialog
      onClose={onClose}
      canOutsideClickClose={true}
      fixed
      dataTestid='group-invite-qr'
    >
      <DialogHeader title={tx('qrshow_title')} onClose={onClose} />
      <QrCodeShowQrInner
        qrCode={qrCode}
        qrCodeSVG={qrCodeSVG}
        onClose={onClose}
        description={tx('qrshow_join_group_hint', [groupName])}
      />
    </Dialog>
  )
}

export function EditGroupNameDialog({
  onClose,
  onOk,
  isBroadcast,
  groupName: initialGroupName,
  groupDescription: initialGroupDescription,
  groupColor,
  groupImage: initialGroupImage,
}: {
  onOk: (
    groupName: string,
    groupDescription: string,
    groupImage: string | null
  ) => void
  groupName: string
  groupDescription: string
  groupImage: string | null
  groupColor: string
  isBroadcast?: boolean
} & DialogProps) {
  const [groupName, setGroupName] = useState(initialGroupName)
  const [groupDescription, setGroupDescription] = useState(
    initialGroupDescription
  )
  const [groupImage, setGroupImage] = useState(initialGroupImage)
  const tx = useTranslationFunction()

  const onClickCancel = () => {
    onClose()
  }

  const onClickOk = () => {
    onClose()
    onOk(groupName, groupDescription, groupImage)
  }

  const haveUnsavedChanges =
    groupName !== initialGroupName ||
    groupDescription !== initialGroupDescription ||
    groupImage !== initialGroupImage

  return (
    <Dialog onClose={onClose} canOutsideClickClose={!haveUnsavedChanges} fixed>
      <DialogHeader
        title={
          !isBroadcast ? tx('menu_group_name_and_image') : tx('channel_name')
        }
      />
      <form action={onClickOk}>
        <DialogBody>
          <DialogContent>
            <div
              className='profile-image-username center'
              style={{ marginBottom: '30px' }}
            >
              <GroupImageSelector
                groupName={groupName}
                groupColor={groupColor}
                groupImage={groupImage}
                setGroupImage={setGroupImage}
              />
            </div>
            <DeltaInput
              id='groupname'
              placeholder={!isBroadcast ? tx('group_name') : tx('channel_name')}
              value={groupName}
              onChange={(
                event: React.FormEvent<HTMLElement> &
                  React.ChangeEvent<HTMLInputElement>
              ) => {
                setGroupName(event.target.value)
              }}
            />
            {groupName === '' && (
              <p
                style={{
                  color: 'var(--colorDanger)',
                  marginLeft: '80px',
                  position: 'relative',
                  top: '-10px',
                  marginBottom: '-18px',
                }}
              >
                {tx('please_enter_chat_name')}
              </p>
            )}
            <DeltaTextarea
              id='description'
              placeholder={tx('chat_description')}
              value={groupDescription}
              onChange={(
                event: React.FormEvent<HTMLElement> &
                  React.ChangeEvent<HTMLTextAreaElement>
              ) => {
                setGroupDescription(event.target.value)
              }}
            />
          </DialogContent>
        </DialogBody>
        <OkCancelFooterAction onCancel={onClickCancel} onOk='submit' />
      </form>
    </Dialog>
  )
}

export function GroupImageSelector(props: {
  groupName: string
  groupColor: string
  groupImage: string | null
  setGroupImage: (groupImage: string | null) => void
}) {
  const tx = useTranslationFunction()
  const initials = avatarInitial(props.groupName, '')
  const { openDialog } = useDialog()

  return (
    <ImageSelector
      color={props.groupColor}
      filePath={props.groupImage}
      initials={initials}
      lastUsedSlot={LastUsedSlot.GroupImage}
      onChange={async filepath => {
        if (!filepath) {
          props.setGroupImage(null)
        } else {
          openDialog(ImageCropper, {
            filepath: await copyToBlobDir(filepath),
            shape: 'circle',
            onResult: props.setGroupImage,
            onCancel: () => {},
            desiredWidth: 512,
            desiredHeight: 512,
          })
        }
      }}
      removeLabel={tx('remove_group_image')}
      selectLabel={tx('change_group_image')}
    />
  )
}
