package com.solarized.firedown.phone.fragments;


import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.KeyEvent;
import android.view.PointerIcon;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.AppLock;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.autocomplete.AutoCompleteViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.WebHistoryViewModel;
import com.solarized.firedown.data.repository.SearchRepository;
import com.solarized.firedown.geckoview.GeckoComponents;
import com.solarized.firedown.geckoview.GeckoObserver;
import com.solarized.firedown.geckoview.GeckoObserverRegistry;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.geckoview.media.GeckoMediaController;
import com.solarized.firedown.geckoview.prompt.GeckoPromptManager;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
import com.solarized.firedown.ui.adapters.SearchAutocompleteAdapter;
import com.solarized.firedown.autocomplete.AutoCompleteEditText;
import com.solarized.firedown.autocomplete.AutoCompleteView;
import com.solarized.firedown.utils.BrowserContextActions;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebResponse;

import java.util.ArrayList;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class BaseBrowserFragment extends BaseFocusFragment implements AutoCompleteEditText.OnSearchStateChangeListener,
        AutoCompleteEditText.OnFilterListener, AutoCompleteEditText.OnCommitListener, AutoCompleteEditText.OnTextChangedListener,
        AutoCompleteEditText.OnWindowsFocusChangeListener, AutoCompleteEditText.OnFocusChangedListener,
        GeckoToolbar.OnToolbarListener, GeckoToolbar.OnClearFocusListener, BottomNavigationBar.OnBottomBarListener,
        SwipeRefreshLayout.OnRefreshListener, GeckoObserver {


    private static final String TAG = BaseBrowserFragment.class.getName();

    protected AutoCompleteView mAutoCompleteView;

    protected SearchAutocompleteAdapter mSearchAutocompleteAdapter;

    protected AutoCompleteViewModel mAutoCompleteViewModel;

    // Compact flame download button in the single top bar (Item 2 — was the
    // browser's floating FAB). Typed as View: the toolbar hosts a MaterialButton
    // now, and only show()/hide()/setOnClickListener (all View) are used.
    protected View mDownloadButton;

    @Inject
    protected GeckoObserverRegistry mGeckoObserverRegistry;

    @Inject
    protected SearchRepository mSearchRepository;

    @Inject
    protected SharedPreferences mSharedPreferences;

    @Inject
    protected GeckoRuntimeHelper mGeckoRuntimeHelper;

    @Inject
    protected GeckoComponents mGeckoComponents;
    @Inject
    protected BrowserContextActions mContextActions;
    @Inject
    protected GeckoPromptManager mGeckoPromptManager;
    @Inject
    protected GeckoMediaController mGeckoMediaController;
    @Inject
    protected AppLock mAppLock;

    protected GeckoStateViewModel mGeckoStateViewModel;

    protected WebHistoryViewModel mWebHistoryViewModel;



    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mWebHistoryViewModel = new ViewModelProvider(this).get(WebHistoryViewModel.class);
        mAutoCompleteViewModel = new ViewModelProvider(this).get(AutoCompleteViewModel.class);
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
    }


    // ── Voice search (address-bar mic) ──────────────────────────────────────
    // Chrome-parity: the system voice-recognition dialog (Google's service on
    // most devices) returns text, which lands in the address bar and runs the
    // same commit/search path as typing + enter. The recognized text is handed
    // to onVoiceSearchResult(), which each fragment implements with its own
    // toolbar/commit path.

    private final ActivityResultLauncher<Intent> mVoiceSearchLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            ArrayList<String> matches = result.getData()
                                    .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                            if (matches != null && !matches.isEmpty()) {
                                onVoiceSearchResult(matches.get(0));
                            }
                        }
                    });

    /** Launches the system voice-recognition dialog (no-op if unavailable). */
    protected void launchVoiceSearch() {
        if (mActivity == null) return;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                getString(R.string.voice_search_prompt));
        try {
            mVoiceSearchLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            // No speech service on the device — tell the user quietly.
            makeSnackbar(mActivity.getSnackAnchorView(),
                    R.string.voice_search_unavailable, isVoiceSearchIncognito()).show();
        }
    }

    /** Handles recognized speech — each fragment fills its own toolbar and
     *  commits (default no-op; override in fragments that have an address bar). */
    protected void onVoiceSearchResult(String text) {
    }

    /** The incognito flag for the voice-unavailable snackbar's theme. */
    protected boolean isVoiceSearchIncognito() {
        return false;
    }


    // ── Image search (address-bar Google Lens icon) ─────────────────────────
    // Chrome-parity v1: the system photo picker chooses an image, which opens
    // Google Lens in the current tab so the user can search by that image.

    private final ActivityResultLauncher<String> mImageSearchLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null || mActivity == null) return;
                        // Lens accepts an image via its upload-by-URL surface;
                        // for a local pick we open Lens and prefill with the
                        // content URI where supported.
                        String lensUrl = "https://lens.google.com/uploadbyurl?url="
                                + Uri.encode(uri.toString());
                        openUriInCurrentTab(lensUrl);
                    });

    /** Opens the system photo picker; the picked image opens Google Lens. */
    protected void launchImageSearch() {
        if (mActivity == null) return;
        mImageSearchLauncher.launch("image/*");
    }

    /**
     * Opens a URL in the CURRENT tab (used by the focus icons). Fragments
     * override with their own tab/session semantics; base default is a no-op.
     */
    protected void openUriInCurrentTab(String url) {
    }



    @Override
    public void onCommit() {

    }

    @Override
    public void onTextChanged(String afterText, String currentText) {

    }

    @Override
    public void onWindowsFocusChanged(boolean hasFocus) {

    }

    @Override
    public void onRefreshAutoComplete(String text) {

    }

    @Override
    public void onSearchStateChanged(boolean isActive) {

    }

    @Override
    public void onFocusChanged(boolean hasFocus) {

    }

    @Override
    public void onRefresh() {

    }

    @Override
    public void updateProgress(GeckoState geckoState, int progress) {

    }

    @Override
    public void onLocationChange(GeckoState geckoState, String url) {

    }


    @Override
    public void onFullScreen(GeckoState geckoState, boolean fullScreen) {

    }

    @Override
    public void onShowDynamicToolbar() {

    }

    @Override
    public void onMetaViewportFitChange(String viewPortFit) {

    }

    @Override
    public void onKill(GeckoState geckoState) {

    }

    @Override
    public void onNew(GeckoState geckoState, String uri) {

    }

    @Override
    public void onClose(GeckoState geckoState) {

    }

    @Override
    public void onDownload(WebResponse response) {

    }

    @Override
    public void onThumbnail(GeckoState geckoState) {

    }

    @Override
    public void onLoadRequest(GeckoState geckoState, String uri, boolean autoRedirect, boolean wasRedirector) {

    }

    @Override
    public void onPlayStoreRedirect(GeckoState geckoState, String uri, boolean wasRedirector) {

    }

    @Override
    public void onScrollChange(int scrollY) {

    }

    @Override
    public void onContext(GeckoState geckoState, GeckoSession.ContentDelegate.ContextElement element) {

    }

    @Override
    public void onPromptFile(GeckoState geckoState, GeckoSession.PromptDelegate.FilePrompt filePrompt, Intent intent) {

    }

    @Override
    public void onPromptChoice(GeckoState geckoState, GeckoSession.PromptDelegate.ChoicePrompt prompt) {

    }

    @Override
    public void onPromptAlert(GeckoState geckoState, GeckoSession.PromptDelegate.AlertPrompt prompt) {

    }

    @Override
    public void onPromptButton(GeckoState geckoState, GeckoSession.PromptDelegate.ButtonPrompt prompt) {

    }

    @Override
    public void onPromptText(GeckoState geckoState, GeckoSession.PromptDelegate.TextPrompt prompt) {

    }

    @Override
    public void onPromptRepost(GeckoState geckoState, GeckoSession.PromptDelegate.RepostConfirmPrompt prompt) {

    }

    @Override
    public void onPromptAuth(GeckoState geckoState, GeckoSession.PromptDelegate.AuthPrompt prompt) {

    }

    @Override
    public void onPromptColor(GeckoState geckoState, GeckoSession.PromptDelegate.ColorPrompt prompt) {

    }

    @Override
    public void onPromptUnload(GeckoState geckoState, GeckoSession.PromptDelegate.BeforeUnloadPrompt prompt) {

    }

    @Override
    public void onPromptDate(GeckoState geckoState, GeckoSession.PromptDelegate.DateTimePrompt prompt) {

    }

    @Override
    public void onContentPermission(GeckoState geckoState, GeckoSession.PermissionDelegate.ContentPermission permission, int resId) {

    }

    @Override
    public void onPromptLoginSave(GeckoState geckoState, GeckoSession.PromptDelegate.AutocompleteRequest<?> request, boolean contains) {

    }

    @Override
    public void onMediaPause(GeckoState geckoState, MediaSession mediaSession) {

    }

    @Override
    public void onMediaPlay(GeckoState geckoState, MediaSession mediaSession) {

    }

    @Override
    public void onMediaActivated(GeckoState geckoState, MediaSession mediaSession) {

    }

    @Override
    public void onMediaDeactivated(GeckoState geckoState, MediaSession mediaSession) {

    }

    @Override
    public void onMediaStop(GeckoState geckoState, MediaSession mediaSession) {

    }

    @Override
    public void onMediaMetadata(GeckoState geckoState, MediaSession mediaSession, MediaSession.Metadata metadata) {

    }

    @Override
    public void onMediaPosition(GeckoState geckoState, MediaSession mediaSession, MediaSession.PositionState positionState) {

    }

    @Override
    public void onCrash(GeckoState geckoState) {

    }

    @Override
    public void onOrientation(Integer screenOrientation) {

    }

    @Override
    public void onHideBars(GeckoState geckoState) {

    }

    @Override
    public void onStart(GeckoState geckoState) {

    }

    @Override
    public void onStop(GeckoState geckoState) {

    }


    @Override
    public void onFirstComposite(GeckoState geckoState) {

    }

    @Override
    public void onPointerIconChange(GeckoState geckoState, PointerIcon icon) {

    }

    @Override
    public void onSecurityChange(GeckoState geckoState, GeckoSession.ProgressDelegate.SecurityInformation securityInfo) {

    }

    @Override
    public void onToolbarButtonClick(View v, int id) {

    }

    @Override
    public void onToolbarKey(int keyCode, KeyEvent event) {

    }


    @Override
    public void onBottomBarButtonClick(View v, int id) {

    }

    @Override
    public boolean onBottomBarButtonLongClick(View v, int id) {
        return false;
    }

    @Override
    public void onToolbarClearFocus() {

    }


    protected void flashNewTab(View flashView) {
        if (flashView == null) return;
        flashView.setAlpha(0f);
        flashView.setScaleY(0.95f);
        flashView.setVisibility(View.VISIBLE);
        flashView.animate()
                .alpha(0.8f)
                .scaleY(1f)
                .setDuration(120)
                .setInterpolator(new FastOutSlowInInterpolator())
                .withEndAction(() ->
                        flashView.animate()
                                .alpha(0f)
                                .setDuration(250)
                                .withEndAction(() -> flashView.setVisibility(View.GONE))
                                .start())
                .start();
    }


    protected void quitApp(){
        if (mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF_HISTORY, false)) {
            mWebHistoryViewModel.deleteAll();
        }
        if (mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF_TABS, false)) {
            mGeckoStateViewModel.deleteAll();
        }
        if (mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF_COOKIES, false)) {
            mGeckoRuntimeHelper.getGeckoRuntime().getStorageController().clearData(StorageController.ClearFlags.COOKIES);
        }
        if (mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF_CACHE, false)) {
            mGeckoRuntimeHelper.getGeckoRuntime().getStorageController().clearData(StorageController.ClearFlags.IMAGE_CACHE);
        }
        mActivity.finishAndRemoveTask();
    }
}
