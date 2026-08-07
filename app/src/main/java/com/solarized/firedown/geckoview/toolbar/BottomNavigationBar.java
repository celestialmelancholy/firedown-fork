package com.solarized.firedown.geckoview.toolbar;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.badge.ExperimentalBadgeUtils;
import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.R;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.browser.TabsBrowserButton;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.badge.BadgeUtils;


/**
 * Single-top-bar controller (Item 2). The bottom toolbar is gone; the buttons
 * it used to host now live in the top toolbar's {@code top_bar_actions} cluster
 * ({@code browser_address_bar.xml}). This view remains in the layouts as a
 * zero-height, GONE controller so every existing consumer keeps working:
 * {@link BottomNavigationBehavior}, {@link AnchorBehavior} (snackbar anchoring),
 * {@code NestedGeckoViewBehavior}, {@code FindViewUtils}, and the fragments'
 * {@code onBadgeCount} / {@code onTabsCount} / {@code updateTheme} calls.
 *
 * <p>It wires the (relocated) buttons by id from the fragment root on attach —
 * the buttons keep their original ids and classes, so every fragment's
 * {@code onBottomBarButtonClick}/{@code onBottomBarButtonLongClick} handler
 * works unchanged. All badge / tab-count / theme logic is unchanged; only the
 * badge now attaches to the compact download {@code download_button} (the old
 * {@code downloads_button} slot no longer exists).
 */
public class BottomNavigationBar extends FrameLayout implements View.OnClickListener, View.OnLongClickListener {

    private static final String TAG = BottomNavigationBar.class.getName();

    private OnBottomBarListener mOnBottomBarListener;

    private TabsBrowserButton mTabsCountButton;

    private BadgeDrawable mBadge;

    /** Host root the relocated buttons were bound from ({@link #onAttachedToWindow}). */
    private View mButtonHost;

    /** Last updateTheme args — updateTheme can run in onViewCreated, before the
     *  view reaches the window (mButtonHost null → tints no-op). Re-applied from
     *  onAttachedToWindow once the host binds. */
    @Nullable private Activity mLastThemeActivity;
    private boolean mLastThemeIncognito;
    private boolean mHasTheme;

    // Top hairline divider (Firefox-parity). The bar is painted the page
    // SURFACE tone (not a tonal surfaceContainer step), so it would dissolve
    // into surface content (Home) or arbitrary web content (Browser) without
    // this 1dp line. Drawn as a top-gravity child OVER the bar background, so
    // it rides the bar's scroll-hide. Coloured (incognito-aware) in updateTheme.
    private View mSeparator;

    private boolean mSelfPadSystemBars;


    public interface OnBottomBarListener {
        void onBottomBarButtonClick(View v, int id);
        boolean onBottomBarButtonLongClick(View v, int id);

    }

    public BottomNavigationBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs, 0);
        init(context, attrs, 0);
    }


    public BottomNavigationBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }


    @Override
    public void onClick(View v) {
        if(mOnBottomBarListener != null) mOnBottomBarListener.onBottomBarButtonClick(v, v.getId());
    }

    @Override
    public boolean onLongClick(View v) {
        if(mOnBottomBarListener != null) {
            return mOnBottomBarListener.onBottomBarButtonLongClick(v, v.getId());
        }
        return false;
    }


    private void init(Context context, AttributeSet attrs, int defStyleAttr) {

        TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.BottomNavigationBar, defStyleAttr, 0);
        // hideMiddleSlot is kept declared (layouts still pass it) but is now
        // unused — the bookmarks button it used to hide has moved into the
        // 3-dot popup menu, so there is no middle slot left to hide.
        // Kept for compatibility: Home/incognito Home self-pad the nav inset so
        // the bar owns the nav strip. The browser sets it FALSE — its framed
        // root reserves the strip with all-sides padding. With the bar now
        // zero-height the padding is inert, but the attr contract is unchanged.
        mSelfPadSystemBars = array.getBoolean(R.styleable.BottomNavigationBar_selfPadSystemBars, true);
        array.recycle();

        mBadge = BadgeDrawable.create(context);
        mBadge.setVisible(false);
        // The badge is a bare dot with no label — its background IS the signal,
        // so it is ink, not a container. colorPrimary keeps it legible in both
        // themes; colorPrimaryContainer is now a proper container tone (pale in
        // light, dark in dark) and would disappear.
        mBadge.setBackgroundColor(IncognitoColors.getPrimary(context, false));
        mBadge.setVerticalOffset(getResources().getDimensionPixelOffset(R.dimen.badge_vertical_offset));
        mBadge.setHorizontalOffset(getResources().getDimensionPixelOffset(R.dimen.badge_horizontal_offset));

        // Top hairline divider, added as the LAST child of this FrameLayout so
        // it draws OVER the bar background at the very top edge. 1dp tall;
        // tinted in updateTheme.
        int hairline = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        mSeparator = new View(context);
        mSeparator.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, hairline, Gravity.TOP));
        addView(mSeparator);

        // Self-pad the nav inset UNLESS the host opted out (the browser's
        // framed root reserves the strip instead — see selfPadSystemBars).
        if (mSelfPadSystemBars) {
            applyWindowInsets();
        }
    }


    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // The relocated buttons live in the top toolbar (a sibling of this
        // view within the SAME fragment). They are bound explicitly by the
        // fragment via bindButtons(rootView) in onCreateView — NOT resolved
        // from the window root here: during fragment navigation (home ↔
        // browser) BOTH fragments' views coexist in the window, so a
        // getRootView().findViewById() can bind to the OTHER fragment's
        // toolbar buttons and the listeners die when that view detaches.
        // If a fragment ever skips bindButtons, apply the pending theme now
        // (buttons may be unbound, but the bar's own chrome still tints).
        if (mHasTheme && mLastThemeActivity != null) {
            updateTheme(mLastThemeActivity, mLastThemeIncognito);
        }
    }

    /**
     * Binds the top-bar action cluster for THIS fragment. The fragment passes
     * its own inflated root view (onCreateView's {@code v}), so every lookup
     * resolves the buttons inside its own toolbar — immune to other fragments
     * coexisting in the same window during navigation.
     */
    public void bindButtons(View hostRoot) {
        if (hostRoot == null) return;
        mButtonHost = hostRoot;

        View newTabButton = hostRoot.findViewById(R.id.new_tab_button);
        View downloadButton = hostRoot.findViewById(R.id.download_button);
        View moreButton = hostRoot.findViewById(R.id.more_button);

        // Route clicks for the whole cluster through this controller, exactly
        // as the old bottom bar did for its own children. The compact flame
        // keeps its real id (download_button) — each fragment's
        // onBottomBarButtonClick handles it (browser: Captured sheet, home:
        // Downloads, incognito: Vault).
        View[] clickables = {newTabButton, downloadButton, moreButton,
                hostRoot.findViewById(R.id.tab_button)};
        for (View v : clickables) {
            if (v != null) v.setOnClickListener(this);
        }
        if (newTabButton != null) newTabButton.setOnLongClickListener(this);
        if (downloadButton != null) downloadButton.setOnLongClickListener(this);

        mTabsCountButton = hostRoot.findViewById(R.id.tab_button);

        // Re-apply the last theme now that the host buttons are bound (see the
        // mLastTheme* fields — updateTheme may have run before bind).
        if (mHasTheme && mLastThemeActivity != null) {
            updateTheme(mLastThemeActivity, mLastThemeIncognito);
        }

        // Badge attaches to the compact flame download button (the old
        // downloads_button slot no longer exists). Attach once laid out.
        if (downloadButton != null) {
            downloadButton.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @OptIn(markerClass = ExperimentalBadgeUtils.class)
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    downloadButton.removeOnLayoutChangeListener(this);
                    BadgeUtils.attachBadgeDrawable(mBadge, downloadButton);
                }
            });
        }
    }


    public void updateTheme(Activity activity, boolean incognito) {
        Context context = getContext();
        mLastThemeActivity = activity;
        mLastThemeIncognito = incognito;
        mHasTheme = true;

        // SURFACE (Firefox-parity) — the bar is the page background tone, not a
        // tonal surfaceContainer step. With the bar zero-height the colour is
        // inert, but painted anyway so a future restore keeps the contract.
        int surfaceColor = IncognitoColors.getSurface(activity, incognito);
        int iconColor = IncognitoColors.getOnSurface(activity, incognito);

        ColorStateList iconTint = ColorStateList.valueOf(iconColor);

        setBackgroundColor(surfaceColor);

        View host = mButtonHost != null ? mButtonHost : this;

        // Tint each icon button (resolved from the toolbar cluster).
        View newTabBtn = host.findViewById(R.id.new_tab_button);
        if (newTabBtn instanceof AppCompatImageButton) {
            ImageViewCompat.setImageTintList((AppCompatImageButton) newTabBtn, iconTint);
        }

        View downloadBtn = host.findViewById(R.id.download_button);
        if (downloadBtn instanceof MaterialButton) {
            // Neutral at rest — the flame only turns brand-primary (coral)
            // while something is being caught/downloaded (onBadgeCount > 0),
            // matching main Firedown's download affordance. Incognito resolves
            // its own primary (see IncognitoColors).
            ((MaterialButton) downloadBtn).setIconTint(
                    ColorStateList.valueOf(IncognitoColors.getOnSurfaceVariant(activity, incognito)));
        }

        View moreBtn = host.findViewById(R.id.more_button);
        if (moreBtn instanceof AppCompatImageButton) {
            ImageViewCompat.setImageTintList((AppCompatImageButton) moreBtn, iconTint);
        }

        // Tab counter (TabCountDrawable) — one tint colours its rect + digits
        // (it owns its own stroke, so no GradientDrawable restroke here).
        if (mTabsCountButton != null) {
            mTabsCountButton.setTabsTextColor(iconColor);
        }

        // Badge color — the accent, not the container (see the create() call).
        if (mBadge != null) {
            mBadge.setBackgroundColor(IncognitoColors.getPrimary(context, incognito));
        }

        // Top hairline — a soft line (outlineVariant read too prominent).
        // Any dark surface (system-dark OR incognito) gets a translucent-WHITE
        // line (a black groove is invisible on dark); a light surface gets a
        // faint translucent-BLACK line.
        if (mSeparator != null) {
            int nightMode = getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            boolean dark = incognito || nightMode == Configuration.UI_MODE_NIGHT_YES;
            mSeparator.setBackgroundColor(ContextCompat.getColor(context,
                    dark ? R.color.bottom_bar_divider_dark : R.color.bottom_bar_divider_light));
        }
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout());
            // Self-pad the bottom (nav) inset so the bar's background owns
            // the nav strip; l/r for cutouts. Consume so the inset doesn't
            // also reach a descendant. (Only registered when the host did
            // NOT opt out via selfPadSystemBars — i.e. Home, not browser.)
            v.setPadding(insets.left, 0, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    public void onBadgeCount(int count){
        mBadge.setVisible(count > 0);
        // Keep the badge-dot behavior (active downloads) separate from the
        // media-caught coral: onBadgeCount only drives the dot.
    }

    /**
     * The download flame turns brand-primary (coral) ONLY while the current
     * page has detected downloadable media (the capture-sheet signal). Neutral
     * otherwise — matching main Firedown's "caught something" affordance.
     */
    public void onMediaDetected(boolean hasMedia) {
        View downloadBtn = mButtonHost != null
                ? mButtonHost.findViewById(R.id.download_button) : null;
        if (downloadBtn instanceof MaterialButton && mLastThemeActivity != null) {
            int tint = hasMedia
                    ? IncognitoColors.getPrimary(mLastThemeActivity, mLastThemeIncognito)
                    : IncognitoColors.getOnSurfaceVariant(mLastThemeActivity, mLastThemeIncognito);
            ((MaterialButton) downloadBtn).setIconTint(ColorStateList.valueOf(tint));
        }
    }

    public void onTabsCount(int count) {
        if(mTabsCountButton != null) mTabsCountButton.setTabsCount(count);
    }

    public void setListener(OnBottomBarListener listener) {
        this.mOnBottomBarListener = listener;
    }

    public void show(){
        setVisibility(View.VISIBLE);
    }

    public void hide(){
        setVisibility(View.GONE);
    }




}
