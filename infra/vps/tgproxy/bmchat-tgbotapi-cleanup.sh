#!/usr/bin/env bash
# Periodic cleanup of telegram-bot-api's on-disk cache.
#
# The proxy calls deleteFile after streaming, so under normal flow
# this script has nothing to do. It's the safety net for two cases:
#
#   * A player aborted mid-download and the deleteFile call never
#     fired (rare but real on flaky networks).
#   * Files came in via getUpdates but were never streamed by anyone
#     (e.g. user deleted the BMChat message before tapping play).
#
# Rules:
#   - keep td.binlog / tqueue.binlog / webhooks_db.binlog forever
#     (those are MTProto session state — deleting them logs the bot
#     out and forces a re-authorization)
#   - delete cached media files older than MAX_AGE_MIN minutes
#   - if the mount is over SOFT_LIMIT_MB, LRU-evict oldest media
#     files until we're back under the limit
#
# Idempotent, exits cleanly even when the mount is empty.
set -eo pipefail

DIR="/var/lib/telegram-bot-api"
MAX_AGE_MIN="${BMCHAT_CLEANUP_MAX_AGE_MIN:-20}"
SOFT_LIMIT_MB="${BMCHAT_CLEANUP_SOFT_LIMIT_MB:-5120}"   # 5 GB

# Subdirectories under each bot dir that hold downloaded media. We
# leave everything else (binlog, session state) alone.
MEDIA_SUBDIRS=(videos photos documents audios voices video_notes \
               animations stickers thumbnails)

if [[ ! -d "${DIR}" ]]; then
    exit 0
fi

# Step 1 — mtime-based purge.
for sub in "${MEDIA_SUBDIRS[@]}"; do
    find "${DIR}"/*/"${sub}" -maxdepth 1 -type f -mmin +"${MAX_AGE_MIN}" -delete 2>/dev/null || true
done

# Step 2 — size-based LRU eviction. Cheap to compute the total via
# `du -sm`, falls into the loop only when we're actually over budget.
total_mb=$(du -sm "${DIR}" 2>/dev/null | awk '{print $1}')
if (( total_mb > SOFT_LIMIT_MB )); then
    # Build a list of media files sorted by mtime (oldest first),
    # delete them one by one until we're back under the limit. We
    # touch only media subdirs — never the binlog files.
    excess_mb=$((total_mb - SOFT_LIMIT_MB))
    freed_mb=0
    while IFS= read -r line; do
        size_kb=$(stat -c '%s' -- "${line}" 2>/dev/null || echo 0)
        size_mb=$((size_kb / 1024 / 1024))
        rm -f -- "${line}"
        freed_mb=$((freed_mb + size_mb))
        (( freed_mb >= excess_mb )) && break
    done < <(
        for sub in "${MEDIA_SUBDIRS[@]}"; do
            find "${DIR}"/*/"${sub}" -maxdepth 1 -type f -printf '%T@\t%p\n' 2>/dev/null
        done | sort -n | awk -F'\t' '{print $2}'
    )
fi

exit 0
