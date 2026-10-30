package com.yash.sparklelock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * The Xperia Z2 (Android 4.4) lock screen effect:
 * <ul>
 *   <li>a cloud of tiny white "fairy dust" specks swarms around the fingertip, drifts outward
 *       with a little inertia and twinkles out;</li>
 *   <li>while the finger is held down the whole wallpaper slowly cycles through the colour
 *       wheel (blue → purple → magenta → red → orange → yellow → green → …);</li>
 *   <li>the phone does NOT unlock while the finger is down. It unlocks when the finger is
 *       lifted after swiping far enough;</li>
 *   <li>swiping up from the camera icon (bottom right) opens the camera.</li>
 * </ul>
 * Everything is drawn with calls Android 9's hardware renderer supports (drawBitmap with a
 * colour-matrix filter, drawPoints).
 */
public class SparkleView extends View {

    public interface Listener {
        void onUnlockGesture();
        void onRevealFinished();
        default void onCameraGesture() { }
        default void onEffectTouchDown() { }
        default void onEffectSettled() { }
    }

    private static final int STATE_IDLE = 0, STATE_TOUCH = 1, STATE_REVEAL = 3;
    private static final int MAX_P = 1400;
    private static final long FACE_FADE_MS = 300;
    private static final long BACKDROP_FADE_MS = 180;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float density;
    private final Random rnd = new Random();

    // particles (structure of arrays)
    private final float[] px = new float[MAX_P], py = new float[MAX_P];
    private final float[] vx = new float[MAX_P], vy = new float[MAX_P];
    private final float[] life = new float[MAX_P], maxLife = new float[MAX_P];
    private final float[] size = new float[MAX_P], phase = new float[MAX_P];
    private int count;

    // draw batching: 3 sizes x 4 alpha levels
    private final float[][] buckets = new float[12][MAX_P * 2];
    private final int[] bucketLen = new int[12];
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scenePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ColorMatrix hueMatrix = new ColorMatrix();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private Listener listener;
    private Haptics haptics;
    private Bitmap scene;
    private float unlockFraction = 0.25f;
    private float amount = 1f;          // particle density multiplier
    private float hueSpeed = 12f;       // degrees per second while held
    private boolean colorShift = true;
    private float hue;                  // current hue rotation in degrees

    private int state = STATE_IDLE;
    private float downX, downY, touchX, touchY, lastX, lastY, progress;
    private long lastMoveMs, lastSparkleTick;
    private float fingerVx, fingerVy;
    private boolean thresholdBuzzed, wasMoving;
    private long lastFrameNs;
    private float time;

    // camera shortcut
    private boolean cameraEnabled = true;
    private boolean cameraDrag;
    private float cameraDy;
    private final Paint camPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path camPath = new Path();

    // unlock reveal
    private float backdrop = 1f, backdropFrom = 1f, backdropTarget = 1f;
    private long backdropAnimStart, faceFadeStart = -1;
    private float faceAlpha = 1f;
    private boolean revealDone, allowFinishWhenOpaque = true;

    public SparkleView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        dotPaint.setStyle(Paint.Style.STROKE);
        dotPaint.setStrokeCap(Paint.Cap.ROUND);
        camPaint.setStyle(Paint.Style.STROKE);
        camPaint.setStrokeJoin(Paint.Join.ROUND);
        camPaint.setStrokeCap(Paint.Cap.ROUND);
        setHapticFeedbackEnabled(false);
    }

    public void setListener(Listener l) { listener = l; }

    public void configure(float unlockFrac, float particleAmount, float hueDegPerSec,
                          boolean shiftColors, boolean camera, Haptics h) {
        unlockFraction = Math.max(0.1f, Math.min(0.6f, unlockFrac));
        amount = Math.max(0.2f, Math.min(3f, particleAmount));
        hueSpeed = hueDegPerSec;
        colorShift = shiftColors;
        cameraEnabled = camera;
        haptics = h;
        invalidate();
    }

    public void setScene(Bitmap b) { scene = b; invalidate(); }
    public Bitmap getScene() { return scene; }
    public float getHue() { return hue; }
    public void setHue(float h) { hue = ((h % 360f) + 360f) % 360f; invalidate(); }

    public void resetToLocked() {
        state = STATE_IDLE;
        backdrop = backdropFrom = backdropTarget = 1f;
        faceFadeStart = -1;
        faceAlpha = 1f;
        applyFaceAlpha();
        revealDone = false;
        progress = 0;
        count = 0;
        cameraDrag = false;
        cameraDy = 0;
        invalidate();
    }

    // ------------------------------------------------------------------ unlock reveal

    public void startReveal(boolean backdropClear) {
        state = STATE_REVEAL;
        revealDone = false;
        // a final puff of dust outward from the finger
        float cx = touchX > 0 ? touchX : getWidth() / 2f, cy = touchY > 0 ? touchY : getHeight() / 2f;
        for (int i = 0; i < 220 * amount; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = (60 + rnd.nextFloat() * 420) * density;
            spawn(cx + (float) Math.cos(a) * 20 * density, cy + (float) Math.sin(a) * 20 * density,
                    (float) Math.cos(a) * sp, (float) Math.sin(a) * sp, 0.5f + rnd.nextFloat() * 0.6f);
        }
        allowFinishWhenOpaque = !backdropClear;
        if (backdropClear) setBackdropClear();
        kick();
    }

    public void setBackdropClear() {
        if (backdropTarget == 0f) return;
        backdropFrom = backdrop;
        backdropTarget = 0f;
        backdropAnimStart = SystemClock.uptimeMillis();
        allowFinishWhenOpaque = true;
        if (faceFadeStart < 0) faceFadeStart = backdropAnimStart;
        kick();
    }

    public void forceFinishAllowed() {
        allowFinishWhenOpaque = true;
        if (faceFadeStart < 0) faceFadeStart = SystemClock.uptimeMillis();
        kick();
    }

    private void applyFaceAlpha() {
        if (getAlpha() != faceAlpha) setAlpha(faceAlpha);
    }

    // ------------------------------------------------------------------ touch

    private float camCx() { return getWidth() - 46 * density; }
    private float camCy() { return getHeight() - 46 * density; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (state == STATE_REVEAL) return true;
        int h = Math.max(1, getHeight());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = touchX = lastX = e.getX();
                downY = touchY = lastY = e.getY();
                lastMoveMs = e.getEventTime();
                fingerVx = fingerVy = 0;
                progress = 0;
                thresholdBuzzed = false;
                state = STATE_TOUCH;
                cameraDrag = cameraEnabled
                        && Math.hypot(downX - camCx(), downY - camCy()) < 44 * density;
                cameraDy = 0;
                wasMoving = true;
                if (haptics != null) haptics.touchDown();
                if (listener != null) listener.onEffectTouchDown();
                kick();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                touchX = e.getX();
                touchY = e.getY();
                long t = e.getEventTime();
                float dt = Math.max(1, t - lastMoveMs) / 1000f;
                float ivx = (touchX - lastX) / dt, ivy = (touchY - lastY) / dt;
                fingerVx = 0.6f * fingerVx + 0.4f * ivx;
                fingerVy = 0.6f * fingerVy + 0.4f * ivy;
                // dust along the path, so fast strokes leave a trail
                float seg = (float) Math.hypot(touchX - lastX, touchY - lastY);
                int n = (int) (seg / (5 * density) * amount);
                for (int i = 0; i < n; i++) {
                    float f = rnd.nextFloat();
                    spawnAround(lastX + (touchX - lastX) * f, lastY + (touchY - lastY) * f, 0.7f);
                }
                lastX = touchX;
                lastY = touchY;
                lastMoveMs = t;

                if (cameraDrag) {
                    cameraDy = Math.min(0, touchY - downY);
                    progress = Math.min(1f, -cameraDy / (0.18f * h));
                } else {
                    // Like the Z2: wander as much as you like; once you've been far enough
                    // from where you touched down, letting go unlocks.
                    float dist = (float) Math.hypot(touchX - downX, touchY - downY);
                    progress = Math.max(progress, Math.min(1f, dist / (unlockFraction * h)));
                }
                if (progress >= 1f && !thresholdBuzzed) {
                    thresholdBuzzed = true;
                    if (haptics != null) haptics.threshold();
                }
                float speed = (float) Math.hypot(fingerVx, fingerVy);
                if (haptics != null && speed > 300 * density && t - lastSparkleTick > 70) {
                    lastSparkleTick = t;
                    haptics.slatTick(0.12f + 0.35f * Math.min(1f, speed / (2500 * density)));
                }
                kick();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                boolean go = up && progress >= 1f;
                if (up) performClick();
                state = STATE_IDLE;
                if (cameraDrag) {
                    cameraDrag = false;
                    if (go && listener != null) {
                        if (haptics != null) haptics.unlock();
                        listener.onCameraGesture();
                    }
                    cameraDy = 0;
                } else if (go) {
                    if (listener != null) listener.onUnlockGesture();
                } else if (progress > 0.15f && haptics != null) {
                    haptics.cancel();
                }
                kick();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    // ------------------------------------------------------------------ particles

    private void spawn(float x, float y, float svx, float svy, float lifeS) {
        int i;
        if (count < MAX_P) {
            i = count++;
        } else {
            i = rnd.nextInt(MAX_P); // recycle a random old speck
        }
        px[i] = x; py[i] = y; vx[i] = svx; vy[i] = svy;
        life[i] = maxLife[i] = lifeS;
        float r = rnd.nextFloat();
        size[i] = (r < 0.6f ? 1.1f : r < 0.9f ? 1.8f : 2.7f) * density;
        phase[i] = rnd.nextFloat() * 6.283f;
    }

    /** One speck in the cloud around a point: a ring around the fingertip, drifting outward. */
    private void spawnAround(float cx, float cy, float lifeScale) {
        float R = 46 * density;
        double a = rnd.nextDouble() * Math.PI * 2;
        float r = R * (0.35f + 1.25f * (float) Math.pow(rnd.nextFloat(), 0.7));
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
        float out = (8 + rnd.nextFloat() * 34) * density;
        spawn(cx + ca * r, cy + sa * r,
                ca * out + fingerVx * 0.12f + (rnd.nextFloat() - 0.5f) * 14 * density,
                sa * out + fingerVy * 0.12f + (rnd.nextFloat() - 0.5f) * 14 * density,
                (0.45f + rnd.nextFloat() * 1.1f) * lifeScale);
    }

    private void kick() {
        lastFrameNs = 0;
        postInvalidateOnAnimation();
    }

    private boolean step() {
        long nowNs = SystemClock.elapsedRealtimeNanos();
        float dt = lastFrameNs == 0 ? 1f / 60f : (nowNs - lastFrameNs) / 1e9f;
        lastFrameNs = nowNs;
        dt = Math.min(dt, 1f / 30f);
        time += dt;
        boolean moving = false;
        long nowMs = SystemClock.uptimeMillis();

        if (state == STATE_TOUCH) {
            moving = true;
            if (colorShift && !cameraDrag) hue = (hue + hueSpeed * dt) % 360f;
            // steady emission while the finger rests
            float rate = 210f * amount;
            int n = (int) (rate * dt);
            if (rnd.nextFloat() < rate * dt - n) n++;
            for (int i = 0; i < n; i++) spawnAround(touchX, touchY, 1f);
            // finger velocity relaxes when it stops
            float k = (float) Math.exp(-dt * 6);
            fingerVx *= k;
            fingerVy *= k;
        }

        // particles
        float drag = (float) Math.exp(-dt * 1.6f);
        int i = 0;
        while (i < count) {
            life[i] -= dt;
            if (life[i] <= 0) {
                int last = --count;
                px[i] = px[last]; py[i] = py[last]; vx[i] = vx[last]; vy[i] = vy[last];
                life[i] = life[last]; maxLife[i] = maxLife[last]; size[i] = size[last]; phase[i] = phase[last];
                continue;
            }
            // gentle swirl + float
            float sw = (float) Math.sin(time * 2.1f + phase[i]) * 10 * density;
            vx[i] = vx[i] * drag + sw * dt * 4;
            vy[i] = vy[i] * drag - 4 * density * dt;
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            i++;
        }
        if (count > 0) moving = true;

        if (backdrop != backdropTarget) {
            float t = Math.min(1f, (nowMs - backdropAnimStart) / (float) BACKDROP_FADE_MS);
            backdrop = backdropFrom + (backdropTarget - backdropFrom) * t;
            moving = true;
        }
        if (state == STATE_REVEAL) {
            moving = true;
            boolean done = false;
            if (faceFadeStart >= 0) {
                float ft = Math.min(1f, (nowMs - faceFadeStart) / (float) FACE_FADE_MS);
                faceAlpha = 1f - ft * ft;
                applyFaceAlpha();
                done = ft >= 1f;
            }
            if (done && !revealDone && backdrop == backdropTarget && allowFinishWhenOpaque) {
                revealDone = true;
                if (listener != null) mainHandler.post(listener::onRevealFinished);
                return false;
            }
        }
        // camera icon springs back
        if (!cameraDrag && cameraDy != 0) {
            cameraDy *= (float) Math.exp(-dt * 14);
            if (Math.abs(cameraDy) < 0.5f) cameraDy = 0;
            moving = true;
        }
        return moving;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        boolean again = step();
        if (again) {
            wasMoving = true;
        } else if (wasMoving && state == STATE_IDLE) {
            wasMoving = false;
            if (listener != null) mainHandler.post(listener::onEffectSettled);
        }
        int w = getWidth(), h = getHeight();
        if (backdrop > 0f) canvas.drawColor(Color.argb(Math.round(backdrop * 255), 6, 8, 12));

        if (scene != null) {
            src.set(0, 0, scene.getWidth(), scene.getHeight());
            dst.set(0, 0, w, h);
            if (hue > 0.05f && hue < 359.95f) {
                setHueRotation(hueMatrix, hue);
                scenePaint.setColorFilter(new ColorMatrixColorFilter(hueMatrix));
            } else {
                scenePaint.setColorFilter(null);
            }
            canvas.drawBitmap(scene, src, dst, scenePaint);
        }
        if (cameraEnabled) drawCamera(canvas);
        drawParticles(canvas);
        if (again) postInvalidateOnAnimation();
    }

    private void drawParticles(Canvas canvas) {
        if (count == 0) return;
        for (int b = 0; b < 12; b++) bucketLen[b] = 0;
        float d = density;
        for (int i = 0; i < count; i++) {
            float lf = life[i] / maxLife[i];              // 1 → 0
            float fadeIn = Math.min(1f, (1f - lf) * 8f);  // quick appear
            float tw = 0.55f + 0.45f * (float) Math.sin(time * 9f + phase[i] * 3f);
            float a = fadeIn * (float) Math.pow(lf, 0.7) * tw;
            if (a < 0.04f) continue;
            int ab = a > 0.75f ? 3 : a > 0.5f ? 2 : a > 0.25f ? 1 : 0;
            int sb = size[i] > 2.2f * d ? 2 : size[i] > 1.4f * d ? 1 : 0;
            int b = sb * 4 + ab;
            float[] arr = buckets[b];
            int k = bucketLen[b];
            arr[k] = px[i];
            arr[k + 1] = py[i];
            bucketLen[b] = k + 2;
        }
        float[] sizes = {1.1f * d, 1.8f * d, 2.7f * d};
        int[] alphas = {60, 120, 185, 245};
        for (int b = 0; b < 12; b++) {
            if (bucketLen[b] == 0) continue;
            dotPaint.setStrokeWidth(sizes[b / 4]);
            dotPaint.setColor(Color.argb(alphas[b % 4], 255, 255, 255));
            canvas.drawPoints(buckets[b], 0, bucketLen[b], dotPaint);
        }
    }

    /** A small camera glyph, bottom right; it follows the finger up during the camera swipe. */
    private void drawCamera(Canvas c) {
        float cx = camCx(), cy = camCy() + cameraDy;
        float s = 11 * density;
        camPaint.setStrokeWidth(1.8f * density);
        camPaint.setColor(Color.argb(cameraDrag ? 255 : 210, 255, 255, 255));
        camPath.reset();
        camPath.addRoundRect(cx - s, cy - s * 0.62f, cx + s, cy + s * 0.78f, 3 * density, 3 * density, Path.Direction.CW);
        c.drawPath(camPath, camPaint);
        c.drawCircle(cx, cy + s * 0.08f, s * 0.42f, camPaint);
        c.drawLine(cx - s * 0.45f, cy - s * 0.62f, cx - s * 0.25f, cy - s * 0.95f, camPaint);
        c.drawLine(cx - s * 0.25f, cy - s * 0.95f, cx + s * 0.25f, cy - s * 0.95f, camPaint);
        c.drawLine(cx + s * 0.25f, cy - s * 0.95f, cx + s * 0.45f, cy - s * 0.62f, camPaint);
    }

    /** Luminance-preserving hue rotation (same maths as CSS hue-rotate); white stays white. */
    static void setHueRotation(ColorMatrix m, float degrees) {
        double r = Math.toRadians(degrees);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        m.set(new float[]{
                0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0, 0,
                0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0, 0,
                0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0, 0,
                0, 0, 0, 1, 0});
    }
}
