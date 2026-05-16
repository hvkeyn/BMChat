package org.thoughtcrime.securesms.bots;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Build a single composite bitmap that visually mimics how Telegram
 * renders a media-group album in one bubble.
 *
 * <p>Telegram packs 2..10 photos into a tiled layout — 1 large image
 * + small grid below for portrait-leaning content, or a clean grid
 * for landscape content. BMChat's chat bubble cannot host an
 * "album" type natively, so we collapse the album client-side into a
 * single image attached to the first {@link com.b44t.messenger.DcMsg}
 * with the bot caption as its body. The receiver sees one tap-to-zoom
 * picture instead of N stacked one-photo posts (the regression visible
 * on screenshots 1–3 from May 9).
 *
 * <p>Layout heuristics, in order of preference:
 * <ul>
 *   <li>1 photo  → use the photo as-is (no compositing).</li>
 *   <li>2 photos → side-by-side (1×2). Switches to top/bottom (2×1)
 *       when both images are obviously portrait.</li>
 *   <li>3 photos → big top + 2 small below (1+2 layout), Telegram-style.</li>
 *   <li>4 photos → 2×2 grid.</li>
 *   <li>5..10 photos → big top + uniform grid below
 *       (2 columns when ≤7 items, 3 columns otherwise).</li>
 * </ul>
 *
 * <p>The canvas width is fixed at {@link #CANVAS_WIDTH} so the
 * downstream JPEG encode produces a bounded-size attachment regardless
 * of the original Telegram resolution. Empty cells (rare odd counts)
 * are filled with a flat dark grey so the composite stays rectangular.
 *
 * <p>Returns {@code null} on any failure — the caller falls back to
 * the legacy "one DcMsg per photo" path so the user never loses a post
 * because of a layout edge case.
 */
public final class AlbumComposer {

  private static final String TAG = "AlbumComposer";

  /** Output canvas width in pixels. 1280 keeps the JPEG well under
   *  the typical 2-3 MiB IMAP attachment cap for a 10-photo album. */
  public static final int CANVAS_WIDTH = 1280;
  /** Inner gap between tiles, in pixels. Mirrors Telegram's spacing. */
  private static final int GAP = 6;
  /** Background fill colour for empty cells. */
  private static final int BG = 0xFF202020;
  /** Maximum height: prevents extremely tall composites for large albums. */
  private static final int MAX_HEIGHT = 2400;

  private AlbumComposer() {}

  /**
   * Compose {@code files} into a single JPEG saved at {@code outFile}.
   * Returns the resulting File on success, {@code null} on failure.
   *
   * <p>Caller must ensure {@code files} contains at least one entry
   * pointing at a decodable image (jpeg/png/webp). Order is preserved.
   */
  public static @Nullable File compose(@NonNull List<File> files, @NonNull File outFile) {
    if (files.isEmpty()) return null;
    if (files.size() == 1) return files.get(0);

    List<Bitmap> bitmaps = new ArrayList<>(files.size());
    try {
      for (File f : files) {
        Bitmap bm = decodeSampled(f, CANVAS_WIDTH);
        if (bm != null) bitmaps.add(bm);
      }
      if (bitmaps.isEmpty()) return null;
      if (bitmaps.size() == 1) {
        // Only one decoded → return its source file (skip composing).
        recycleAll(bitmaps);
        return files.get(0);
      }

      Bitmap composite = layoutAndDraw(bitmaps);
      recycleAll(bitmaps);
      if (composite == null) return null;

      try (FileOutputStream fos = new FileOutputStream(outFile)) {
        composite.compress(Bitmap.CompressFormat.JPEG, 88, fos);
      }
      composite.recycle();
      return outFile;
    } catch (Throwable t) {
      Log.w(TAG, "compose failed", t);
      recycleAll(bitmaps);
      return null;
    }
  }

  // ------------------------------------------------------------------
  //  layout dispatch
  // ------------------------------------------------------------------

  private static @Nullable Bitmap layoutAndDraw(@NonNull List<Bitmap> bm) {
    int n = bm.size();
    switch (n) {
      case 2: return layout2(bm);
      case 3: return layout3(bm);
      case 4: return layoutGrid(bm, 2, 2);
      default: return layoutBigTopPlusGrid(bm);
    }
  }

  /** 2 photos. Side-by-side for landscape, stacked for portrait. */
  private static @Nullable Bitmap layout2(@NonNull List<Bitmap> bm) {
    boolean bothPortrait = bm.get(0).getHeight() > bm.get(0).getWidth()
        && bm.get(1).getHeight() > bm.get(1).getWidth();
    if (bothPortrait) {
      // 2x1 stack, half-height each.
      return layoutGrid(bm, 1, 2);
    }
    return layoutGrid(bm, 2, 1);
  }

  /** 3 photos: big on top spanning full width, 2 small at the bottom. */
  private static @Nullable Bitmap layout3(@NonNull List<Bitmap> bm) {
    int w = CANVAS_WIDTH;
    int topH = (int) (w * 0.55f);   // 55% tall hero shot
    int botH = (int) (w * 0.35f);   // 35% tall row of 2

    Bitmap out = newCanvas(w, topH + GAP + botH);
    if (out == null) return null;
    Canvas c = new Canvas(out);
    drawCell(c, bm.get(0), 0, 0, w, topH);
    int half = (w - GAP) / 2;
    drawCell(c, bm.get(1), 0, topH + GAP, half, botH);
    drawCell(c, bm.get(2), half + GAP, topH + GAP, half, botH);
    return out;
  }

  /**
   * 5..10 photos: big hero on top, uniform grid below.
   * - 5..7 items → 2-column grid (so 4..6 thumbnails)
   * - 8..10 items → 3-column grid
   */
  private static @Nullable Bitmap layoutBigTopPlusGrid(@NonNull List<Bitmap> bm) {
    int n = bm.size();
    int w = CANVAS_WIDTH;
    int cols = n <= 7 ? 2 : 3;
    int gridItems = n - 1;
    int rows = (gridItems + cols - 1) / cols;

    int heroH = (int) (w * 0.55f);
    int cellW = (w - GAP * (cols - 1)) / cols;
    int cellH = (int) (cellW * 0.85f); // slight portrait bias for thumbs
    int gridH = rows * cellH + (rows - 1) * GAP;
    int totalH = Math.min(heroH + GAP + gridH, MAX_HEIGHT);

    Bitmap out = newCanvas(w, totalH);
    if (out == null) return null;
    Canvas c = new Canvas(out);
    drawCell(c, bm.get(0), 0, 0, w, heroH);

    int idx = 1;
    int y = heroH + GAP;
    for (int r = 0; r < rows && idx < n; r++) {
      int x = 0;
      for (int col = 0; col < cols && idx < n; col++) {
        drawCell(c, bm.get(idx), x, y, cellW, cellH);
        x += cellW + GAP;
        idx++;
      }
      y += cellH + GAP;
    }
    return out;
  }

  /** Generic uniform grid (used for 4 photos = 2×2). */
  private static @Nullable Bitmap layoutGrid(@NonNull List<Bitmap> bm, int cols, int rows) {
    int w = CANVAS_WIDTH;
    int cellW = (w - GAP * (cols - 1)) / cols;
    int cellH = cellW;
    int totalH = Math.min(rows * cellH + (rows - 1) * GAP, MAX_HEIGHT);

    Bitmap out = newCanvas(w, totalH);
    if (out == null) return null;
    Canvas c = new Canvas(out);

    int idx = 0;
    int y = 0;
    for (int r = 0; r < rows; r++) {
      int x = 0;
      for (int col = 0; col < cols; col++) {
        if (idx < bm.size()) {
          drawCell(c, bm.get(idx), x, y, cellW, cellH);
        }
        x += cellW + GAP;
        idx++;
      }
      y += cellH + GAP;
    }
    return out;
  }

  // ------------------------------------------------------------------
  //  draw helpers
  // ------------------------------------------------------------------

  private static @Nullable Bitmap newCanvas(int w, int h) {
    if (w <= 0 || h <= 0) return null;
    try {
      Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      Canvas c = new Canvas(b);
      c.drawColor(BG);
      return b;
    } catch (Throwable t) {
      Log.w(TAG, "newCanvas " + w + "x" + h + " failed", t);
      return null;
    }
  }

  /**
   * Center-crop {@code src} into the destination rectangle, mirroring
   * Telegram's "fill cell" photo behaviour.
   */
  private static void drawCell(@NonNull Canvas c, @NonNull Bitmap src,
                               int x, int y, int w, int h) {
    if (w <= 0 || h <= 0) return;
    Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    p.setColor(BG);
    c.drawRect(x, y, x + w, y + h, p);

    float srcRatio = (float) src.getWidth() / (float) src.getHeight();
    float dstRatio = (float) w / (float) h;

    Rect srcR;
    if (srcRatio > dstRatio) {
      // src is wider; crop horizontally.
      int newW = (int) (src.getHeight() * dstRatio);
      int left = (src.getWidth() - newW) / 2;
      srcR = new Rect(left, 0, left + newW, src.getHeight());
    } else {
      int newH = (int) (src.getWidth() / dstRatio);
      int top = (src.getHeight() - newH) / 2;
      srcR = new Rect(0, top, src.getWidth(), top + newH);
    }
    RectF dstR = new RectF(x, y, x + w, y + h);
    c.drawBitmap(src, srcR, dstR, p);
  }

  /**
   * Decode {@code file} downsampled so its longer edge does not exceed
   * {@code targetWidth}. Avoids OOM on 12-MP album photos.
   */
  private static @Nullable Bitmap decodeSampled(@NonNull File file, int targetWidth) {
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
      int sample = 1;
      int longer = Math.max(bounds.outWidth, bounds.outHeight);
      while (longer / (sample * 2) >= targetWidth) sample *= 2;

      BitmapFactory.Options opts = new BitmapFactory.Options();
      opts.inSampleSize = sample;
      opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
      return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    } catch (Throwable t) {
      Log.w(TAG, "decodeSampled failed for " + file, t);
      return null;
    }
  }

  private static void recycleAll(@NonNull List<Bitmap> bitmaps) {
    for (Bitmap b : bitmaps) {
      if (b != null && !b.isRecycled()) {
        try { b.recycle(); } catch (Throwable ignored) {}
      }
    }
    bitmaps.clear();
  }

  // Unused but kept to silence the linter when callers experiment with
  // colour overlays for empty cells.
  @SuppressWarnings("unused")
  private static int debugColor() { return Color.MAGENTA; }
}
