package com.yash.sparklelock;

import android.app.Activity;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Full-screen activity that sits on top of the system keyguard (showWhenLocked) and shows the
 * sparkle lock screen. Unlocking plays the dust burst + fade and asks the system to dismiss
 * its own keyguard, so a PIN / pattern / fingerprint stays fully in force.
 */
public class LockActivity extends Activity implements SparkleView.Listener, WidgetPager.Host {

    public static final String EXTRA_PREVIEW = "preview";

    private SparkleView effect;
    private Haptics haptics;
    private boolean preview;
    private boolean finishing;
    private boolean handOffPending, handingOff;
    private int bottomInset;
    private Bitmap currentScene;
    private WidgetPager pager;
    private AppWidgetHost widgetHost;
    private String pagesSignature = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { rebuildScene(); }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && !finishing) {
                saveHue();
                effect.resetToLocked();
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction()) && handingOff) {
                finishQuietly(); // unlocked via the system UI
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preview = getIntent().getBooleanExtra(EXTRA_PREVIEW, false);
        setShowWhenLocked(true);

        Window win = getWindow();
        win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        win.setStatusBarColor(Color.TRANSPARENT);
        win.setNavigationBarColor(Color.TRANSPARENT);
        WindowManager.LayoutParams lp = win.getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        win.setAttributes(lp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            win.setDecorFitsSystemWindows(false);
        }

        FrameLayout root = new LockRoot(this);
        effect = new SparkleView(this);
        effect.setListener(this);
        root.addView(effect, new FrameLayout.LayoutParams(-1, -1));
        pager = new WidgetPager(this);
        pager.setHost(this);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1, Gravity.TOP));
        widgetHost = Widgets.host(this);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                //noinspection deprecation
                b = insets.getSystemWindowInsetBottom();
            }
            if (b != bottomInset) {
                bottomInset = b;
                rebuildScene();
            }
            return insets;
        });
        effect.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != or - ol || bt - t != ob - ot) rebuildScene();
        });
        setContentView(root);
        hideNavigation(); // after setContentView: the window's decor must exist
        applyPrefs();
        buildPages();

        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, sf);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false);
        finishing = false;
        handOffPending = false;
        handingOff = false;
        handler.removeCallbacksAndMessages(null);
        setShowWhenLocked(true);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        hideNavigation();
        pager.animate().cancel();
        pager.setAlpha(1f);
        effect.resetToLocked();
        if (currentScene != null) effect.setScene(currentScene);
    }

    @Override
    protected void onStart() {
        super.onStart();
        try { widgetHost.startListening(); } catch (Exception ignored) { }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { widgetHost.stopListening(); } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPrefs();
        buildPages();
        rebuildScene();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, f);
        if (!handingOff) hideNavigation();
    }

    /**
     * Hides the navigation bar (immersive, sticky) so Home / Recents can't be tapped to skip the
     * effect. Apps can't intercept those buttons, but with the bar hidden they only come back
     * after a deliberate swipe in from the bottom edge, and then hide again.
     */
    @SuppressWarnings("deprecation")
    private void hideNavigation() {
        Window win = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = win.getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.navigationBars());
            }
        } else {
            win.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !handingOff) hideNavigation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(timeReceiver); } catch (IllegalArgumentException ignored) { }
    }

    private void applyPrefs() {
        Prefs p = new Prefs(this);
        haptics = new Haptics(this);
        effect.configure(p.unlockPct() / 100f, p.sparkleAmount() / 5f, p.hueSpeed(),
                p.colorShift(), p.cameraShortcut(), haptics);
        effect.setHue(p.keepColor() ? p.hue() : 0f);
    }

    private void rebuildScene() {
        int w = effect.getWidth(), h = effect.getHeight();
        if (w <= 0 || h <= 0) return;
        Bitmap old = currentScene;
        currentScene = SceneRenderer.compose(this, w, h, bottomInset);
        effect.setScene(currentScene);
        if (old != null && old != currentScene) old.recycle();
        if (pager != null) {
            View cp = pager.currentPageView();
            if (cp instanceof WidgetPager.ClockPage) cp.invalidate();
        }
    }

    // ------------------------------------------------------------------ widgets

    /**
     * Root layout: effect fill the screen; the widget strip sits from ~7% to ~43% of the screen
     * height (where the Xperia clock sat). Positioned in the same layout pass.
     */
    private static final class LockRoot extends FrameLayout {
        LockRoot(android.content.Context c) { super(c); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                int hh = ch instanceof WidgetPager ? Math.round(h * 0.36f) : h;
                ch.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(hh, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int h = b - t;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                int top = ch instanceof WidgetPager ? Math.round(h * 0.07f) : 0;
                ch.layout(0, top, r - l, top + ch.getMeasuredHeight());
            }
        }
    }

    /** (Re)creates the pages: clock, the user's widgets, then "+". */
    private void buildPages() {
        Prefs p = new Prefs(this);
        java.util.List<Integer> ids = Widgets.validIds(this);
        String sig = p.showClock() + ":" + ids;
        if (sig.equals(pagesSignature)) return;
        pagesSignature = sig;
        pager.removeAllViews();
        if (p.showClock()) pager.addView(new WidgetPager.ClockPage(this));
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        for (int id : ids) {
            try {
                AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
                if (info == null) continue;
                AppWidgetHostView hv = widgetHost.createView(this, id, info);
                pager.addView(new WidgetPager.WidgetPage(this, hv));
            } catch (Exception ignored) {
            }
        }
        pager.addView(new WidgetPager.AddPage(this, this::onAddWidgetTapped));
        pager.setPageImmediate(p.widgetPage());
    }

    @Override
    public void onCameraGesture() {
        // Sony-style lock screen camera: the secure camera opens on top of the lock screen.
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = km != null && km.isKeyguardLocked();
        Intent cam = new Intent(locked ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        try {
            startActivity(cam);
        } catch (Exception e) {
            try {
                startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onForwardToEffect(MotionEvent rootEvent) {
        effect.dispatchTouchEvent(rootEvent);
    }

    @Override
    public void onPageChanged(int page) {
        new Prefs(this).setWidgetPage(page);
        if (haptics != null) haptics.slatTick(0.45f);
    }

    @Override
    public void onAddWidgetTapped() {
        Intent target = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ADD_WIDGET, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        openAfterUnlock(target);
    }

    /** Unlocks (system PIN etc. on top, as in the hand-off) and then opens {@code target}. */
    private void openAfterUnlock(Intent target) {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (preview || km == null || !km.isKeyguardLocked()) {
            startActivity(target);
            finishQuietly();
            return;
        }
        finishing = true;
        handingOff = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        setShowWhenLocked(false);
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() {
                startActivity(target);
                finishQuietly();
            }
            @Override public void onDismissCancelled() { finishQuietly(); }
            @Override public void onDismissError() { finishQuietly(); }
        });
    }

    // ------------------------------------------------------------------ unlock flow

    @Override
    public void onUnlockGesture() {
        if (finishing) return;
        if (haptics != null) haptics.unlock();
        saveHue();
        pager.animate().alpha(0f).setDuration(200).start();

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = !preview && km != null && km.isKeyguardLocked();

        if (!locked) {
            // Already unlocked (e.g. fingerprint while the screen was off): open straight away.
            finishing = true;
            effect.startReveal(true);
            return;
        }

        // isDeviceLocked() is false when no credential is needed right now: Swipe/None,
        // or a trusted state such as Smart Lock / Extend Unlock.
        if (km.isKeyguardSecure() && km.isDeviceLocked()) {
            // PIN / pattern / password: open the effect, fade our screen away, then hand over
            // to Android's own lock screen so its PIN pad / fingerprint / face unlock is shown
            // ON TOP (on some Android 9 phones it otherwise opens hidden behind us).
            finishing = true;
            handOffPending = true;
            effect.startReveal(false);
            handler.postDelayed(effect::setBackdropClear, 200);
        } else {
            // Swipe / None / trusted: dismiss directly; fade once the keyguard has gone.
            finishing = true;
            effect.startReveal(false);
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() {
                    // Give the system keyguard a moment to leave (Android 9 animates it).
                    handler.postDelayed(effect::setBackdropClear, 120);
                }
                @Override public void onDismissCancelled() { effect.forceFinishAllowed(); }
                @Override public void onDismissError() { effect.forceFinishAllowed(); }
            });
            handler.postDelayed(effect::forceFinishAllowed, 1500);
        }
    }

    @Override
    public void onRevealFinished() {
        handler.removeCallbacksAndMessages(null);
        if (handOffPending) {
            handOffToSystem();
            return;
        }
        finishQuietly();
    }

    /**
     * Steps out of the way of the system keyguard: we stop showing over the lock screen and
     * stop taking touches, then ask the system to show its unlock UI. We finish when the
     * phone is unlocked (or the user backs out).
     */
    private void handOffToSystem() {
        handOffPending = false;
        handingOff = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        setShowWhenLocked(false);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked()) {
            finishQuietly();
            return;
        }
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { finishQuietly(); }
            @Override public void onDismissCancelled() { finishQuietly(); }
            @Override public void onDismissError() { finishQuietly(); }
        });
        // Safety net: never leave an invisible activity around.
        handler.postDelayed(() -> {
            if (handingOff && !km.isKeyguardLocked()) finishQuietly();
        }, 60_000);
    }

    /** Remembers the colour the wallpaper has drifted to (Xperia Z2 kept it). */
    private void saveHue() {
        Prefs p = new Prefs(this);
        if (p.keepColor()) p.setHue(effect.getHue());
    }

    private void finishQuietly() {
        if (isFinishing()) return;
        handler.removeCallbacksAndMessages(null);
        finish();
        overridePendingTransition(0, 0);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (preview) super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }
}
