use deltachat::storage_usage as core_storage;
use serde::{Deserialize, Serialize};
use typescript_type_def::TypeDef;

#[derive(Clone, Copy, Debug, Serialize, Deserialize, TypeDef, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum StorageCategory {
    Images,
    Videos,
    Audio,
    Files,
    Webxdc,
    Other,
}

#[derive(Debug, Serialize, TypeDef, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct StorageCategoryUsage {
    pub category: StorageCategory,
    pub bytes: u64,
    pub evictable_bytes: u64,
    pub files: u64,
}

#[derive(Debug, Serialize, TypeDef, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct StorageChatUsage {
    pub chat_id: u32,
    pub name: String,
    pub bytes: u64,
    pub evictable_bytes: u64,
    pub files: u64,
    pub last_timestamp: i64,
}

#[derive(Debug, Serialize, TypeDef, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct StorageUsage {
    pub total_bytes: u64,
    pub database_bytes: u64,
    pub blobdir_bytes: u64,
    pub evictable_bytes: u64,
    pub by_category: Vec<StorageCategoryUsage>,
    pub by_chat: Vec<StorageChatUsage>,
}

#[derive(Debug, Deserialize, TypeDef, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct StorageClearRequest {
    pub categories: Option<Vec<StorageCategory>>,
    pub chat_ids: Option<Vec<u32>>,
    pub older_than_seconds: Option<i64>,
    pub limit_bytes: Option<u64>,
}

#[derive(Debug, Serialize, TypeDef, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct StorageClearResult {
    pub freed_bytes: u64,
    pub cleared_files: u64,
    pub cleared_messages: u64,
    pub skipped_files: u64,
    pub skipped_bytes: u64,
}

impl From<core_storage::StorageCategory> for StorageCategory {
    fn from(value: core_storage::StorageCategory) -> Self {
        match value {
            core_storage::StorageCategory::Images => Self::Images,
            core_storage::StorageCategory::Videos => Self::Videos,
            core_storage::StorageCategory::Audio => Self::Audio,
            core_storage::StorageCategory::Files => Self::Files,
            core_storage::StorageCategory::Webxdc => Self::Webxdc,
            core_storage::StorageCategory::Other => Self::Other,
        }
    }
}

impl From<StorageCategory> for core_storage::StorageCategory {
    fn from(value: StorageCategory) -> Self {
        match value {
            StorageCategory::Images => Self::Images,
            StorageCategory::Videos => Self::Videos,
            StorageCategory::Audio => Self::Audio,
            StorageCategory::Files => Self::Files,
            StorageCategory::Webxdc => Self::Webxdc,
            StorageCategory::Other => Self::Other,
        }
    }
}

impl From<core_storage::StorageUsageSummary> for StorageUsage {
    fn from(value: core_storage::StorageUsageSummary) -> Self {
        Self {
            total_bytes: value.total_bytes,
            database_bytes: value.database_bytes,
            blobdir_bytes: value.blobdir_bytes,
            evictable_bytes: value.evictable_bytes,
            by_category: value
                .by_category
                .into_iter()
                .map(|item| StorageCategoryUsage {
                    category: item.category.into(),
                    bytes: item.bytes,
                    evictable_bytes: item.evictable_bytes,
                    files: item.files,
                })
                .collect(),
            by_chat: value
                .by_chat
                .into_iter()
                .map(|item| StorageChatUsage {
                    chat_id: item.chat_id,
                    name: item.name,
                    bytes: item.bytes,
                    evictable_bytes: item.evictable_bytes,
                    files: item.files,
                    last_timestamp: item.last_timestamp,
                })
                .collect(),
        }
    }
}

impl From<StorageClearRequest> for core_storage::StorageClearOptions {
    fn from(value: StorageClearRequest) -> Self {
        Self {
            categories: value
                .categories
                .map(|categories| categories.into_iter().map(Into::into).collect()),
            chat_ids: value.chat_ids,
            older_than_seconds: value.older_than_seconds,
            limit_bytes: value.limit_bytes,
        }
    }
}

impl From<core_storage::StorageClearResult> for StorageClearResult {
    fn from(value: core_storage::StorageClearResult) -> Self {
        Self {
            freed_bytes: value.freed_bytes,
            cleared_files: value.cleared_files,
            cleared_messages: value.cleared_messages,
            skipped_files: value.skipped_files,
            skipped_bytes: value.skipped_bytes,
        }
    }
}
