//! # Download large messages manually.

use std::collections::BTreeMap;

use anyhow::{Context as _, Result, anyhow, bail, ensure};
use async_imap::types::Flag;
use deltachat_derive::{FromSql, ToSql};
use futures::TryStreamExt;
use serde::{Deserialize, Serialize};

use crate::chat::add_device_msg;
use crate::constants::DC_VERSION_STR;
use crate::context::Context;
use crate::imap::session::Session;
use crate::log::warn;
use crate::message::{self, Message, MsgId, rfc724_mid_exists};
use crate::receive_imf::receive_imf_inner;
use crate::{EventType, chatlist_events};

/// Octets per IMAP `BODY.PEEK[]<offset.count>` while a partial message is
/// downloaded. One FETCH of the whole file reports no progress until the end.
const BMCHAT_FETCH_CHUNK: u32 = 4 * 1024 * 1024;

pub(crate) mod post_msg_metadata;
pub(crate) use post_msg_metadata::PostMsgMetadata;

/// If a message is downloaded only partially
/// and `delete_server_after` is set to small timeouts (eg. "at once"),
/// the user might have no chance to actually download that message.
/// `MIN_DELETE_SERVER_AFTER` increases the timeout in this case.
pub(crate) const MIN_DELETE_SERVER_AFTER: i64 = 48 * 60 * 60;

/// From this point onward outgoing messages are considered large
/// and get a Pre-Message, which announces the Post-Message.
/// This is only about sending so we can modify it any time.
/// Current value is a bit less than the minimum auto-download setting from the UIs (which is 160
/// KiB).
pub(crate) const PRE_MSG_ATTACHMENT_SIZE_THRESHOLD: u64 = 140_000;

/// Max size for pre messages. A warning is emitted when this is exceeded.
pub(crate) const PRE_MSG_SIZE_WARNING_THRESHOLD: usize = 150_000;

/// Download state of the message.
#[derive(
    Debug,
    Default,
    Display,
    Clone,
    Copy,
    PartialEq,
    Eq,
    FromPrimitive,
    ToPrimitive,
    FromSql,
    ToSql,
    Serialize,
    Deserialize,
)]
#[repr(u32)]
pub enum DownloadState {
    /// Message is fully downloaded.
    #[default]
    Done = 0,

    /// Message is partially downloaded and can be fully downloaded at request.
    Available = 10,

    /// Failed to fully download the message.
    Failure = 20,

    /// Undecipherable message.
    Undecipherable = 30,

    /// Full download of the message is in progress.
    InProgress = 1000,
}

impl MsgId {
    /// Schedules Post-Message download for partially downloaded message.
    pub async fn download_full(self, context: &Context) -> Result<()> {
        let msg = Message::load_from_db(context, self).await?;
        match msg.download_state() {
            DownloadState::Done | DownloadState::Undecipherable => {
                return Err(anyhow!("Nothing to download."));
            }
            DownloadState::InProgress => return Err(anyhow!("Download already in progress.")),
            DownloadState::Available | DownloadState::Failure => {
                if msg.rfc724_mid().is_empty() {
                    return Err(anyhow!("Download not possible, message has no rfc724_mid"));
                }
                self.update_download_state(context, DownloadState::InProgress)
                    .await?;
                info!(
                    context,
                    "Requesting full download of {:?}.",
                    msg.rfc724_mid()
                );
                context
                    .sql
                    .execute(
                        "INSERT INTO download (rfc724_mid, msg_id) VALUES (?,?)",
                        (msg.rfc724_mid(), msg.id),
                    )
                    .await?;
                context.scheduler.interrupt_inbox().await;
            }
        }
        Ok(())
    }

    /// Updates the message download state. Returns `Ok` if the message doesn't exist anymore or has
    /// the download state up to date.
    pub(crate) async fn update_download_state(
        self,
        context: &Context,
        download_state: DownloadState,
    ) -> Result<()> {
        if context
            .sql
            .execute(
                "UPDATE msgs SET download_state=? WHERE id=? AND download_state<>?1",
                (download_state, self),
            )
            .await?
            == 0
        {
            return Ok(());
        }
        let Some(msg) = Message::load_from_db_optional(context, self).await? else {
            return Ok(());
        };
        context.emit_event(EventType::MsgsChanged {
            chat_id: msg.chat_id,
            msg_id: self,
        });
        chatlist_events::emit_chatlist_item_changed(context, msg.chat_id);
        Ok(())
    }
}

impl Message {
    /// Returns the download state of the message.
    pub fn download_state(&self) -> DownloadState {
        self.download_state
    }
}

/// Actually downloads a message partially downloaded before if the message is available on the
/// session transport, in which case returns `Some`. If the message is available on another
/// transport, returns `None`.
///
/// Most messages are downloaded automatically on fetch instead.
pub(crate) async fn download_msg(
    context: &Context,
    rfc724_mid: String,
    session: &mut Session,
) -> Result<Option<()>> {
    let transport_id = session.transport_id();
    let row = context
        .sql
        .query_row_optional(
            "SELECT uid, folder, transport_id FROM imap
             WHERE rfc724_mid=? AND target!=''
             ORDER BY transport_id=? DESC LIMIT 1",
            (&rfc724_mid, transport_id),
            |row| {
                let server_uid: u32 = row.get(0)?;
                let server_folder: String = row.get(1)?;
                let msg_transport_id: u32 = row.get(2)?;
                Ok((server_uid, server_folder, msg_transport_id))
            },
        )
        .await?;

    let Some((server_uid, server_folder, msg_transport_id)) = row else {
        // No IMAP record found, we don't know the UID and folder.
        delete_from_available_post_msgs(context, &rfc724_mid).await?;
        return Err(anyhow!(
            "IMAP location for {rfc724_mid:?} post-message is unknown"
        ));
    };
    if msg_transport_id != transport_id {
        return Ok(None);
    }
    session
        .fetch_single_msg(context, &server_folder, server_uid, rfc724_mid)
        .await?;
    Ok(Some(()))
}

impl Session {
    /// Download a single message and pipe it to receive_imf().
    ///
    /// receive_imf() is not directly aware that this is a result of a call to download_msg(),
    /// however, implicitly knows that as the existing message is flagged as being partly.
    async fn fetch_single_msg(
        &mut self,
        context: &Context,
        folder: &str,
        uid: u32,
        rfc724_mid: String,
    ) -> Result<()> {
        if uid == 0 {
            bail!("Attempt to fetch UID 0");
        }

        let folder_exists = self.select_with_uidvalidity(context, folder).await?;
        ensure!(folder_exists, "No folder {folder}");

        // we are connected, and the folder is selected
        info!(context, "Downloading message {}/{} fully...", folder, uid);

        let msg_id = rfc724_mid_exists(context, &rfc724_mid).await?;
        let file_bytes = if let Some(id) = msg_id {
            if let Ok(msg) = Message::load_from_db(context, id).await {
                msg.get_filebytes(context)
                    .await
                    .ok()
                    .flatten()
                    .unwrap_or(0)
            } else {
                0
            }
        } else {
            0
        };

        let fetched = match self
            .fetch_body_with_progress(context, uid, msg_id, file_bytes)
            .await
        {
            Ok(fetched) => fetched,
            Err(err) => {
                warn!(
                    context,
                    "Chunked download of UID {uid} failed ({err:#}), falling back to one FETCH."
                );
                let mut uid_message_ids: BTreeMap<u32, String> = BTreeMap::new();
                uid_message_ids.insert(uid, rfc724_mid);
                let (sender, receiver) = async_channel::unbounded();
                self.fetch_many_msgs(context, folder, vec![uid], &uid_message_ids, sender)
                    .await?;
                if receiver.recv().await.is_err() {
                    bail!("Failed to fetch UID {uid}");
                }
                return Ok(());
            }
        };

        if fetched.deleted {
            info!(context, "Not processing deleted msg {uid}.");
            return Ok(());
        }

        if let Err(err) =
            receive_imf_inner(context, &rfc724_mid, &fetched.bytes, fetched.seen).await
        {
            warn!(context, "receive_imf error: {err:#}.");
            let text = format!(
                "❌ Failed to receive a message: {err:#}. Core version v{DC_VERSION_STR}. Please report this bug to delta@merlinux.eu or https://support.delta.chat/."
            );
            let mut msg = Message::new_text(text);
            add_device_msg(context, None, Some(&mut msg)).await?;
        }
        Ok(())
    }

    /// Downloads `BODY.PEEK[]` in 4 MiB slices and emits `bmchat-xfer <msg> <got> <total>`.
    async fn fetch_body_with_progress(
        &mut self,
        context: &Context,
        uid: u32,
        msg_id: Option<MsgId>,
        file_bytes: u64,
    ) -> Result<FetchedBody> {
        let uid_set = uid.to_string();
        let mut size_stream = self
            .uid_fetch(&uid_set, "(FLAGS RFC822.SIZE)")
            .await
            .context("RFC822.SIZE fetch")?;
        let size_row = size_stream
            .try_next()
            .await
            .context("RFC822.SIZE row")?
            .context("RFC822.SIZE missing")?;
        drain_fetch(&mut size_stream).await?;

        let seen = size_row.flags().any(|flag| flag == Flag::Seen);
        let deleted = size_row.flags().any(|flag| flag == Flag::Deleted);
        let rfc822_size = u64::from(size_row.size.unwrap_or(0));
        drop(size_row);
        drop(size_stream);
        if deleted {
            return Ok(FetchedBody {
                bytes: Vec::new(),
                seen,
                deleted: true,
            });
        }

        let mut body = Vec::new();
        if rfc822_size > 0 && rfc822_size <= 512 * 1024 * 1024 {
            body.reserve(usize::try_from(rfc822_size).unwrap_or(0));
        }
        let mut offset: u64 = 0;
        for _ in 0..2048 {
            let query = format!("(BODY.PEEK[]<{offset}.{BMCHAT_FETCH_CHUNK}>)");
            let mut stream = self
                .uid_fetch(&uid_set, &query)
                .await
                .with_context(|| format!("partial BODY fetch at {offset}"))?;
            let row = stream
                .try_next()
                .await
                .context("partial BODY row")?
                .context("partial BODY missing")?;
            drain_fetch(&mut stream).await?;
            let chunk = row.body().context("partial BODY had no bytes")?;
            if chunk.is_empty() {
                break;
            }
            let whole_message = offset == 0 && chunk.len() > BMCHAT_FETCH_CHUNK as usize;
            body.extend_from_slice(chunk);
            offset = body.len() as u64;
            let wire_total = if rfc822_size > 0 {
                rfc822_size
            } else {
                offset
            };
            emit_xfer(context, msg_id, file_bytes, offset, wire_total);
            if whole_message || (chunk.len() as u32) < BMCHAT_FETCH_CHUNK {
                break;
            }
            if rfc822_size > 0 && offset >= rfc822_size {
                break;
            }
        }
        if body.is_empty() {
            bail!("Empty body for UID {uid}");
        }
        Ok(FetchedBody {
            bytes: body,
            seen,
            deleted: false,
        })
    }
}

struct FetchedBody {
    bytes: Vec<u8>,
    seen: bool,
    deleted: bool,
}

fn emit_xfer(
    context: &Context,
    msg_id: Option<MsgId>,
    file_bytes: u64,
    wire_got: u64,
    wire_total: u64,
) {
    let Some(msg_id) = msg_id else {
        return;
    };
    let (got, total) = if file_bytes > 0 && wire_total > 0 {
        let scaled = file_bytes.saturating_mul(wire_got) / wire_total;
        (scaled.min(file_bytes), file_bytes)
    } else {
        (wire_got, wire_total)
    };
    context.emit_event(EventType::Info(format!(
        "bmchat-xfer {} {got} {total}",
        msg_id.to_u32()
    )));
}

async fn drain_fetch(
    stream: &mut (impl TryStreamExt<Ok = async_imap::types::Fetch, Error = impl std::fmt::Display>
             + Unpin),
) -> Result<()> {
    while stream
        .try_next()
        .await
        .map_err(|err| anyhow!("{err}"))?
        .is_some()
    {}
    Ok(())
}

async fn set_state_to_failure(context: &Context, rfc724_mid: &str) -> Result<()> {
    if let Some(msg_id) = rfc724_mid_exists(context, rfc724_mid).await? {
        // Update download state to failure
        // so it can be retried.
        //
        // On success update_download_state() is not needed
        // as receive_imf() already
        // set the state and emitted the event.
        msg_id
            .update_download_state(context, DownloadState::Failure)
            .await?;
    }
    Ok(())
}

async fn available_post_msgs_contains_rfc724_mid(
    context: &Context,
    rfc724_mid: &str,
) -> Result<bool> {
    Ok(context
        .sql
        .query_get_value::<String>(
            "SELECT rfc724_mid FROM available_post_msgs WHERE rfc724_mid=?",
            (&rfc724_mid,),
        )
        .await?
        .is_some())
}

async fn delete_from_available_post_msgs(context: &Context, rfc724_mid: &str) -> Result<()> {
    context
        .sql
        .execute(
            "DELETE FROM available_post_msgs WHERE rfc724_mid=?",
            (&rfc724_mid,),
        )
        .await?;
    Ok(())
}

async fn delete_from_downloads(context: &Context, rfc724_mid: &str) -> Result<()> {
    context
        .sql
        .execute("DELETE FROM download WHERE rfc724_mid=?", (&rfc724_mid,))
        .await?;
    Ok(())
}

pub(crate) async fn msg_is_downloaded_for(context: &Context, rfc724_mid: &str) -> Result<bool> {
    Ok(message::rfc724_mid_exists(context, rfc724_mid)
        .await?
        .is_some())
}

pub(crate) async fn download_msgs(context: &Context, session: &mut Session) -> Result<()> {
    let rfc724_mids = context
        .sql
        .query_map_vec("SELECT rfc724_mid FROM download", (), |row| {
            let rfc724_mid: String = row.get(0)?;
            Ok(rfc724_mid)
        })
        .await?;

    for rfc724_mid in &rfc724_mids {
        let res = download_msg(context, rfc724_mid.clone(), session).await;
        if let Ok(Some(())) = res {
            delete_from_downloads(context, rfc724_mid).await?;
            delete_from_available_post_msgs(context, rfc724_mid).await?;
        }
        if let Err(err) = res {
            warn!(
                context,
                "Failed to download message rfc724_mid={rfc724_mid}: {:#}.", err
            );
            if !msg_is_downloaded_for(context, rfc724_mid).await? {
                // This is probably a classical email that vanished before we could download it
                warn!(
                    context,
                    "{rfc724_mid} download failed and there is no downloaded pre-message."
                );
                delete_from_downloads(context, rfc724_mid).await?;
            } else if available_post_msgs_contains_rfc724_mid(context, rfc724_mid).await? {
                warn!(
                    context,
                    "{rfc724_mid} is in available_post_msgs table but we failed to fetch it,
                    so set the message to DownloadState::Failure - probably it was deleted on the server in the meantime"
                );
                set_state_to_failure(context, rfc724_mid).await?;
                delete_from_downloads(context, rfc724_mid).await?;
                delete_from_available_post_msgs(context, rfc724_mid).await?;
            } else {
                // leave the message in DownloadState::InProgress;
                // it will be downloaded once it arrives.
            }
        }
    }

    Ok(())
}

/// Downloads known post-messages without pre-messages
/// in order to guard against lost pre-messages.
pub(crate) async fn download_known_post_messages_without_pre_message(
    context: &Context,
    session: &mut Session,
) -> Result<()> {
    let rfc724_mids = context
        .sql
        .query_map_vec("SELECT rfc724_mid FROM available_post_msgs", (), |row| {
            let rfc724_mid: String = row.get(0)?;
            Ok(rfc724_mid)
        })
        .await?;
    for rfc724_mid in &rfc724_mids {
        if msg_is_downloaded_for(context, rfc724_mid).await? {
            delete_from_available_post_msgs(context, rfc724_mid).await?;
            continue;
        }

        // Download the Post-Message unconditionally,
        // because the Pre-Message got lost.
        // The message may be in the wrong order,
        // but at least we have it at all.
        let res = download_msg(context, rfc724_mid.clone(), session).await;
        if let Ok(Some(())) = res {
            delete_from_available_post_msgs(context, rfc724_mid).await?;
        }
        if let Err(err) = res {
            warn!(
                context,
                "download_known_post_messages_without_pre_message: Failed to download message rfc724_mid={rfc724_mid}: {:#}.",
                err
            );
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use num_traits::FromPrimitive;

    use super::*;
    use crate::chat::send_msg;
    use crate::test_utils::TestContext;

    #[test]
    fn test_downloadstate_values() {
        // values may be written to disk and must not change
        assert_eq!(DownloadState::Done, DownloadState::default());
        assert_eq!(DownloadState::Done, DownloadState::from_i32(0).unwrap());
        assert_eq!(
            DownloadState::Available,
            DownloadState::from_i32(10).unwrap()
        );
        assert_eq!(DownloadState::Failure, DownloadState::from_i32(20).unwrap());
        assert_eq!(
            DownloadState::InProgress,
            DownloadState::from_i32(1000).unwrap()
        );
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn test_update_download_state() -> Result<()> {
        let t = TestContext::new_alice().await;
        let chat = t.create_chat_with_contact("Bob", "bob@example.org").await;

        let mut msg = Message::new_text("Hi Bob".to_owned());
        let msg_id = send_msg(&t, chat.id, &mut msg).await?;
        let msg = Message::load_from_db(&t, msg_id).await?;
        assert_eq!(msg.download_state(), DownloadState::Done);

        for s in &[
            DownloadState::Available,
            DownloadState::InProgress,
            DownloadState::Failure,
            DownloadState::Done,
            DownloadState::Done,
        ] {
            msg_id.update_download_state(&t, *s).await?;
            let msg = Message::load_from_db(&t, msg_id).await?;
            assert_eq!(msg.download_state(), *s);
        }
        t.sql
            .execute("DELETE FROM msgs WHERE id=?", (msg_id,))
            .await?;
        // Nothing to do is ok.
        msg_id
            .update_download_state(&t, DownloadState::Done)
            .await?;

        Ok(())
    }
}
