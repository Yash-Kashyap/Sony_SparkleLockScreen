package com.yash.sparklelock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Builds the bitmap that the lock face is drawn from: wallpaper + clock + date + hint.
 * The default wallpaper is an original procedural "sky over a wheat field" scene in the
 * spirit of the 2013 Xperia lock screens (no copyrighted artwork is bundled).
 */
public final class SceneRenderer {

    private static Bitmap cachedWallpaper;
    private static String cachedWallpaperKey;

    private SceneRenderer() {}

    public static synchronized void invalidateWallpaper() {
        cachedWallpaper = null;
        cachedWallpaperKey = null;
    }

    /** Wallpaper scaled (center-crop) to exactly w x h, cached for the lock screen size. */
    public static Bitmap wallpaper(Context ctx, int w, int h) {
        return wallpaper(ctx, w, h, true);
    }

    public static synchronized Bitmap wallpaper(Context ctx, int w, int h, boolean cache) {
        Prefs prefs = new Prefs(ctx);
        boolean custom = prefs.customWallpaper();
        String key = (custom ? "custom:" + prefs.wallpaperFile().lastModified() : "default") + ":" + w + "x" + h;
        if (cachedWallpaper != null && key.equals(cachedWallpaperKey)) return cachedWallpaper;

        Bitmap result = null;
        if (custom) {
            Bitmap src = BitmapFactory.decodeFile(prefs.wallpaperFile().getAbsolutePath());
            if (src != null) {
                result = centerCrop(src, w, h);
                if (result != src) src.recycle();
            }
        }
        if (result == null) result = defaultWallpaper(w, h);
        if (cache) {
            cachedWallpaper = result;
            cachedWallpaperKey = key;
        }
        return result;
    }

    /**
     * Composes the lock-screen background: wallpaper, scrims and the unlock hint. The clock and
     * widgets live in the widget pager on top; they are stamped into this bitmap only while the
     * finger is down (see LockActivity).
     */
    public static Bitmap compose(Context ctx, int w, int h, int bottomInset) {
        Prefs prefs = new Prefs(ctx);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(wallpaper(ctx, w, h), 0, 0, null);

        // Gentle scrims so white text and the status bar stay readable on any image.
        Paint scrim = new Paint();
        scrim.setShader(new LinearGradient(0, 0, 0, h * 0.42f,
                Color.argb(110, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.42f, scrim);
        scrim.setShader(new LinearGradient(0, h * 0.78f, 0, h,
                Color.argb(0, 0, 0, 0), Color.argb(90, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.78f, w, h, scrim);

        drawHint(ctx, c, w, h, bottomInset);
        return out;
    }

    /** Xperia-style clock + date, centred horizontally, time baseline at {@code baseline}. */
    public static void drawClock(Context ctx, Canvas c, int w, float baseline) {
        Calendar now = Calendar.getInstance();
        boolean is24 = DateFormat.is24HourFormat(ctx);
        String time = DateFormat.format(is24 ? "HH:mm" : "hh:mm", now).toString();
        String ampm = is24 ? null : DateFormat.format("a", now).toString().toLowerCase(Locale.getDefault());
        Locale loc = Locale.getDefault();
        String date = DateFormat.format(DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM"), now).toString();

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        tp.setTextSize(w * 0.25f);
        tp.setShadowLayer(w * 0.01f, 0, w * 0.003f, Color.argb(90, 0, 0, 0));

        float timeW = tp.measureText(time);
        Paint ap = new Paint(tp);
        ap.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        ap.setTextSize(w * 0.05f);
        float ampmW = ampm == null ? 0 : ap.measureText(ampm) + w * 0.01f;

        float x = (w - timeW - ampmW) / 2f;
        c.drawText(time, x, baseline, tp);
        if (ampm != null) {
            // Small superscript am/pm, like the Xperia clock.
            c.drawText(ampm, x + timeW + w * 0.01f, baseline - tp.getTextSize() * 0.52f, ap);
        }

        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor(Color.argb(235, 255, 255, 255));
        dp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        dp.setTextSize(w * 0.046f);
        dp.setShadowLayer(w * 0.008f, 0, w * 0.002f, Color.argb(90, 0, 0, 0));
        dp.setTextAlign(Paint.Align.CENTER);
        c.drawText(date, w / 2f, baseline + w * 0.10f, dp);
    }

    /** Xperia Z2-style hint: a quiet "Swipe to unlock" a little below the middle. */
    private static void drawHint(Context ctx, Canvas c, int w, int h, int bottomInset) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(215, 255, 255, 255));
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        p.setTextSize(w * 0.038f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setShadowLayer(w * 0.006f, 0, 0, Color.argb(120, 0, 0, 0));
        float y = Math.min(h * 0.64f, h - bottomInset - w * 0.2f);
        c.drawText(ctx.getString(R.string.swipe_to_unlock), w / 2f, y, p);
    }

    // ------------------------------------------------------------------ helpers

    public static Bitmap centerCrop(Bitmap src, int w, int h) {
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float sw = w / scale, sh = h / scale;
        float left = (src.getWidth() - sw) / 2f, top = (src.getHeight() - sh) / 2f;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(src, new Rect(Math.round(left), Math.round(top),
                Math.round(left + sw), Math.round(top + sh)), new RectF(0, 0, w, h), p);
        return out;
    }

    /**
     * Procedural "flowing silk" wallpaper in the spirit of the 2014 Xperia look: a deep blue
     * field with soft, translucent, layered ribbons. Original artwork drawn in code. It is blue so
     * that the hue cycling sweeps it through the whole rainbow.
     */
    public static Bitmap defaultWallpaper(int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, w * 0.3f, h,
                new int[]{0xFF0A1E8C, 0xFF1C3FD6, 0xFF2B58F0, 0xFF1530A8},
                new float[]{0f, 0.45f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(w * 0.75f, h * 0.35f, w * 0.9f,
                new int[]{0x553F7BFF, 0x00000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, glow);

        Random rnd = new Random(20140502L);
        Paint rib = new Paint(Paint.ANTI_ALIAS_FLAG);
        rib.setStyle(Paint.Style.FILL);
        // Each ribbon: a band between two gently curved edges sweeping across the screen.
        for (int k = 0; k < 7; k++) {
            float y0 = h * (0.10f + 0.12f * k + 0.05f * rnd.nextFloat());
            float thick = h * (0.03f + 0.07f * rnd.nextFloat());
            float sag = h * (0.10f + 0.18f * rnd.nextFloat()) * (rnd.nextBoolean() ? 1 : -1);
            float tilt = h * (0.15f + 0.2f * rnd.nextFloat());
            Path path = new Path();
            path.moveTo(-w * 0.1f, y0 + tilt);
            path.cubicTo(w * 0.3f, y0 + tilt + sag, w * 0.6f, y0 - sag * 0.6f, w * 1.1f, y0 - tilt * 0.4f);
            path.lineTo(w * 1.1f, y0 - tilt * 0.4f + thick * 0.4f);
            path.cubicTo(w * 0.6f, y0 - sag * 0.6f + thick, w * 0.3f, y0 + tilt + sag + thick * 1.2f,
                    -w * 0.1f, y0 + tilt + thick * 1.4f);
            path.close();
            int light = rnd.nextBoolean() ? 0x66A8C4FF : 0x447FA8FF;
            rib.setShader(new LinearGradient(0, y0 - thick, w, y0 + thick,
                    new int[]{0x00FFFFFF, light, 0x10FFFFFF, light & 0x55FFFFFF},
                    new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            rib.setMaskFilter(new BlurMaskFilter(w * (0.004f + 0.01f * rnd.nextFloat()),
                    BlurMaskFilter.Blur.NORMAL));
            c.drawPath(path, rib);
            // a crisp highlight along the leading edge, like folded silk
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1.5f, w * 0.002f));
            edge.setColor(0x55DDE8FF);
            edge.setMaskFilter(new BlurMaskFilter(w * 0.002f, BlurMaskFilter.Blur.NORMAL));
            Path e2 = new Path();
            e2.moveTo(-w * 0.1f, y0 + tilt);
            e2.cubicTo(w * 0.3f, y0 + tilt + sag, w * 0.6f, y0 - sag * 0.6f, w * 1.1f, y0 - tilt * 0.4f);
            c.drawPath(e2, edge);
        }
        // soft vignette
        Paint vig = new Paint();
        vig.setShader(new RadialGradient(w / 2f, h / 2f, h * 0.75f,
                new int[]{0x00000000, 0x55000020}, new float[]{0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, vig);
        return out;
    }
}
