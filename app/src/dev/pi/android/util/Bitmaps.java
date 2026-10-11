package dev.pi.android.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

/**
 * Bounded BitmapFactory helpers shared by chat thumbs / fullscreen previews.
 * Never decode untrusted byte arrays at full resolution on the UI thread.
 */
public final class Bitmaps {
    /** Draft chip / list thumbnails — matches MessageAdapter historical cap. */
    public static final int THUMB_EDGE = 256;
    /** Fullscreen / large preview — matches AttachmentController normalize cap. */
    public static final int VIEW_EDGE = 2048;

    private Bitmaps() {}

    /**
     * Decode {@code data} with {@code inSampleSize} so the longest edge is at most
     * {@code maxEdge}, then scale down if a decoder ignored sampling.
     * Returns null on bad input, absurd dimensions, decode failure, or OOM.
     */
    @Nullable
    public static Bitmap decodeByteArrayBounded(@Nullable byte[] data, int maxEdge) {
        if (data == null || data.length == 0 || maxEdge <= 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            long w = bounds.outWidth;
            long h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;
            // 防御恶意尺寸：单边超 20000 或总像素超 1 亿直接拒。
            if (Math.max(w, h) > 20000 || w * h > 100_000_000L) return null;

            int sample = 1;
            while (Math.max(w, h) / sample > maxEdge) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (decoded == null) return null;
            return scaleDown(decoded, maxEdge);
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    @Nullable
    private static Bitmap scaleDown(Bitmap src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxEdge) return src;
        float scale = (float) maxEdge / max;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(src,
                    Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
            if (scaled != src) src.recycle();
            return scaled;
        } catch (Exception | OutOfMemoryError e) {
            src.recycle();
            return null;
        }
    }
}
