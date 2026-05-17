//! Module to collect and display Disk Space Usage of a Profile.
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::Path;

use crate::constants::{DC_CHAT_ID_TRASH, DC_MSG_ID_LAST_SPECIAL};
use crate::context::Context;
use crate::download::DownloadState;
use crate::message::{MsgId, Viewtype};
use crate::param::{Param, Params};
use crate::tools::{delete_file, time};
use anyhow::{Context as _, Result};
use humansize::{BINARY, format_size};
use serde::{Deserialize, Serialize};
use walkdir::WalkDir;

/// Storage Usage Report
/// Useful for debugging space usage problems in the deltachat database.
#[derive(Debug)]
pub struct StorageUsage {
    /// Total database size, subtract this from the backup size to estimate size of all blobs
    pub db_size: u64,
    /// size and row count of the 10 biggest tables
    pub largest_tables: Vec<(String, u64, Option<u64>)>,
    /// count and total size of status updates
    /// for the 10 webxdc apps with the most size usage in status updates
    pub largest_webxdc_data: Vec<(MsgId, u64, u64)>,
    /// Total size of all files in the blobdir
    pub blobdir_size: u64,
}

/// User-facing file category used by storage management UIs.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum StorageCategory {
    /// Images, animated images and stickers.
    Images,
    /// Video attachments.
    Videos,
    /// Audio and voice attachments.
    Audio,
    /// Generic documents and vCards.
    Files,
    /// Webxdc app archives.
    Webxdc,
    /// Anything that does not fit a user-facing media bucket.
    Other,
}

/// Storage usage for one category.
#[derive(Debug, Clone, Serialize)]
pub struct StorageCategoryUsage {
    /// Category represented by this row.
    pub category: StorageCategory,
    /// Total bytes attributed to this category.
    pub bytes: u64,
    /// Bytes that can be cleared locally and downloaded again.
    pub evictable_bytes: u64,
    /// Number of unique blob files in this category.
    pub files: u64,
}

/// Storage usage attributed to one chat.
#[derive(Debug, Clone, Serialize)]
pub struct StorageChatUsage {
    /// Chat ID.
    pub chat_id: u32,
    /// Best-effort chat name for UI display.
    pub name: String,
    /// Total bytes attributed to this chat.
    pub bytes: u64,
    /// Bytes that can be cleared locally and downloaded again.
    pub evictable_bytes: u64,
    /// Number of unique blob files attributed to this chat.
    pub files: u64,
    /// Timestamp of the newest message contributing storage.
    pub last_timestamp: i64,
}

/// Structured storage usage for UI clients.
#[derive(Debug, Clone, Serialize)]
pub struct StorageUsageSummary {
    /// Combined account size: database plus blob directory.
    pub total_bytes: u64,
    /// SQLite database size.
    pub database_bytes: u64,
    /// Blob directory size.
    pub blobdir_bytes: u64,
    /// Bytes that can be cleared locally and downloaded again.
    pub evictable_bytes: u64,
    /// Usage grouped by media category.
    pub by_category: Vec<StorageCategoryUsage>,
    /// Usage grouped by chat.
    pub by_chat: Vec<StorageChatUsage>,
}

/// Filters for local cache cleanup.
#[derive(Debug, Clone, Default)]
pub struct StorageClearOptions {
    /// Optional category filter.
    pub categories: Option<Vec<StorageCategory>>,
    /// Optional chat filter.
    pub chat_ids: Option<Vec<u32>>,
    /// Only clear messages older than this many seconds.
    pub older_than_seconds: Option<i64>,
    /// Stop after freeing about this many bytes.
    pub limit_bytes: Option<u64>,
}

/// Result of local cache cleanup.
#[derive(Debug, Clone, Serialize)]
pub struct StorageClearResult {
    /// Bytes removed from local storage.
    pub freed_bytes: u64,
    /// Blob files removed from local storage.
    pub cleared_files: u64,
    /// Messages converted back to downloadable placeholders.
    pub cleared_messages: u64,
    /// Files skipped because redownload is not safe.
    pub skipped_files: u64,
    /// Bytes skipped because redownload is not safe.
    pub skipped_bytes: u64,
}

#[derive(Debug, Clone)]
struct StoredBlobRef {
    msg_id: MsgId,
    chat_id: u32,
    chat_name: String,
    viewtype: Viewtype,
    timestamp: i64,
    param: Params,
    blob_key: String,
    bytes: u64,
    evictable: bool,
}

#[derive(Debug, Default)]
struct CategoryAccumulator {
    bytes: u64,
    evictable_bytes: u64,
    files: u64,
}

#[derive(Debug, Default)]
struct ChatAccumulator {
    name: String,
    bytes: u64,
    evictable_bytes: u64,
    files: u64,
    last_timestamp: i64,
}

impl std::fmt::Display for StorageUsage {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        writeln!(f, "Storage Usage:")?;
        let blobdir_size = format_size(self.blobdir_size, BINARY);
        writeln!(f, "[Blob Directory Size]: {blobdir_size}")?;
        let human_db_size = format_size(self.db_size, BINARY);
        writeln!(f, "[Database Size]: {human_db_size}")?;
        writeln!(f, "[Largest Tables]:")?;
        for (name, size, row_count) in &self.largest_tables {
            let human_table_size = format_size(*size, BINARY);
            writeln!(
                f,
                "   {name:<20} {human_table_size:>10}, {row_count:>6} rows",
                name = format!("{name}:"),
                row_count = row_count.map(|c| c.to_string()).unwrap_or("?".to_owned())
            )?;
        }
        writeln!(f, "[Webxdc With Biggest Status Update Space Usage]:")?;
        for (msg_id, size, update_count) in &self.largest_webxdc_data {
            let human_size = format_size(*size, BINARY);
            writeln!(
                f,
                "   {msg_id:<8} {human_size:>10} across {update_count:>5} updates",
                msg_id = format!("{msg_id}:")
            )?;
        }
        Ok(())
    }
}

/// Get storage usage information for the Context's database
#[expect(clippy::arithmetic_side_effects)]
pub async fn get_storage_usage(ctx: &Context) -> Result<StorageUsage> {
    let context_clone = ctx.clone();
    let blobdir_size =
        tokio::task::spawn_blocking(move || get_blobdir_storage_usage(&context_clone));

    let page_size: u64 = ctx
        .sql
        .query_get_value("PRAGMA page_size", ())
        .await?
        .unwrap_or_default();
    let page_count: u64 = ctx
        .sql
        .query_get_value("PRAGMA page_count", ())
        .await?
        .unwrap_or_default();

    let mut largest_tables = ctx
        .sql
        .query_map_vec(
            "SELECT name,
                SUM(pgsize) AS size
                FROM dbstat
                WHERE name IN (SELECT name FROM sqlite_master WHERE type='table')
                GROUP BY name ORDER BY size DESC LIMIT 10",
            (),
            |row| {
                let name: String = row.get(0)?;
                let size: u64 = row.get(1)?;
                Ok((name, size, None))
            },
        )
        .await?;

    for row in &mut largest_tables {
        let name = &row.0;
        let row_count: Result<Option<u64>> = ctx
            .sql
            // SECURITY: the table name comes from the db, not from the user
            .query_get_value(&format!("SELECT COUNT(*) FROM {name}"), ())
            .await;
        row.2 = row_count.unwrap_or_default();
    }

    let largest_webxdc_data = ctx
        .sql
        .query_map_vec(
            "SELECT msg_id, SUM(length(update_item)) as size, COUNT(*) as update_count
                 FROM msgs_status_updates
                 GROUP BY msg_id ORDER BY size DESC LIMIT 10",
            (),
            |row| {
                let msg_id: MsgId = row.get(0)?;
                let size: u64 = row.get(1)?;
                let count: u64 = row.get(2)?;

                Ok((msg_id, size, count))
            },
        )
        .await?;

    let blobdir_size = blobdir_size.await?;

    Ok(StorageUsage {
        db_size: page_size * page_count,
        largest_tables,
        largest_webxdc_data,
        blobdir_size,
    })
}

/// Get structured storage usage information for UI clients.
#[expect(clippy::arithmetic_side_effects)]
pub async fn get_storage_usage_summary(ctx: &Context) -> Result<StorageUsageSummary> {
    let legacy_usage = get_storage_usage(ctx).await?;
    let blob_refs = collect_blob_refs(ctx).await?;

    let mut unique_total: HashMap<String, (u64, bool)> = HashMap::new();
    let mut by_category: BTreeMap<String, (StorageCategory, CategoryAccumulator)> = BTreeMap::new();
    let mut by_chat: BTreeMap<u32, ChatAccumulator> = BTreeMap::new();

    for blob_ref in blob_refs {
        let first_seen = unique_total
            .insert(
                blob_ref.blob_key.clone(),
                (blob_ref.bytes, blob_ref.evictable),
            )
            .is_none();
        if !first_seen {
            continue;
        }

        let category = category_for_viewtype(blob_ref.viewtype);
        let category_key = format!("{category:?}");
        let category_entry = by_category
            .entry(category_key)
            .or_insert((category, CategoryAccumulator::default()));
        category_entry.1.bytes += blob_ref.bytes;
        category_entry.1.files += 1;
        if blob_ref.evictable {
            category_entry.1.evictable_bytes += blob_ref.bytes;
        }

        let chat_entry = by_chat.entry(blob_ref.chat_id).or_default();
        chat_entry.name = if blob_ref.chat_name.is_empty() {
            format!("Chat {}", blob_ref.chat_id)
        } else {
            blob_ref.chat_name
        };
        chat_entry.bytes += blob_ref.bytes;
        chat_entry.files += 1;
        chat_entry.last_timestamp = chat_entry.last_timestamp.max(blob_ref.timestamp);
        if blob_ref.evictable {
            chat_entry.evictable_bytes += blob_ref.bytes;
        }
    }

    let evictable_bytes = unique_total
        .values()
        .filter_map(|(bytes, evictable)| evictable.then_some(*bytes))
        .sum();

    Ok(StorageUsageSummary {
        total_bytes: legacy_usage.db_size + legacy_usage.blobdir_size,
        database_bytes: legacy_usage.db_size,
        blobdir_bytes: legacy_usage.blobdir_size,
        evictable_bytes,
        by_category: by_category
            .into_values()
            .map(|(category, item)| StorageCategoryUsage {
                category,
                bytes: item.bytes,
                evictable_bytes: item.evictable_bytes,
                files: item.files,
            })
            .collect(),
        by_chat: by_chat
            .into_iter()
            .map(|(chat_id, item)| StorageChatUsage {
                chat_id,
                name: item.name,
                bytes: item.bytes,
                evictable_bytes: item.evictable_bytes,
                files: item.files,
                last_timestamp: item.last_timestamp,
            })
            .collect(),
    })
}

/// Clears locally cached blobs while keeping messages available for re-download.
#[expect(clippy::arithmetic_side_effects)]
pub async fn clear_local_storage(
    ctx: &Context,
    options: StorageClearOptions,
) -> Result<StorageClearResult> {
    let category_filter: Option<HashSet<StorageCategory>> =
        options.categories.map(|items| items.into_iter().collect());
    let chat_filter: Option<HashSet<u32>> =
        options.chat_ids.map(|items| items.into_iter().collect());
    let threshold_timestamp = options
        .older_than_seconds
        .map(|seconds| time().saturating_sub(seconds));

    let mut result = StorageClearResult {
        freed_bytes: 0,
        cleared_files: 0,
        cleared_messages: 0,
        skipped_files: 0,
        skipped_bytes: 0,
    };

    let mut selected = Vec::new();
    for blob_ref in collect_blob_refs(ctx).await? {
        let category = category_for_viewtype(blob_ref.viewtype);
        if category_filter
            .as_ref()
            .is_some_and(|categories| !categories.contains(&category))
        {
            continue;
        }
        if chat_filter
            .as_ref()
            .is_some_and(|chat_ids| !chat_ids.contains(&blob_ref.chat_id))
        {
            continue;
        }
        if threshold_timestamp.is_some_and(|threshold| blob_ref.timestamp > threshold) {
            continue;
        }
        if !blob_ref.evictable {
            result.skipped_files += 1;
            result.skipped_bytes += blob_ref.bytes;
            continue;
        }
        selected.push(blob_ref);
    }

    selected.sort_by_key(|blob_ref| blob_ref.timestamp);
    let mut touched_blobs = HashSet::new();
    let mut touched_chats = HashSet::new();

    for blob_ref in selected {
        if options
            .limit_bytes
            .is_some_and(|limit| result.freed_bytes >= limit)
        {
            break;
        }

        let mut new_param = blob_ref.param.clone();
        new_param
            .remove(Param::File)
            .remove(Param::Width)
            .remove(Param::Height)
            .remove(Param::Duration)
            .set_i64(Param::PostMessageFileBytes, blob_ref.bytes as i64)
            .set_i64(Param::PostMessageViewtype, blob_ref.viewtype as i64);

        let changed = ctx
            .sql
            .execute(
                "UPDATE msgs
                 SET param=?, type=?, bytes=0, download_state=?
                 WHERE id=? AND download_state=?",
                (
                    new_param.to_string(),
                    Viewtype::Text,
                    DownloadState::Available,
                    blob_ref.msg_id,
                    DownloadState::Done,
                ),
            )
            .await?;
        if changed == 0 {
            continue;
        }

        result.cleared_messages += 1;
        touched_chats.insert(blob_ref.chat_id);
        touched_blobs.insert(blob_ref.blob_key.clone());

        if !is_blob_referenced(ctx, &blob_ref.blob_key).await? {
            let freed = delete_blob_key(ctx, &blob_ref.blob_key).await?;
            if freed > 0 {
                result.freed_bytes += freed;
                result.cleared_files += 1;
            }
        }
    }

    for chat_id in touched_chats {
        ctx.emit_msgs_changed_without_msg_id(crate::chat::ChatId::new(chat_id));
    }

    // Some selected messages can share a blob. If the last reference disappeared after a later
    // update, make a final pass over every touched blob.
    for blob_key in touched_blobs {
        if !is_blob_referenced(ctx, &blob_key).await? {
            let freed = delete_blob_key(ctx, &blob_key).await?;
            if freed > 0 {
                result.freed_bytes += freed;
                result.cleared_files += 1;
            }
        }
    }

    Ok(result)
}

/// Returns storage usage of the blob directory
#[expect(clippy::arithmetic_side_effects)]
pub fn get_blobdir_storage_usage(ctx: &Context) -> u64 {
    WalkDir::new(ctx.get_blobdir())
        .max_depth(2)
        .into_iter()
        .filter_map(|entry| entry.ok())
        .filter_map(|entry| entry.metadata().ok())
        .filter(|metadata| metadata.is_file())
        .fold(0, |acc, m| acc + m.len())
}

async fn collect_blob_refs(ctx: &Context) -> Result<Vec<StoredBlobRef>> {
    let rows = ctx
        .sql
        .query_map_vec(
            "SELECT
                m.id,
                m.chat_id,
                COALESCE(c.name, ''),
                m.type,
                m.timestamp,
                m.param,
                m.download_state,
                m.rfc724_mid
             FROM msgs m
             LEFT JOIN chats c ON c.id=m.chat_id
             WHERE m.id > ?
               AND m.chat_id != ?
               AND m.hidden = 0
               AND m.param LIKE '%f=$BLOBDIR/%'",
            (DC_MSG_ID_LAST_SPECIAL, DC_CHAT_ID_TRASH),
            |row| {
                let param: Params = row.get::<_, String>(5)?.parse().unwrap_or_default();
                Ok((
                    row.get::<_, MsgId>(0)?,
                    row.get::<_, u32>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, Viewtype>(3)?,
                    row.get::<_, i64>(4)?,
                    param,
                    row.get::<_, DownloadState>(6)?,
                    row.get::<_, String>(7)?,
                ))
            },
        )
        .await?;

    let mut result = Vec::new();
    for (msg_id, chat_id, chat_name, viewtype, timestamp, param, download_state, rfc724_mid) in rows
    {
        let Some(blob_key) = blob_key_from_param(&param) else {
            continue;
        };
        let path = ctx.get_blobdir().join(&blob_key);
        let Ok(metadata) = tokio::fs::metadata(&path).await else {
            continue;
        };
        if !metadata.is_file() {
            continue;
        }
        let evictable = download_state == DownloadState::Done
            && !rfc724_mid.is_empty()
            && has_imap_copy(ctx, &rfc724_mid).await?;
        result.push(StoredBlobRef {
            msg_id,
            chat_id,
            chat_name,
            viewtype,
            timestamp,
            param,
            blob_key,
            bytes: metadata.len(),
            evictable,
        });
    }
    Ok(result)
}

fn blob_key_from_param(param: &Params) -> Option<String> {
    param
        .get(Param::File)?
        .strip_prefix("$BLOBDIR/")
        .map(ToOwned::to_owned)
}

fn category_for_viewtype(viewtype: Viewtype) -> StorageCategory {
    match viewtype {
        Viewtype::Image | Viewtype::Gif | Viewtype::Sticker => StorageCategory::Images,
        Viewtype::Video => StorageCategory::Videos,
        Viewtype::Audio | Viewtype::Voice => StorageCategory::Audio,
        Viewtype::File | Viewtype::Vcard => StorageCategory::Files,
        Viewtype::Webxdc => StorageCategory::Webxdc,
        Viewtype::Unknown | Viewtype::Text | Viewtype::Call => StorageCategory::Other,
    }
}

async fn has_imap_copy(ctx: &Context, rfc724_mid: &str) -> Result<bool> {
    ctx.sql
        .exists(
            "SELECT COUNT(*) FROM imap
             WHERE rfc724_mid=? AND target!='' AND uid>0",
            (rfc724_mid,),
        )
        .await
}

async fn is_blob_referenced(ctx: &Context, blob_key: &str) -> Result<bool> {
    let blob_name = format!("$BLOBDIR/{blob_key}");
    let like_blob_name = format!("%{blob_name}%");
    if ctx
        .sql
        .exists(
            "SELECT COUNT(*) FROM msgs WHERE param LIKE ? AND chat_id != ?",
            (&like_blob_name, DC_CHAT_ID_TRASH),
        )
        .await?
    {
        return Ok(true);
    }
    if ctx
        .sql
        .exists(
            "SELECT COUNT(*) FROM chats WHERE param LIKE ?",
            (&like_blob_name,),
        )
        .await?
    {
        return Ok(true);
    }
    if ctx
        .sql
        .exists(
            "SELECT COUNT(*) FROM contacts WHERE param LIKE ?",
            (&like_blob_name,),
        )
        .await?
    {
        return Ok(true);
    }
    if ctx
        .sql
        .exists(
            "SELECT COUNT(*) FROM config WHERE value LIKE ?",
            (&like_blob_name,),
        )
        .await?
    {
        return Ok(true);
    }
    ctx.sql
        .exists(
            "SELECT COUNT(*) FROM http_cache WHERE blobname=? OR blobname=?",
            (&blob_name, blob_key),
        )
        .await
}

async fn delete_blob_key(ctx: &Context, blob_key: &str) -> Result<u64> {
    let path = ctx.get_blobdir().join(blob_key);
    delete_file_if_exists(ctx, &path).await
}

async fn delete_file_if_exists(ctx: &Context, path: &Path) -> Result<u64> {
    let Ok(metadata) = tokio::fs::metadata(path).await else {
        return Ok(0);
    };
    if !metadata.is_file() {
        return Ok(0);
    }
    let size = metadata.len();
    delete_file(ctx, path)
        .await
        .with_context(|| format!("failed to delete cached blob {}", path.display()))?;
    Ok(size)
}
