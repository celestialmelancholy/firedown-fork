package com.solarized.firedown.utils;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.interpolator.view.animation.FastOutLinearInInterpolator;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.solarized.firedown.ui.IncognitoColors;

/**
 * Chrome-style "new tab" reveal (values taken from Chromium source:
 * ZOOMING_DURATION = 300ms + FAST_OUT_SLOW_IN for the scale, 150ms
 * FAST_OUT_LINEAR_IN for the fade).
 *
 * <p>The overlay is attached to the ACTIVITY decor and starts expanding
 * IMMEDIATELY when the new-tab button is tapped — over whatever screen is
 * currently live (tab tray, browser, or home).</p>
 *
 * <p>KEY: the caller passes an {@code onCovered} callback. The reveal runs
 * over the LIVE screen first; only when the overlay has FULLY covered the
 * screen does the callback run (the navigation to Home happens underneath
 * the opaque overlay). This is what makes the animation feel like Chrome's:
 * the old screen is what the expand plays over, and the new homepage is
 * never visible until the final fade reveals it — no flash, no snap.</p>
 *
 * <p>Pure visual — no tab state is touched. Safe no-op if the window is
 * gone or an overlay is already playing. Touches are blocked while the
 * overlay is up so nothing underneath can be pressed mid-animation.</p>
 */
public final class NewTabReveal {

    private static final String OVERLAY_TAG = "new_tab_reveal_overlay";
    private static final int ZOOM_DURATION_MS = 300; // Chromium ZOOMING_DURATION
    private static final int HOLD_DURATION_MS = 200; // Home content settle
    private static final int FADE_DURATION_MS = 150; // Chromium DIALOG_ALPHA

    private NewTabReveal() {
    }

    /**
     * Starts the reveal over the current screen. Must be called from the UI
     * thread, at tap time. The {@code onCovered} callback (typically the
     * navigation to Home) runs once the overlay has fully covered the
     * screen. If an overlay is already showing (double-tap), this is a no-op
     * and the callback is NOT invoked.
     *
     * @param activity  the host activity (non-null)
     * @param incognito whether the new tab is incognito (affects the color)
     * @param onCovered runs on the UI thread when the overlay fully covers
     *                  the screen; may be null (no navigation needed)
     */
    public static void play(Activity activity, boolean incognito, Runnable onCovered) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (findOverlay(decor) != null) return;

        final View overlay = new View(activity);
        overlay.setTag(OVERLAY_TAG);
        // Rounded corners matching the app's tab-card radius (Chrome's
        // expanding new-tab surface is rounded too — the overlay starts as a
        // rounded card and only reads as square once it exceeds the screen).
        float radiusPx = activity.getResources().getDimension(
                com.solarized.firedown.R.dimen.tab_card_radius);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(IncognitoColors.getSurface(activity, incognito));
        bg.setCornerRadius(radiusPx);
        overlay.setBackground(bg);
        overlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // Block touches while the overlay is up so nothing underneath (the
        // still-live tray/browser) can be pressed mid-animation.
        overlay.setClickable(true);
        overlay.setFocusable(true);

        // Pivot top-left: the reveal expands diagonally down-right.
        overlay.setPivotX(0f);
        overlay.setPivotY(0f);
        overlay.setScaleX(0.01f);
        overlay.setScaleY(0.01f);
        decor.addView(overlay);

        // Chrome's real values: 300ms scale, FAST_OUT_SLOW_IN.
        overlay.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ZOOM_DURATION_MS)
                .setInterpolator(new FastOutSlowInInterpolator())
                .withEndAction(() -> {
                    // Screen is FULLY covered now — run the navigation
                    // underneath the opaque overlay.
                    if (onCovered != null) onCovered.run();
                    // Hold the covered blank while Home's async sections
                    // (shortcuts/trackers) settle, then fade out revealing
                    // the laid-out homepage.
                    overlay.postDelayed(() -> {
                        overlay.animate()
                                .alpha(0f)
                                .setDuration(FADE_DURATION_MS)
                                .setInterpolator(new FastOutLinearInInterpolator())
                                .withEndAction(() -> {
                                    ViewGroup parent = (ViewGroup) overlay.getParent();
                                    if (parent != null) parent.removeView(overlay);
                                })
                                .start();
                    }, HOLD_DURATION_MS);
                })
                .start();
    }

    private static View findOverlay(ViewGroup decor) {
        for (int i = 0; i < decor.getChildCount(); i++) {
            View child = decor.getChildAt(i);
            if (OVERLAY_TAG.equals(child.getTag())) return child;
        }
        return null;
    }
}
