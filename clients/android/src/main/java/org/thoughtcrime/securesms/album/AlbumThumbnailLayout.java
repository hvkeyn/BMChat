package org.thoughtcrime.securesms.album;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.ThumbnailView;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.mms.ImageSlide;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.mms.VideoSlide;

import java.util.List;
import java.util.Set;

/**
 * Telegram-style media-album grid that renders a small group of photos / videos
 * into a single bubble.
 *
 * <p>BMChat used to draw every member of a media album as its own bubble,
 * stacked on top of each other with negative margins. That looked OK for two
 * photos but never matched Telegram's familiar tile layout (2×2, 1+2, 2+3, …).
 *
 * <p>This view groups the album bubbles into one. The caller hands it the
 * list of {@link DcMsg DcMsg}-IDs that belong to the album and a {@link
 * GlideRequests} loader, and {@link #bind} computes:
 *
 * <ul>
 *   <li>tile positions according to a small set of fixed templates that match
 *       what Telegram's "GroupedMessages" code produces (see below),</li>
 *   <li>a 1 px gap between tiles so the cluster reads as a single mosaic.</li>
 * </ul>
 *
 * <p>Tap delegation: every tile gets the same {@link SlideClickListener} so
 * the existing {@link org.thoughtcrime.securesms.MediaPreviewActivity}
 * pipeline (with its swipe-through pager) takes over on tap. The clicked
 * DcMsg's id is the entry point — the previewer already discovers and
 * paginates the rest of the album from the chat itself.
 *
 * <p>Tile templates (all use a 1 dp inter-tile gap):
 *
 * <pre>
 *  count = 2:   ▢ ▢            ─── 1 row, 2 cols (full width)
 *  count = 3:   ▢▢▢            ─── top: 1 wide; bottom: 2 wide tiles
 *               ▢ ▢                (i.e. one full-width + two side-by-side)
 *  count = 4:   ▢ ▢            ─── 2×2
 *               ▢ ▢
 *  count = 5:   ▢ ▢            ─── 2 (top) + 3 (bottom)
 *               ▢▢▢
 *  count = 6:   ▢ ▢ ▢          ─── 2×3
 *               ▢ ▢ ▢
 * </pre>
 *
 * <p>Anything ≥ 7 falls back to a uniform 3-column grid with as many rows as
 * needed (Telegram does the same for "long" albums up to its hard cap of 10).
 */
public class AlbumThumbnailLayout extends FrameLayout {

  /** Inter-tile gap (Telegram uses 2 px on hidpi; 1 dp reads similarly). */
  private static final int TILE_GAP_DP = 2;

  /** Outer corner radius — matches @drawable/conversation_item_background. */
  private static final int CORNER_RADIUS_DP = 12;

  private final Path clipPath = new Path();
  private final RectF clipRect = new RectF();
  private final float[] cornerRadii = new float[8];
  private final int gapPx;
  private final int cornerRadiusPx;

  public AlbumThumbnailLayout(Context context) {
    this(context, null);
  }

  public AlbumThumbnailLayout(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public AlbumThumbnailLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    float density = getResources().getDisplayMetrics().density;
    this.gapPx = Math.round(TILE_GAP_DP * density);
    this.cornerRadiusPx = Math.round(CORNER_RADIUS_DP * density);
    for (int i = 0; i < cornerRadii.length; i++) cornerRadii[i] = cornerRadiusPx;
    setWillNotDraw(false);
  }

  /**
   * Per-tile event delegate. The hosting {@link
   * org.thoughtcrime.securesms.ConversationItem} implements this to:
   *
   * <ul>
   *   <li>open the swipe-through {@link
   *       org.thoughtcrime.securesms.MediaPreviewActivity} starting at the
   *       tapped photo when there is no active batch-select session;</li>
   *   <li>toggle just the tapped photo into the batch-select set when one
   *       is already underway (so the user can pick a subset of an album
   *       for forward / delete / star);</li>
   *   <li>start a fresh batch-select session on long-press of any tile.</li>
   * </ul>
   *
   * <p>Each method receives the {@link DcMsg} that backs the tapped tile —
   * NOT the album's first message — so the selection set keeps a one-to-one
   * mapping between visual tiles and chat messages, exactly like Telegram.
   */
  public interface AlbumTileEvents {
    /** Short tap on a tile. */
    void onTileClick(@NonNull DcMsg msg, @NonNull View tile);

    /** Long press on a tile. Return value forwards to {@code View.OnLongClickListener}. */
    boolean onTileLongClick(@NonNull DcMsg msg, @NonNull View tile);
  }

  /**
   * Bind the album to render. Removes any previous tiles and lays out a fresh
   * mosaic. Safe to call from {@code onBindViewHolder} on every recycle.
   *
   * @param dcContext       delta-core context, used to resolve {@code msgIds} into
   *                        {@link DcMsg} for slide construction
   * @param msgIds          ordered list of DcMsg-IDs belonging to the album, in
   *                        chat (= visual) order
   * @param glide           Glide requests instance for thumbnails
   * @param events          per-tile click/long-click delegate
   * @param batchSelectedIds set of DcMsg-IDs that are currently part of a
   *                        batch-select operation; matching tiles get a
   *                        coloured selection frame overlaid
   */
  public void bind(
      @NonNull DcContext dcContext,
      @NonNull List<Integer> msgIds,
      @NonNull GlideRequests glide,
      @NonNull AlbumTileEvents events,
      @Nullable Set<Integer> batchSelectedIds) {
    removeAllViews();
    int count = msgIds.size();
    if (count == 0) return;

    for (int i = 0; i < count; i++) {
      int msgId = msgIds.get(i);
      DcMsg msg = dcContext.getMsg(msgId);
      if (msg == null) continue;

      ThumbnailView tv = new ThumbnailView(getContext());
      tv.setClickable(true);
      tv.setFocusable(true);
      tv.setLongClickable(true);

      Slide slide = makeSlide(msg);
      if (slide != null) {
        tv.setImageResource(glide, slide, msg.getWidth(0), msg.getHeight(0));
      }

      final DcMsg finalMsg = msg;
      tv.setOnClickListener(v -> events.onTileClick(finalMsg, v));
      tv.setOnLongClickListener(v -> events.onTileLongClick(finalMsg, v));

      // Selection frame overlay (Telegram-style 3 dp coloured border) — drawn
      // as the tile's foreground so it sits above the thumbnail without
      // changing the tile's size.
      if (batchSelectedIds != null && batchSelectedIds.contains(msgId)) {
        tv.setForeground(getResources().getDrawable(R.drawable.bmchat_album_tile_selected));
      } else {
        tv.setForeground(null);
      }

      addView(tv);
    }
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int tileCount = getChildCount();
    if (tileCount == 0) {
      setMeasuredDimension(width, 0);
      return;
    }

    int height = computeHeight(tileCount, width);
    setMeasuredDimension(width, height);

    measureTiles(tileCount, width, height);
  }

  /** Returns total mosaic height for {@code count} tiles in a {@code width}-wide bubble. */
  private int computeHeight(int count, int width) {
    switch (count) {
      case 1:
        return Math.round(width * 0.75f);
      case 2:
        // 1 row × 2 cols, square tiles
        return (width - gapPx) / 2;
      case 3:
        // top row 1 (width × 0.5), bottom row 2 of half-width (each 0.5 of half-width)
        int topH = Math.round(width * 0.50f);
        int botH = (width - gapPx) / 2;
        return topH + gapPx + botH / 2;
      case 4:
        // 2×2
        return width;
      case 5:
        // top row 2 (each (width-gap)/2 × ((width-gap)/2 * 0.6))
        int row1H = Math.round((width - gapPx) / 2f * 0.85f);
        int row2H = Math.round((width - 2 * gapPx) / 3f * 0.85f);
        return row1H + gapPx + row2H;
      case 6:
        // 2×3
        return Math.round(((width - 2 * gapPx) / 3f) * 2 + gapPx);
      default:
        // ≥7: 3-column uniform grid
        int rows = (count + 2) / 3;
        int cellH = (width - 2 * gapPx) / 3;
        return cellH * rows + gapPx * (rows - 1);
    }
  }

  /** Measure every tile child to its target rect according to the template. */
  private void measureTiles(int count, int width, int height) {
    RectF[] rects = computeRects(count, width, height);
    for (int i = 0; i < count; i++) {
      View tile = getChildAt(i);
      RectF r = rects[i];
      int tileW = (int) r.width();
      int tileH = (int) r.height();
      tile.measure(
          MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY));
    }
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    int count = getChildCount();
    if (count == 0) return;
    int width = right - left;
    int height = bottom - top;
    RectF[] rects = computeRects(count, width, height);
    for (int i = 0; i < count; i++) {
      View tile = getChildAt(i);
      RectF r = rects[i];
      tile.layout((int) r.left, (int) r.top, (int) r.right, (int) r.bottom);
    }
  }

  /**
   * Computes per-tile rectangles for the given {@code count} and {@code (width × height)}
   * bubble, applying the same templates documented in the class header.
   */
  private RectF[] computeRects(int count, int width, int height) {
    RectF[] r = new RectF[count];
    switch (count) {
      case 1:
        r[0] = new RectF(0, 0, width, height);
        break;
      case 2: {
        int w = (width - gapPx) / 2;
        r[0] = new RectF(0, 0, w, height);
        r[1] = new RectF(w + gapPx, 0, width, height);
        break;
      }
      case 3: {
        int topH = Math.round(width * 0.50f);
        int botStart = topH + gapPx;
        int w = (width - gapPx) / 2;
        r[0] = new RectF(0, 0, width, topH);
        r[1] = new RectF(0, botStart, w, height);
        r[2] = new RectF(w + gapPx, botStart, width, height);
        break;
      }
      case 4: {
        int half = (width - gapPx) / 2;
        r[0] = new RectF(0, 0, half, half);
        r[1] = new RectF(half + gapPx, 0, width, half);
        r[2] = new RectF(0, half + gapPx, half, height);
        r[3] = new RectF(half + gapPx, half + gapPx, width, height);
        break;
      }
      case 5: {
        // top row: 2 tiles of (width-gap)/2
        int half = (width - gapPx) / 2;
        int row1H = Math.round(half * 0.85f);
        int row2Start = row1H + gapPx;
        r[0] = new RectF(0, 0, half, row1H);
        r[1] = new RectF(half + gapPx, 0, width, row1H);
        // bottom row: 3 tiles of (width-2gap)/3
        int third = (width - 2 * gapPx) / 3;
        r[2] = new RectF(0, row2Start, third, height);
        r[3] = new RectF(third + gapPx, row2Start, third * 2 + gapPx, height);
        r[4] = new RectF(third * 2 + 2 * gapPx, row2Start, width, height);
        break;
      }
      case 6: {
        int third = (width - 2 * gapPx) / 3;
        int half = third + gapPx; // row height
        // row 0
        r[0] = new RectF(0, 0, third, third);
        r[1] = new RectF(third + gapPx, 0, third * 2 + gapPx, third);
        r[2] = new RectF(third * 2 + 2 * gapPx, 0, width, third);
        // row 1
        r[3] = new RectF(0, half, third, half + third);
        r[4] = new RectF(third + gapPx, half, third * 2 + gapPx, half + third);
        r[5] = new RectF(third * 2 + 2 * gapPx, half, width, half + third);
        break;
      }
      default: {
        // 3-col uniform grid
        int rows = (count + 2) / 3;
        int cellW = (width - 2 * gapPx) / 3;
        int cellH = (height - gapPx * (rows - 1)) / rows;
        for (int i = 0; i < count; i++) {
          int row = i / 3;
          int col = i % 3;
          int x = col * (cellW + gapPx);
          int y = row * (cellH + gapPx);
          r[i] = new RectF(x, y, x + cellW, y + cellH);
        }
        break;
      }
    }
    return r;
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    // Clip the whole mosaic with rounded corners so the cluster looks like
    // a single bubble.
    clipRect.set(0, 0, getWidth(), getHeight());
    clipPath.reset();
    clipPath.addRoundRect(clipRect, cornerRadii, Path.Direction.CW);
    int saveCount = canvas.save();
    canvas.clipPath(clipPath);
    super.dispatchDraw(canvas);
    canvas.restoreToCount(saveCount);
  }

  /** Translates a DcMsg into the appropriate {@link Slide} for ThumbnailView. */
  private @Nullable Slide makeSlide(@NonNull DcMsg msg) {
    int type = msg.getType();
    if (type == DcMsg.DC_MSG_IMAGE || type == DcMsg.DC_MSG_GIF) {
      return new ImageSlide(getContext(), msg);
    } else if (type == DcMsg.DC_MSG_VIDEO) {
      return new VideoSlide(getContext(), msg);
    }
    return null;
  }
}
