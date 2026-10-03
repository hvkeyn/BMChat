//! Split a file into several mail messages and assemble them again.
//!
//! One SMTP message cannot carry an arbitrarily large attachment. A file
//! bigger than [`PART_BYTES`] is sent as numbered parts that share one chat
//! message. The receiver writes the parts to disk and, once every part is
//! present and the hash matches, shows a single file.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use anyhow::{Context as _, Result, bail};
use num_traits::FromPrimitive;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::sync::Mutex;

use crate::blob::BlobObject;
use crate::log::warn;
use crate::constants::DC_CHAT_ID_TRASH;
use crate::contact::ContactId;
use crate::context::Context;
use crate::download::DownloadState;
use crate::events::EventType;
use crate::message::{self, Message, MessageState, MsgId, Viewtype};
use crate::mimefactory::MimeFactory;
use crate::mimeparser::MimeMessage;
use crate::param::Param;
use crate::receive_imf::ReceivedMsg;
use crate::smtp;
use crate::tools::{create_outgoing_rfc724_mid, create_smeared_timestamp, normalize_text};

/// Raw attachment bytes in one mail. After Base64 and encryption this stays
/// under the message size most mail servers accept.
pub(crate) const PART_BYTES: u64 = 4 * 1024 * 1024;

/// 4096 parts is 16 GiB. Larger than that is refused instead of enqueueing
/// an unbounded number of mails.
const MAX_PARTS: u64 = 4096;

const HEADER_VERSION: &str = "1";

fn assembly_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

struct PartSpec {
    index: u32,
    count: u32,
    total: u64,
    sha256: String,
    transfer_id: String,
    viewtype: Viewtype,
    mime: String,
    filename: String,
}

pub(crate) async fn queue_large_file(
    context: &Context,
    msg: &mut Message,
    factory: &mut MimeFactory,
    recipients: &[String],
) -> Result<Option<Vec<i64>>> {
    if !matches!(
        msg.viewtype,
        Viewtype::File
            | Viewtype::Image
            | Viewtype::Gif
            | Viewtype::Sticker
            | Viewtype::Audio
            | Viewtype::Voice
            | Viewtype::Video
    ) {
        return Ok(None);
    }
    let Some(path) = msg.get_file(context) else {
        return Ok(None);
    };
    let Some(total) = msg.get_filebytes(context).await? else {
        return Ok(None);
    };
    if total <= PART_BYTES {
        return Ok(None);
    }
    let count = total.div_ceil(PART_BYTES);
    if count > MAX_PARTS {
        let err = format!(
            "File is {total} bytes, which is more than {MAX_PARTS} mail parts can carry."
        );
        message::set_msg_failed(context, msg, &err).await?;
        bail!(err);
    }
    let count_u = u32::try_from(count).unwrap_or(u32::MAX);
    let sha = hash_file(&path).await?;
    let filename = msg.get_filename().unwrap_or_else(|| "file".to_string());
    let mime = msg.get_filemime().unwrap_or_default();
    let transfer_id = msg.rfc724_mid.clone();
    let mut recipients = recipients.to_vec();
    if context.get_config_bool(crate::config::Config::BccSelf).await? {
        smtp::add_self_recipients(context, &mut recipients, factory.will_be_encrypted()).await?;
    }
    if recipients.is_empty() {
        return Ok(None);
    }

    info!(
        context,
        "Splitting message {} ({total} bytes) into {count_u} mail parts.", msg.id
    );
    msg.param
        .set(Param::BmchatFileParts, count_u.to_string());
    context
        .sql
        .execute(
            "UPDATE msgs SET param=? WHERE id=?",
            (msg.param.to_string(), msg.id),
        )
        .await?;
    let mut row_ids = Vec::new();
    let mut subject = msg.subject.clone();
    let chunk_size = context.get_max_smtp_rcpt_to().await?;
    for index in 0..count_u {
        let start = u64::from(index) * PART_BYTES;
        let len = PART_BYTES.min(total - start);
        let bytes = read_range(&path, start, len).await?;
        let mut part = msg.clone();
        part.rfc724_mid = create_outgoing_rfc724_mid();
        if index != 0 {
            part.text.clear();
        }
        let chunk_name = format!("bmchat-part-{index}.bin");
        part.set_file_from_bytes(context, &chunk_name, &bytes, Some("application/octet-stream"))?;
        let header = encode_header(&PartSpec {
            index,
            count: count_u,
            total,
            sha256: sha.clone(),
            transfer_id: transfer_id.clone(),
            viewtype: msg.viewtype,
            mime: mime.clone(),
            filename: filename.clone(),
        });
        factory.load_file_part(part.clone(), header, index);
        let chunk_path = part.get_file(context);
        let rendered = factory.clone().render(context).await;
        if let Some(chunk_path) = chunk_path {
            let _ = tokio::fs::remove_file(chunk_path).await;
        }
        let rendered = match rendered {
            Ok(rendered) => rendered,
            Err(err) => {
                let _ = context
                    .sql
                    .execute("DELETE FROM smtp WHERE msg_id=?", (msg.id,))
                    .await;
                message::set_msg_failed(context, msg, &err.to_string()).await?;
                return Err(err);
            }
        };
        if msg
            .param
            .get_bool(Param::GuaranteeE2ee)
            .unwrap_or_default()
            && !rendered.is_encrypted
        {
            let err = "End-to-end-encryption unavailable unexpectedly.";
            let _ = context
                .sql
                .execute("DELETE FROM smtp WHERE msg_id=?", (msg.id,))
                .await;
            message::set_msg_failed(context, msg, err).await?;
            bail!(err);
        }
        if index == 0 {
            subject.clone_from(&rendered.subject);
            if rendered.is_encrypted {
                msg.param.set_int(Param::GuaranteeE2ee, 1);
            }
        }
        for recipients_chunk in recipients.chunks(chunk_size) {
            let row_id = context
                .sql
                .insert(
                    "INSERT INTO smtp (rfc724_mid, recipients, mime, msg_id) VALUES (?1, ?2, ?3, ?4)",
                    (
                        &rendered.rfc724_mid,
                        recipients_chunk.join(" "),
                        &rendered.message,
                        msg.id,
                    ),
                )
                .await?;
            row_ids.push(row_id);
        }
    }
    msg.subject = subject;
    context
        .sql
        .execute(
            "UPDATE msgs SET subject=?, param=? WHERE id=?",
            (&msg.subject, msg.param.to_string(), msg.id),
        )
        .await?;
    emit_progress(context, msg.id, 0, total);
    Ok(Some(row_ids))
}

pub(crate) async fn note_smtp_progress(context: &Context, msg_id: MsgId) -> Result<()> {
    let Some(msg) = Message::load_from_db_optional(context, msg_id).await? else {
        return Ok(());
    };
    let Some(count) = msg.param.get_int(Param::BmchatFileParts).filter(|n| *n > 0) else {
        return Ok(());
    };
    let pending = context
        .sql
        .count("SELECT COUNT(*) FROM smtp WHERE msg_id=?", (msg_id,))
        .await?;
    let total = msg.get_filebytes(context).await?.unwrap_or(0);
    let sent = (count as usize).saturating_sub(pending);
    let got = if pending == 0 {
        total
    } else {
        (sent as u64).saturating_mul(PART_BYTES).min(total)
    };
    emit_progress(context, msg_id, got, total);
    Ok(())
}

pub(crate) async fn cancel_remaining_smtp(context: &Context, msg_id: MsgId) -> Result<()> {
    let Some(msg) = Message::load_from_db_optional(context, msg_id).await? else {
        return Ok(());
    };
    if msg.param.get_int(Param::BmchatFileParts).unwrap_or(0) <= 0 {
        return Ok(());
    }
    context
        .sql
        .execute("DELETE FROM smtp WHERE msg_id=?", (msg_id,))
        .await?;
    info!(context, "Stopped the remaining parts of message {msg_id}.");
    Ok(())
}

pub(crate) async fn forget(context: &Context, transfer_id: &str) -> Result<()> {
    let dir = assembly_dir(context, transfer_id);
    if dir.exists() {
        tokio::fs::remove_dir_all(&dir)
            .await
            .with_context(|| format!("remove {}", dir.display()))?;
    }
    Ok(())
}

pub(crate) async fn ingest(
    context: &Context,
    mime_parser: &MimeMessage,
    chat_id: crate::chat::ChatId,
    from_id: ContactId,
    part_rfc724_mid: &str,
) -> Result<Option<ReceivedMsg>> {
    let Some(header) = mime_parser.get_header(crate::headerdef::HeaderDef::ChatBmchatFilePart)
    else {
        return Ok(None);
    };
    let _guard = assembly_lock().lock().await;
    let spec = match parse_header(header) {
        Ok(spec) => spec,
        Err(err) => {
            warn!(context, "Ignoring a broken file part: {err:#}.");
            return Ok(Some(tombstone(context, part_rfc724_mid).await?));
        }
    };
    if spec.index >= spec.count || spec.total == 0 {
        warn!(context, "Ignoring a file part outside its range.");
        return Ok(Some(tombstone(context, part_rfc724_mid).await?));
    }

    if let Some(existing_id) = message::rfc724_mid_exists(context, &spec.transfer_id).await?
        && let Some(existing) = Message::load_from_db_optional(context, existing_id).await?
    {
        if existing.chat_id.is_trash() {
            let _ = forget(context, &spec.transfer_id).await;
            return Ok(Some(tombstone(context, part_rfc724_mid).await?));
        }
        if existing.download_state == DownloadState::Done && existing.get_file(context).is_some() {
            return Ok(Some(tombstone(context, part_rfc724_mid).await?));
        }
    }

    if chat_id.is_special() {
        return Ok(Some(tombstone(context, part_rfc724_mid).await?));
    }

    let dir = assembly_dir(context, &spec.transfer_id);
    tokio::fs::create_dir_all(&dir).await?;
    if dir.join("failed").exists() {
        return Ok(Some(tombstone(context, part_rfc724_mid).await?));
    }
    if let Some(part) = mime_parser.parts.first()
        && let Some(name) = part.param.get(Param::File)
        && let Ok(blob) = BlobObject::from_name(context, name)
    {
        let dest = dir.join(format!("{}.bin", spec.index));
        tokio::fs::copy(blob.to_abs_path(), &dest).await?;
    } else {
        warn!(context, "File part {} has no attachment.", spec.index);
        return Ok(Some(tombstone(context, part_rfc724_mid).await?));
    }
    if spec.index == 0 {
        if let Some(text) = mime_parser.parts.first().map(|part| part.msg.clone()) {
            tokio::fs::write(dir.join("text.txt"), text.as_bytes()).await?;
        }
    }
    write_meta(&dir, &spec).await?;

    let msg_id = ensure_placeholder(context, mime_parser, chat_id, from_id, &spec, &dir).await?;
    if spec.index == 0 {
        let text = tokio::fs::read_to_string(dir.join("text.txt"))
            .await
            .unwrap_or_default();
        if !text.is_empty() {
            context
                .sql
                .execute(
                    "UPDATE msgs SET txt=?, txt_normalized=? WHERE id=? AND txt=''",
                    (&text, normalize_text(&text), msg_id),
                )
                .await?;
        }
    }
    let got = bytes_on_disk(&dir, spec.count).await?;
    emit_progress(context, msg_id, got.min(spec.total), spec.total);
    context.emit_msgs_changed(chat_id, msg_id);

    if parts_complete(&dir, spec.count).await? {
        finish(context, mime_parser, chat_id, msg_id, &spec, &dir).await?;
    }
    Ok(Some(tombstone(context, part_rfc724_mid).await?))
}

async fn ensure_placeholder(
    context: &Context,
    mime_parser: &MimeMessage,
    chat_id: crate::chat::ChatId,
    from_id: ContactId,
    spec: &PartSpec,
    dir: &Path,
) -> Result<MsgId> {
    if let Some(existing_id) = message::rfc724_mid_exists(context, &spec.transfer_id).await?
        && Message::load_from_db_optional(context, existing_id)
            .await?
            .is_some()
    {
        return Ok(existing_id);
    }
    let text = tokio::fs::read_to_string(dir.join("text.txt"))
        .await
        .unwrap_or_default();
    let mut param = crate::param::Params::new();
    param.set(Param::Filename, &spec.filename);
    param.set(Param::PostMessageFileBytes, spec.total.to_string());
    if !spec.mime.is_empty() {
        param.set(Param::MimeType, &spec.mime);
    }
    if mime_parser.was_encrypted() {
        param.set_int(Param::GuaranteeE2ee, 1);
    }
    let state = if mime_parser.incoming {
        MessageState::InNoticed
    } else {
        MessageState::OutPending
    };
    let to_id = if mime_parser.incoming {
        ContactId::SELF
    } else {
        ContactId::UNDEFINED
    };
    let sort = if mime_parser.timestamp_sent > 0 {
        mime_parser.timestamp_sent
    } else {
        create_smeared_timestamp(context)
    };
    let row_id = context
        .sql
        .insert(
            "INSERT INTO msgs (rfc724_mid, chat_id) VALUES (?, ?)",
            (&spec.transfer_id, chat_id),
        )
        .await?;
    let msg_id = MsgId::new(u32::try_from(row_id)?);
    context
        .sql
        .execute(
            "UPDATE msgs SET from_id=?, to_id=?, timestamp=?, timestamp_sent=?, timestamp_rcvd=?, \
             type=?, state=?, msgrmsg=1, txt=?, txt_normalized=?, param=?, hidden=0, bytes=?, \
             download_state=? WHERE id=?",
            (
                from_id,
                to_id,
                sort,
                mime_parser.timestamp_sent,
                mime_parser.timestamp_rcvd,
                spec.viewtype,
                state,
                &text,
                normalize_text(&text),
                param.to_string(),
                spec.total as i64,
                DownloadState::InProgress,
                msg_id,
            ),
        )
        .await?;
    chat_id.unarchive_if_not_muted(context, state).await?;
    Ok(msg_id)
}

async fn finish(
    context: &Context,
    mime_parser: &MimeMessage,
    chat_id: crate::chat::ChatId,
    msg_id: MsgId,
    spec: &PartSpec,
    dir: &Path,
) -> Result<()> {
    let assembled = dir.join("assembled.bin");
    if let Err(err) = concat_parts(dir, spec.count, &assembled, &spec.sha256).await {
        warn!(context, "File parts for {} did not assemble: {err:#}.", spec.filename);
        let _ = tokio::fs::write(dir.join("failed"), b"1").await;
        context
            .sql
            .execute(
                "UPDATE msgs SET download_state=?, error=? WHERE id=?",
                (
                    DownloadState::Failure,
                    "The file parts do not match, so the file was not saved.",
                    msg_id,
                ),
            )
            .await?;
        context.emit_msgs_changed(chat_id, msg_id);
        return Ok(());
    }
    let mut message = Message::load_from_db(context, msg_id).await?;
    let mime = if spec.mime.is_empty() {
        None
    } else {
        Some(spec.mime.as_str())
    };
    message.set_file_and_deduplicate(context, &assembled, Some(&spec.filename), mime)?;
    let state = if mime_parser.incoming {
        MessageState::InFresh
    } else {
        MessageState::OutDelivered
    };
    context
        .sql
        .execute(
            "UPDATE msgs SET param=?, type=?, bytes=?, download_state=?, state=?, error='' WHERE id=?",
            (
                message.param.to_string(),
                spec.viewtype,
                spec.total as i64,
                DownloadState::Done,
                state,
                msg_id,
            ),
        )
        .await?;
    let _ = tokio::fs::remove_dir_all(dir).await;
    if mime_parser.incoming {
        let _ = chat_id
            .unarchive_if_not_muted(context, MessageState::InFresh)
            .await;
    }
    emit_progress(context, msg_id, spec.total, spec.total);
    if mime_parser.incoming {
        context.emit_incoming_msg(chat_id, msg_id);
    } else {
        context.emit_msgs_changed(chat_id, msg_id);
    }
    info!(context, "Assembled {} ({} bytes).", spec.filename, spec.total);
    Ok(())
}

async fn tombstone(context: &Context, rfc724_mid: &str) -> Result<ReceivedMsg> {
    let msg_id = if let Some(id) = message::rfc724_mid_exists(context, rfc724_mid).await? {
        id
    } else {
        message::insert_tombstone(context, rfc724_mid).await?
    };
    Ok(ReceivedMsg {
        chat_id: DC_CHAT_ID_TRASH,
        state: MessageState::InSeen,
        hidden: true,
        sort_timestamp: 0,
        msg_ids: vec![msg_id],
        needs_delete_job: false,
    })
}

fn emit_progress(context: &Context, msg_id: MsgId, got: u64, total: u64) {
    context.emit_event(EventType::Info(format!(
        "bmchat-xfer {} {got} {total}",
        msg_id.to_u32()
    )));
}

fn assembly_dir(context: &Context, transfer_id: &str) -> PathBuf {
    let mut hasher = Sha256::new();
    hasher.update(transfer_id.as_bytes());
    context
        .get_blobdir()
        .join("bmchat-parts")
        .join(hex_encode(&hasher.finalize()))
}

async fn write_meta(dir: &Path, spec: &PartSpec) -> Result<()> {
    let body = format!(
        "v1\n{}\n{}\n{}\n{}\n{}\n{}\n",
        spec.count,
        spec.total,
        spec.sha256,
        spec.viewtype as u32,
        percent_encode(&spec.mime),
        percent_encode(&spec.filename),
    );
    let tmp = dir.join("meta.tmp");
    tokio::fs::write(&tmp, body.as_bytes()).await?;
    tokio::fs::rename(tmp, dir.join("meta.txt")).await?;
    Ok(())
}

async fn bytes_on_disk(dir: &Path, count: u32) -> Result<u64> {
    let mut got = 0u64;
    for index in 0..count {
        let path = dir.join(format!("{index}.bin"));
        if let Ok(meta) = tokio::fs::metadata(&path).await {
            got = got.saturating_add(meta.len());
        }
    }
    Ok(got)
}

async fn parts_complete(dir: &Path, count: u32) -> Result<bool> {
    for index in 0..count {
        if !dir.join(format!("{index}.bin")).exists() {
            return Ok(false);
        }
    }
    Ok(true)
}

async fn concat_parts(dir: &Path, count: u32, dest: &Path, expect: &str) -> Result<()> {
    let mut out = tokio::fs::File::create(dest).await?;
    let mut hasher = Sha256::new();
    for index in 0..count {
        let bytes = tokio::fs::read(dir.join(format!("{index}.bin"))).await?;
        hasher.update(&bytes);
        out.write_all(&bytes).await?;
    }
    out.flush().await?;
    let got = hex_encode(&hasher.finalize());
    if got != expect {
        bail!("hash {got} != {expect}");
    }
    Ok(())
}

async fn hash_file(path: &Path) -> Result<String> {
    let mut file = tokio::fs::File::open(path).await?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1024 * 1024];
    loop {
        let n = file.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex_encode(&hasher.finalize()))
}

async fn read_range(path: &Path, start: u64, len: u64) -> Result<Vec<u8>> {
    let mut file = tokio::fs::File::open(path).await?;
    file.seek(std::io::SeekFrom::Start(start)).await?;
    let mut buf = vec![0u8; usize::try_from(len)?];
    file.read_exact(&mut buf).await?;
    Ok(buf)
}

fn encode_header(spec: &PartSpec) -> String {
    format!(
        "{HEADER_VERSION}|{}|{}|{}|{}|{}|{}|{}|{}",
        spec.index,
        spec.count,
        spec.total,
        spec.sha256,
        percent_encode(&spec.transfer_id),
        spec.viewtype as u32,
        percent_encode(&spec.mime),
        percent_encode(&spec.filename),
    )
}

fn parse_header(header: &str) -> Result<PartSpec> {
    let fields: Vec<&str> = header.trim().split('|').collect();
    if fields.len() != 9 || fields[0] != HEADER_VERSION {
        bail!("unexpected file-part header");
    }
    let viewtype = Viewtype::from_u32(fields[6].parse()?).unwrap_or(Viewtype::File);
    Ok(PartSpec {
        index: fields[1].parse()?,
        count: fields[2].parse()?,
        total: fields[3].parse()?,
        sha256: fields[4].to_string(),
        transfer_id: percent_decode(fields[5])?,
        viewtype,
        mime: percent_decode(fields[7])?,
        filename: percent_decode(fields[8])?,
    })
}

fn percent_encode(value: &str) -> String {
    let mut out = String::new();
    for byte in value.as_bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b'~') {
            out.push(*byte as char);
        } else {
            out.push('%');
            out.push(HEX[usize::from(byte >> 4)] as char);
            out.push(HEX[usize::from(byte & 0xf)] as char);
        }
    }
    out
}

fn percent_decode(value: &str) -> Result<String> {
    let bytes = value.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if i + 2 >= bytes.len() {
                bail!("truncated percent-escape");
            }
            let hi = hex_val(bytes[i + 1])?;
            let lo = hex_val(bytes[i + 2])?;
            out.push((hi << 4) | lo);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    Ok(String::from_utf8(out)?)
}

fn hex_val(byte: u8) -> Result<u8> {
    match byte {
        b'0'..=b'9' => Ok(byte - b'0'),
        b'a'..=b'f' => Ok(byte - b'a' + 10),
        b'A'..=b'F' => Ok(byte - b'A' + 10),
        _ => bail!("bad hex"),
    }
}

const HEX: &[u8] = b"0123456789abcdef";

fn hex_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(HEX[usize::from(byte >> 4)] as char);
        out.push(HEX[usize::from(byte & 0xf)] as char);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_roundtrip() {
        let spec = PartSpec {
            index: 2,
            count: 5,
            total: 20_000_000,
            sha256: "abc".to_string(),
            transfer_id: "msg@localhost".to_string(),
            viewtype: Viewtype::File,
            mime: "application/octet-stream".to_string(),
            filename: "a file.apk".to_string(),
        };
        let parsed = parse_header(&encode_header(&spec)).unwrap();
        assert_eq!(parsed.index, 2);
        assert_eq!(parsed.count, 5);
        assert_eq!(parsed.total, 20_000_000);
        assert_eq!(parsed.transfer_id, "msg@localhost");
        assert_eq!(parsed.filename, "a file.apk");
        assert_eq!(parsed.viewtype, Viewtype::File);
    }
}
