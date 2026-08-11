package com.solarized.firedown.settings;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Extensions sub-screen (Item 5): lists every installed WebExtension (the
 * built-ins plus any user-installed .xpi), lets the user install a .xpi from
 * the system file picker, toggle each extension on/off, and remove
 * user-installed ones.
 *
 * <p>CRITICAL — no PreferenceScreen mutation from async callbacks. The
 * PreferenceGroup adapter NPEs ("Null reference used for synchronization")
 * when rows are added/removed off the layout pass or after detach. So the
 * extension list is fetched ONCE, cached, and the screen is (re)built only
 * from {@link #onResume()} on the main thread. All GeckoResult callbacks
 * merely flip a "dirty" flag and call {@link #reloadExtensions()}, which
 * itself only rebuilds when the fragment is attached and has a view.</p>
 */
@AndroidEntryPoint
public class ExtensionsFragment extends BasePreferenceFragment {

    private static final String TAG = ExtensionsFragment.class.getName();

    private static final long MAX_XPI_BYTES = 50L * 1024 * 1024;

    private static final String KEY_INSTALL = "extensions.install";
    private static final String KEY_CATEGORY_BUILTIN = "extensions.category.builtin";
    private static final String KEY_CATEGORY_USER = "extensions.category.user";
    private static final String PREFIX_EXT = "extensions.ext.";

    /** Cached extension list — the ONLY source the preference rows are built from. */
    private final List<WebExtension> mExtensions = new ArrayList<>();

    private final ActivityResultLauncher<String[]> mXpiPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            installXpi(uri);
                        }
                    });

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.settings_extensions, rootKey);
        tintIcons();

        Preference install = findPreference(KEY_INSTALL);
        if (install != null) {
            install.setOnPreferenceClickListener(p -> {
                mXpiPicker.launch(new String[]{"application/x-xpinstall", "application/zip", "*/*"});
                return true;
            });
        }

        // NOTE: no fetchExtensions() here — onCreatePreferences can run before
        // the fragment is attached; the first fetch happens in onResume.
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-fetch on every return to the screen so toggles/installs elsewhere
        // are reflected. Rebuild happens only if attached.
        fetchExtensions();
    }

    /** Fetches the extension list into the cache, then rebuilds rows on the
     *  main thread — but ONLY when the fragment is attached and has a view.
     *  Never touches the PreferenceScreen from the GeckoResult thread. */
    private void fetchExtensions() {
        mGeckoRuntimeHelper.getWebExtensionController().list().accept(extensions -> {
            mExtensions.clear();
            if (extensions != null) mExtensions.addAll(extensions);
            rebuildIfAttached();
        }, e -> {
            Log.w(TAG, "list() failed", e);
            // Leave the cached list as-is; don't NPE the screen from a callback.
        });
    }

    /** Rebuilds the preference rows from the cache — must run on the main
     *  thread with the fragment attached. */
    private void rebuildIfAttached() {
        if (!isAdded() || getView() == null) return;
        getActivity().runOnUiThread(() -> {
            if (!isAdded() || getView() == null) return;
            rebuildRows(getPreferenceScreen());
        });
    }

    private void rebuildRows(PreferenceScreen screen) {
        // Bulletproof: a failure here must NEVER crash the app (it did: the
        // PreferenceGroup "monitor-enter on null" NPE propagated through
        // GeckoResult's error dispatch). Log and bail.
        try {
            rebuildRowsInternal(screen);
        } catch (Throwable t) {
            Log.e(TAG, "rebuildRows failed", t);
        }
    }

    private void rebuildRowsInternal(PreferenceScreen screen) {
        if (screen == null) return;

        // The two categories are static in the XML; remove any dynamic rows
        // (key prefix PREFIX_EXT) from them, leaving the categories intact.
        PreferenceCategory builtins = (PreferenceCategory) screen.findPreference(KEY_CATEGORY_BUILTIN);
        PreferenceCategory user = (PreferenceCategory) screen.findPreference(KEY_CATEGORY_USER);

        if (builtins != null) {
            for (int i = builtins.getPreferenceCount() - 1; i >= 0; i--) {
                Preference p = builtins.getPreference(i);
                if (p.getKey() != null && p.getKey().startsWith(PREFIX_EXT)) {
                    builtins.removePreference(p);
                }
            }
        }
        if (user != null) {
            for (int i = user.getPreferenceCount() - 1; i >= 0; i--) {
                Preference p = user.getPreference(i);
                if (p.getKey() != null && p.getKey().startsWith(PREFIX_EXT)) {
                    user.removePreference(p);
                }
            }
        }

        if (mExtensions.isEmpty()) {
            if (builtins != null) builtins.setVisible(false);
            if (user != null) user.setVisible(false);
            return;
        }
        if (builtins != null) builtins.setVisible(true);
        if (user != null) user.setVisible(true);

        boolean hasBuiltin = false;
        boolean hasUser = false;
        for (WebExtension ext : mExtensions) {
            if (ext.metaData == null) continue;
            String name = ext.metaData.name;
            String version = ext.metaData.version;
            String description = ext.metaData.description;
            String title = (name != null && !name.isEmpty())
                    ? name + (version != null ? "  " + version : "")
                    : ext.id;

            SwitchPreferenceCompat toggle = new SwitchPreferenceCompat(requireContext());
            toggle.setKey(PREFIX_EXT + ext.id);
            toggle.setTitle(title);
            toggle.setSummary(description != null ? description : "");
            toggle.setIcon(R.drawable.ic_extensions_24);
            toggle.setChecked(ext.metaData.enabled);
            toggle.setOnPreferenceChangeListener((pref, newValue) -> {
                boolean enable = Boolean.TRUE.equals(newValue);
                setEnabled(ext, enable);
                return false;
            });

            if (ext.isBuiltIn) {
                if (builtins != null) {
                    builtins.addPreference(toggle);
                    hasBuiltin = true;
                }
            } else {
                toggle.setSummary((description != null ? description + "\n" : "")
                        + getString(R.string.extensions_remove_hint));
                toggle.setOnPreferenceClickListener(p -> {
                    confirmRemove(ext);
                    return true;
                });
                if (user != null) {
                    user.addPreference(toggle);
                    hasUser = true;
                }
            }
        }

        if (builtins != null) builtins.setVisible(hasBuiltin);
        if (user != null) user.setVisible(hasUser);

        tintIcons();
    }

    private void setEnabled(WebExtension ext, boolean enable) {
        WebExtensionController c = mGeckoRuntimeHelper.getWebExtensionController();
        GeckoResult<WebExtension> result = enable
                ? c.enable(ext, WebExtensionController.EnableSource.USER)
                : c.disable(ext, WebExtensionController.EnableSource.USER);
        result.accept(updated -> {
            // Update the cache + rebuild on the main thread.
            fetchExtensions();
        }, e -> {
            Log.w(TAG, (enable ? "enable" : "disable") + " failed", e);
            runOnUiThreadIfAdded(() -> showSnackbar(R.string.extensions_toggle_failed));
        });
    }

    private void confirmRemove(WebExtension ext) {
        String name = ext.metaData != null && ext.metaData.name != null
                ? ext.metaData.name : ext.id;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.extensions_remove_title)
                .setMessage(getString(R.string.extensions_remove_message, name))
                .setPositiveButton(R.string.extensions_remove_confirm, (d, w) -> {
                    mGeckoRuntimeHelper.getWebExtensionController().uninstall(ext).accept(
                            unused -> runOnUiThreadIfAdded(() -> {
                                showSnackbar(getString(R.string.extensions_removed, name));
                                fetchExtensions();
                            }),
                            e -> {
                                Log.w(TAG, "uninstall failed", e);
                                runOnUiThreadIfAdded(() ->
                                        showSnackbar(R.string.extensions_remove_failed));
                            });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void installXpi(Uri uri) {
        File dir = new File(requireContext().getCacheDir(), "extensions");
        if (!dir.exists() && !dir.mkdirs()) {
            showSnackbar(R.string.extensions_install_failed);
            return;
        }
        File target = new File(dir, "extension-" + System.nanoTime() + ".xpi");

        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Cannot open picked file");
            byte[] buffer = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_XPI_BYTES) {
                    throw new IOException("xpi too large");
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            Log.w(TAG, "copying xpi failed", e);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            showSnackbar(R.string.extensions_install_failed);
            return;
        }

        String fileUri = "file://" + target.getAbsolutePath();
        mGeckoRuntimeHelper.getWebExtensionController().install(fileUri).accept(
                installed -> {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    String name = installed.metaData != null && installed.metaData.name != null
                            ? installed.metaData.name : installed.id;
                    runOnUiThreadIfAdded(() -> {
                        showSnackbar(getString(R.string.extensions_installed, name));
                        fetchExtensions();
                    });
                },
                e -> {
                    Log.w(TAG, "install failed", e);
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    runOnUiThreadIfAdded(() ->
                            showSnackbar(R.string.extensions_install_failed));
                });
    }

    private void runOnUiThreadIfAdded(Runnable action) {
        if (!isAdded() || getView() == null) return;
        getActivity().runOnUiThread(() -> {
            if (isAdded() && getView() != null) {
                action.run();
            }
        });
    }

    private void showSnackbar(int resId) {
        showSnackbar(getString(resId));
    }

    private void showSnackbar(String message) {
        if (getView() == null) return;
        Snackbar.make(getView(), message, Snackbar.LENGTH_SHORT).show();
    }
}
