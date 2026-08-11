package com.solarized.firedown.phone.fragments;


import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.PointerIcon;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.exifinterface.media.ExifInterface;
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

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    @Inject
    protected okhttp3.OkHttpClient mOkHttpClient;

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


    // ── Image search (address-bar camera icon) ──────────────────────────────
    // The system photo picker chooses an image, then the image bytes are
    // uploaded to OUR OWN relay (Cloudflare Worker + R2, this repo's /relay),
    // which auto-deletes after 1 hour. The returned public URL is then handed
    // to the user's selected search engine's URL-based image search endpoint.
    //
    // WHY this flow (verified 2026-08-11 with curl):
    //   - Google's /searchbyimage/upload (legacy direct POST) returns 404 —
    //     Google is mid-rollout on a major Images redesign.
    //   - Yandex's classic cbir_id=0 / upfile direct upload is also dead — it
    //     307s to a plain page with an empty cbirId even with a full cookie
    //     session (verified).
    //   - All engines' URL-based endpoints work reliably with a publicly
    //     reachable image URL (Google Lens uploadbyurl, Yandex rpt=imageview,
    //     Bing imgurl: — all verified returning real result tokens).
    //   - Our relay accepts the same multipart contract (fileToUpload + time),
    //     stores in OUR R2 bucket (hard 450MB cap), 1-minute cron deletes
    //     expired images (verified upload → fetch-back → delete round trip).

    private static final String IMAGE_TEMP_HOST_URL = "https://waves-image-relay.nahidhasansajid4620.workers.dev/upload";
    private static final String IMAGE_HOST_TTL = "1h"; // auto-delete after 1 hour
    private static final int IMAGE_SEARCH_MAX_DIM = 1280; // downscale long edge (px)
    private static final long IMAGE_SEARCH_MAX_SOURCE_BYTES = 10L * 1024 * 1024; // refuse >10MB source

    /** Progress snackbar shown while the image is being prepared + uploaded. */
    private Snackbar mImageSearchWorking;

    private final ActivityResultLauncher<String> mImageSearchLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null || mActivity == null) return;
                        uploadImageToSearch(uri);
                    });

    /**
     * Reads the picked image, downscales it (camera photos are 3–10MB; a
     * search thumbnail needs far less), uploads to our relay, then opens the
     * selected engine's URL-based image search in the current tab. Shows a
     * progress snackbar immediately so the tap doesn't look dead. On failure
     * shows an error snackbar and leaves the tab unchanged.
     */
    private void uploadImageToSearch(Uri imageUri) {
        Activity activity = mActivity;
        if (activity == null) return;
        if (mOkHttpClient == null) {
            Log.w(TAG, "uploadImageToSearch: OkHttpClient not injected");
            showImageSearchError(activity);
            return;
        }

        // Instant feedback: the picker just closed; tell the user we're
        // searching before the background work starts. Use the app's own
        // snackbar helper + anchor so it reliably shows (direct Snackbar.make
        // on the fragment view can silently no-op during transitions).
        activity.runOnUiThread(() -> {
            View anchor = mActivity != null ? mActivity.getSnackAnchorView() : getView();
            if (anchor != null) {
                mImageSearchWorking = makeSnackbar(anchor, R.string.image_search_working,
                        isVoiceSearchIncognito());
                mImageSearchWorking.show();
            }
        });

        new Thread(() -> {
            try {
                // Downscale before upload: keeps uploads ~200–500KB instead of
                // 3–10MB — the single biggest speedup (mobile data + engine
                // fetch-back both get much faster). Also caps at 10MB hard.
                byte[] imageBytes = downscaleForUpload(activity, imageUri);
                if (imageBytes == null) {
                    showImageSearchError(activity);
                    return;
                }
                String publicUrl = uploadToTempHost(activity, imageBytes, "image/jpeg");
                if (publicUrl == null) {
                    showImageSearchError(activity);
                    return;
                }
                String searchUrl = buildEngineImageSearchUrl(publicUrl);
                if (searchUrl == null) {
                    showImageSearchError(activity);
                    return;
                }
                String openUrl = searchUrl;
                activity.runOnUiThread(() -> {
                    dismissImageSearchWorking(activity);
                    openUriInCurrentTab(openUrl);
                });
            } catch (Exception e) {
                Log.w(TAG, "image search upload failed", e);
                showImageSearchError(activity);
            }
        }).start();
    }

    /**
     * Loads the picked image, applies EXIF rotation, downscales to at most
     * {@link #IMAGE_SEARCH_MAX_DIM} px (long edge), and re-encodes as JPEG.
     * Returns the JPEG bytes, or {@code null} on any failure / >10MB file.
     */
    private byte[] downscaleForUpload(Activity activity, Uri imageUri) {
        try {
            // The hard ceiling is on the ACTUAL FILE SIZE (what's on disk),
            // not decoded bitmap bytes — a 2MB JPEG is often 4000×3000px,
            // which would "look like" 48MB of raw pixels.
            long fileBytes;
            try (InputStream in = activity.getContentResolver().openInputStream(imageUri)) {
                if (in == null) return null;
                fileBytes = countBytes(in);
            }
            if (fileBytes > IMAGE_SEARCH_MAX_SOURCE_BYTES) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = activity.getContentResolver().openInputStream(imageUri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            // Compute the sample size to get under the max dimension.
            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / (sample * 2) >= IMAGE_SEARCH_MAX_DIM) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;

            Bitmap bitmap;
            try (InputStream in = activity.getContentResolver().openInputStream(imageUri)) {
                if (in == null) return null;
                bitmap = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bitmap == null) return null;

            // EXIF orientation (rotated camera photos) — apply it so the
            // search engine sees the photo upright.
            int rotation = 0;
            try (InputStream in = activity.getContentResolver().openInputStream(imageUri)) {
                if (in != null) {
                    ExifInterface exif = new ExifInterface(in);
                    int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    if (ori == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                    else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                    else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
                }
            } catch (Exception ignored) { }
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
                if (rotated != bitmap) {
                    bitmap.recycle();
                    bitmap = rotated;
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
            byte[] bytes = out.toByteArray();
            bitmap.recycle();
            return bytes;
        } catch (Exception e) {
            Log.w(TAG, "downscaleForUpload failed", e);
            return null;
        }
    }

    private void dismissImageSearchWorking(Activity activity) {
        activity.runOnUiThread(() -> {
            if (mImageSearchWorking != null) {
                mImageSearchWorking.dismiss();
                mImageSearchWorking = null;
            }
        });
    }

    /**
     * Uploads the image bytes to our relay (Cloudflare Worker + R2) and
     * returns the public URL, or {@code null} on failure. The relay auto-deletes
     * the file after {@link #IMAGE_HOST_TTL} — nothing is stored permanently.
     */
    private String uploadToTempHost(Activity activity, byte[] imageBytes, String mimeType) {
        try {
            String boundary = "----WavesImageSearch" + System.nanoTime();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeMultipart(body, boundary, "fileToUpload", "image", mimeType, imageBytes);
            // Relay TTL field — "1h" auto-deletes the file after an hour.
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(("fileupload").getBytes(StandardCharsets.UTF_8));
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Disposition: form-data; name=\"time\"\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write((IMAGE_HOST_TTL).getBytes(StandardCharsets.UTF_8));
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            RequestBody requestBody = RequestBody.create(body.toByteArray(),
                    MediaType.parse("multipart/form-data; boundary=" + boundary));

            Request request = new Request.Builder()
                    .url(IMAGE_TEMP_HOST_URL)
                    .post(requestBody)
                    .build();

            try (okhttp3.Response response = mOkHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "temp host upload failed: HTTP " + response.code());
                    return null;
                }
                String url = response.body() != null ? response.body().string().trim() : null;
                if (url == null || !url.startsWith("http")) {
                    Log.w(TAG, "temp host upload returned unexpected body: " + url);
                    return null;
                }
                return url;
            }
        } catch (Exception e) {
            Log.w(TAG, "temp host upload failed", e);
            return null;
        }
    }

    /**
     * Builds the URL-based image search URL for the user's currently selected
     * search engine. All templates verified working 2026-08-11 (curl): each
     * returns a real results URL with a search token (Google vsrid, Yandex
     * cbirId, Bing bcid). Engines without a reverse-image-search surface fall
     * back to Yandex.
     */
    private String buildEngineImageSearchUrl(String publicUrl) {
        // Same source of truth the rest of the app uses: the engine display
        // name (e.g. "DuckDuckGo") or the custom-engine sentinel.
        String engineName = mSearchRepository.getSearchType();
        if (engineName == null || engineName.isEmpty()) engineName = "Yandex";
        if (publicUrl == null || !publicUrl.startsWith("http")) return null;
        String enc = Uri.encode(publicUrl, ":/?#[]@!$&'()*+,;=%");
        switch (engineName) {
            case "Google":
                return "https://lens.google.com/uploadbyurl?url=" + enc;
            case "Bing":
            case "DuckDuckGo":
            case "Ecosia":
                // DuckDuckGo and Ecosia are both Bing-powered — reuse Bing's
                // URL-based visual search (same index, no extra adapter).
                return "https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVSP&sbisrc=UrlPaste&q=imgurl:" + enc;
            case "Yandex":
                // Verified 2026-08-11: returns a real cbirId and results.
                return "https://yandex.com/images/search?rpt=imageview&url=" + enc;
            default:
                // Brave / StartPage / Mojeek / Baidu / custom engine: no
                // verified reverse-image-search surface — fall back to Yandex.
                return "https://yandex.com/images/search?rpt=imageview&url=" + enc;
        }
    }

    /** Counts bytes in a stream without buffering it. */
    private static long countBytes(InputStream in) throws IOException {
        long total = 0;
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) total += n;
        return total;
    }

    /** Writes a single-file multipart body (field {@code fieldName}). */
    private static void writeMultipart(ByteArrayOutputStream out, String boundary,
                                       String fieldName, String filename, String contentType,
                                       byte[] fileBytes) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void showImageSearchError(Activity activity) {
        activity.runOnUiThread(() -> {
            if (mImageSearchWorking != null) {
                mImageSearchWorking.dismiss();
                mImageSearchWorking = null;
            }
            View anchor = mActivity != null ? mActivity.getSnackAnchorView() : getView();
            if (anchor != null) {
                makeSnackbar(anchor, R.string.image_search_failed, isVoiceSearchIncognito()).show();
            }
        });
    }

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
