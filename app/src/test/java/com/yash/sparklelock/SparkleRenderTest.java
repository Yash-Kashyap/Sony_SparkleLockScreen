package com.yash.sparklelock;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

/** Renders the real SparkleView through a hold-and-swirl gesture and an unlock. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h780dp-xxhdpi")
public class SparkleRenderTest {
    private static final int W = 1080, H = 2340;
    private final File out = new File(System.getProperty("frames.out", "build/frames"));
    private int frame;
    private Bitmap home;
    private float fx = -1, fy;

    private static class Rec implements SparkleView.Listener {
        SparkleView v; boolean unlocked, finished, camera;
        @Override public void onUnlockGesture() {
            unlocked = true;
            v.startReveal(false);
            new android.os.Handler(Looper.getMainLooper()).postDelayed(v::setBackdropClear, 120);
        }
        @Override public void onRevealFinished() { finished = true; }
        @Override public void onCameraGesture() { camera = true; }
    }

    private SparkleView make(Rec r) {
        Context ctx = RuntimeEnvironment.getApplication();
        SparkleView v = new SparkleView(ctx);
        r.v = v;
        v.setListener(r);
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, W, H);
        v.configure(0.25f, 1f, 40f, true, true, null);
        Bitmap scene = SceneRenderer.compose(ctx, W, H, 0);
        Canvas c = new Canvas(scene);
        SceneRenderer.drawClock(ctx, c, W, H * 0.25f);
        v.setScene(scene);
        home = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, W, H, 0xFF202020, 0xFF505050, Shader.TileMode.CLAMP));
        new Canvas(home).drawRect(0, 0, W, H, p);
        return v;
    }

    private void render(SparkleView v, String dir) throws Exception {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawBitmap(home, 0, 0, null);
        int layer = c.saveLayerAlpha(0, 0, W, H, Math.round(v.getAlpha() * 255));
        v.draw(c);
        c.restoreToCount(layer);
        if (fx >= 0) { // translucent finger marker for the demo only
            Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
            f.setColor(Color.argb(60, 255, 220, 200));
            c.drawCircle(fx, fy, 60, f);
        }
        File d = new File(out, dir);
        d.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(d, String.format("f%04d.png", frame++)))) {
            Bitmap.createScaledBitmap(b, W / 2, H / 2, true).compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
    }

    private void touch(SparkleView v, int action, float x, float y, long down) {
        boolean up = action == MotionEvent.ACTION_UP;
        fx = up ? -1 : x; fy = y;
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    @Test
    public void holdSwirlAndUnlock() throws Exception {
        Rec r = new Rec();
        SparkleView v = make(r);
        frame = 0;
        String dir = "sparkle";
        render(v, dir);
        long down = SystemClock.uptimeMillis();
        float x = 600, y = 1500;
        touch(v, MotionEvent.ACTION_DOWN, x, y, down);
        for (int i = 0; i < 40; i++) render(v, dir); // hold: dust gathers, colour drifts
        for (int i = 0; i < 120; i++) {           // swirl around while holding
            double a = i / 18.0;
            x = 540 + (float) Math.cos(a) * 260;
            y = 1350 + (float) Math.sin(a) * 300 - i * 2;
            touch(v, MotionEvent.ACTION_MOVE, x, y, down);
            render(v, dir);
        }
        assertTrue("no unlock while the finger is held", !r.unlocked);
        assertTrue("colour drifted while holding", v.getHue() > 20f);
        touch(v, MotionEvent.ACTION_UP, x, y, down);
        assertTrue("release after a long swipe unlocks", r.unlocked);
        for (int i = 0; i < 40 && !r.finished; i++) render(v, dir);
        assertTrue(r.finished);
    }

    @Test
    public void tapDoesNotUnlockAndCameraSwipeOpensCamera() throws Exception {
        Rec r = new Rec();
        SparkleView v = make(r);
        long down = SystemClock.uptimeMillis();
        touch(v, MotionEvent.ACTION_DOWN, 500, 1200, down);
        touch(v, MotionEvent.ACTION_UP, 505, 1204, down);
        assertTrue("a tap must not unlock", !r.unlocked);
        float cx = W - 46 * 3, cy = H - 46 * 3;
        down = SystemClock.uptimeMillis();
        touch(v, MotionEvent.ACTION_DOWN, cx, cy, down);
        for (int k = 1; k <= 10; k++) touch(v, MotionEvent.ACTION_MOVE, cx, cy - k * 60, down);
        touch(v, MotionEvent.ACTION_UP, cx, cy - 600, down);
        assertTrue("camera swipe opens camera", r.camera);
        assertTrue("camera swipe is not an unlock", !r.unlocked);
    }
}
