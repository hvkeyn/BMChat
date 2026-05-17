package org.thoughtcrime.securesms.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.thoughtcrime.securesms.BuildConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Peer-to-peer fallback for the update channel.
 *
 * <p>If the BMChat update server (5.187.4.132) ever becomes
 * unreachable but the user is in contact with someone running a
 * newer build, we still want the laggy client to find out an update
 * exists and where to download it from. This class implements the
 * mechanism:
 *
 * <ol>
 *   <li>Every outgoing chat message gets an invisible zero-width
 *       "BMU" marker appended whenever the local client has the
 *       freshest known manifest (whether learned from the official
 *       server or from another peer).</li>
 *   <li>Every incoming chat message is scanned for that marker. If
 *       the embedded manifest is newer than the receiver's installed
 *       BMChat build, it's persisted to the same prefs key the
 *       built-in updater consults — so the next manifest probe
 *       returns this peer-relayed snapshot when the server fails.</li>
 *   <li>The marker is then stripped from the visible message so the
 *       chat bubble shows clean prose.</li>
 * </ol>
 *
 * <p>The payload is base64(JSON) so it survives email
 * encoders/decoders verbatim. The marker bytes themselves are
 * zero-width characters that render as nothing in any modern font.
 */
public final class UpdateBroadcast {

    private static final String TAG = "UpdateBroadcast";
    private static boolean outboundRelayEnabled = false;

    /** Prefix and suffix sit on the same zero-width pair we already
     *  use for BotMediaMarker, but with a different opcode ("BMU")
     *  so the two markers never collide in caption parsing. */
    private static final String PFX = "\u200B\u200BBMU(";
    private static final String SFX = ")\u200B\u200B";

    // BMChat 2.49.56: the strip-side regex used to require the literal
    // zero-width pair on both sides of "BMU(...)". Some SMTP servers and
    // mailing-list MTAs strip zero-width characters from message bodies,
    // and at least one received build appears to have shipped a "BMU(...)"
    // marker without zero-width wrappers altogether. As a result the
    // payload would slip through the strip pass and end up rendered
    // verbatim in the chat bubble (looking like a giant base64 garbage
    // dump after the user's prose). We now match the marker leniently:
    //
    //   - any number of optional zero-width / whitespace characters on
    //     either side (including \u200B/\u200C/\u200D/\uFEFF and ASCII
    //     spaces / tabs / line breaks),
    //   - a tolerated "best effort" capture for cases where the closing
    //     parenthesis was truncated by an upstream mailer that hard-wraps
    //     long lines: the second pattern matches "BMU(" up to the end of
    //     the message so users never see a half-marker.
    //
    // Both patterns are still ingest-safe because {@link #ingestEncoded}
    // validates the decoded JSON aggressively before persisting.
    private static final Pattern MARKER = Pattern.compile(
            "[\\u200B\\u200C\\u200D\\uFEFF\\s]*"
                    + "BMU\\(([A-Za-z0-9+/=_\\-]+)\\)"
                    + "[\\u200B\\u200C\\u200D\\uFEFF\\s]*");

    /** Catches malformed markers where the closing ")" was lost on the
     *  way (some MTAs hard-wrap or trim runs of base64). We strip them
     *  unconditionally; ingest is best-effort on any base64 fragment
     *  that decodes to valid JSON. Without this, a single trailing
     *  "BMU(...."-style fragment would otherwise leak into the bubble.*/
    private static final Pattern MARKER_OPEN_ENDED = Pattern.compile(
            "[\\u200B\\u200C\\u200D\\uFEFF\\s]*BMU\\(([A-Za-z0-9+/=_\\-]+)[\\s\\S]*$");

    /** Shared prefs file/key with BMChatUpdater. Matching the names
     *  exactly lets the updater treat a peer-relayed snapshot the
     *  same way as a server probe. */
    static final String PREFS_NAME = "bmchat-updater";
    static final String KEY_PEER_MANIFEST_JSON  = "peer-manifest-json";
    static final String KEY_PEER_MANIFEST_AT_MS = "peer-manifest-at-ms";

    private UpdateBroadcast() {}

    // ---------- outgoing ---------------------------------------------------

    /**
     * If the local client knows about a build that's newer than the
     * one it's currently running, append an invisible "you should
     * upgrade — here's where" marker to the supplied outgoing text.
     *
     * <p>Otherwise returns the input verbatim. Never appends when
     * there is no known manifest, so a brand-new install (which has
     * not yet talked to the server) doesn't relay anything.
     */
    @AnyThread
    public static @NonNull String maybeAppend(@NonNull Context ctx, @NonNull String body) {
        // BMChat 2.49.68: disabled outbound peer-to-peer update markers.
        //
        // The idea was useful as a fallback updater channel, but real mail
        // servers and clients mutate zero-width framing too often. Once the
        // wrappers are stripped, users see the raw "BMU(base64...)" payload in
        // bubbles, notifications, or the full-message view. The official
        // update.json + mirrors are reliable enough, so do not decorate any
        // new outgoing messages. Keep stripAndIngest()/strip() below so older
        // messages already carrying markers stay clean on render.
        if (!outboundRelayEnabled) return body;
        try {
            String json = bestKnownManifestJson(ctx);
            if (json == null) return body;
            JSONObject m = new JSONObject(json);
            long advertised = m.optLong("versionCode", 0L);
            if (advertised <= BuildConfig.VERSION_CODE) {
                // Nothing useful to broadcast: either we don't know a
                // newer build than ours, or — typically — we *are*
                // already running the build the manifest describes.
                // No reason to litter every outgoing message.
                return body;
            }
            String encoded = Base64.encodeToString(
                    json.getBytes("UTF-8"),
                    Base64.NO_WRAP | Base64.URL_SAFE);
            return body + PFX + encoded + SFX;
        } catch (Throwable t) {
            Log.w(TAG, "maybeAppend failed; sending body unchanged", t);
            return body;
        }
    }

    // ---------- incoming ---------------------------------------------------

    /**
     * Parses any embedded {@link UpdateBroadcast} marker out of
     * {@code text}, persists the manifest if it advertises a newer
     * build than the one we have stored, and returns the
     * marker-free text for the UI.
     *
     * <p>Safe to call on every incoming bubble — does no IO when no
     * marker is present (quick prefix-search on the literal "BMU"
     * trigram before running a regex).
     */
    @AnyThread
    public static @NonNull String stripAndIngest(@NonNull Context ctx,
                                                 @NonNull String text) {
        if (text.isEmpty() || !text.contains("BMU(")) return text;

        // 1) Well-formed markers with optional zero-width / whitespace
        //    framing: ingest the embedded manifest and strip the marker
        //    from the visible text in one pass.
        Matcher m = MARKER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String encoded = m.group(1);
            ingestEncoded(ctx, encoded);
            m.appendReplacement(sb, "");
        }
        m.appendTail(sb);
        String cleaned = sb.toString();

        // 2) Defensive: if a malformed marker slipped through (closing
        //    ")" lost in transit, payload truncated by a mailer wrap),
        //    drop everything from "BMU(" to the end of the message.
        //    Try ingesting the truncated payload first — base64 + JSON
        //    parsing in {@link #ingestEncoded} silently rejects garbage.
        if (cleaned.contains("BMU(")) {
            Matcher openEnded = MARKER_OPEN_ENDED.matcher(cleaned);
            if (openEnded.find()) {
                ingestEncoded(ctx, openEnded.group(1));
                cleaned = openEnded.replaceAll("").trim();
            }
        }
        return cleaned;
    }

    /**
     * Strip-only variant used by render paths that have already done
     * the ingest pass — keeps the visible text clean without the
     * cost of re-touching prefs.
     */
    @AnyThread
    public static @NonNull String strip(@NonNull String text) {
        if (text.isEmpty() || !text.contains("BMU(")) return text;
        String cleaned = MARKER.matcher(text).replaceAll("");
        if (cleaned.contains("BMU(")) {
            cleaned = MARKER_OPEN_ENDED.matcher(cleaned).replaceAll("").trim();
        }
        return cleaned;
    }

    private static void ingestEncoded(@NonNull Context ctx, @Nullable String encoded) {
        if (encoded == null || encoded.isEmpty()) return;
        try {
            byte[] raw = Base64.decode(encoded, Base64.URL_SAFE);
            String json = new String(raw, "UTF-8");
            JSONObject candidate = new JSONObject(json);
            long candVc = candidate.optLong("versionCode", 0L);
            String url = candidate.optString("url", "");
            String sha = candidate.optString("sha256", "");
            if (candVc <= 0 || url.isEmpty() || sha.isEmpty()) return;
            // Defensive: only trust manifests whose APK URL points
            // at one of our well-known hosts. The list expanded in
            // BMChat 2.49.43 to include the WhiteBlade mirror; the
            // updater applies the same check on the URL it actually
            // downloads from, so this guard is the choke point for
            // peer-supplied redirects.
            boolean trusted = false;
            for (String h : org.thoughtcrime.securesms.update.BMChatUpdater.DEFAULT_HOSTS) {
                if (url.startsWith(h + "/")) { trusted = true; break; }
            }
            if (!trusted) return;
            if (candVc <= BuildConfig.VERSION_CODE) return; // older than us

            SharedPreferences prefs = ctx.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
            String existing = prefs.getString(KEY_PEER_MANIFEST_JSON, null);
            long existingVc = -1L;
            if (existing != null) {
                try { existingVc = new JSONObject(existing).optLong("versionCode", -1L); }
                catch (Throwable ignore) { /* corrupt store — overwrite */ }
            }
            if (candVc > existingVc) {
                prefs.edit()
                        .putString(KEY_PEER_MANIFEST_JSON, json)
                        .putLong(KEY_PEER_MANIFEST_AT_MS, System.currentTimeMillis())
                        .apply();
                Log.i(TAG, "ingested peer-relayed manifest for vc=" + candVc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ingestEncoded failed", t);
        }
    }

    /**
     * The freshest manifest the client knows about, regardless of
     * whether it was obtained from the official server or relayed
     * by a peer. Returned verbatim as JSON so {@link BMChatUpdater}
     * can hand it straight to {@code Manifest.parse}.
     */
    static @Nullable String bestKnownManifestJson(@NonNull Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                  .getString(KEY_PEER_MANIFEST_JSON, null);
    }
}
