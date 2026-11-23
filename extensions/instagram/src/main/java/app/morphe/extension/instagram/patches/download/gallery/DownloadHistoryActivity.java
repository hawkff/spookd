package app.morphe.extension.instagram.patches.download.gallery;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.patches.dm.InboxLock;
import app.morphe.extension.instagram.patches.dm.vault.PlainMedia;
import app.morphe.extension.instagram.patches.dm.vault.VaultViewer;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Recent downloads, newest first, built in code inside the app's modal activity. The UI runs on the
 * main thread; the history is read and cleared on one background thread.
 */
public final class DownloadHistoryActivity {
    private static final Executor JOBS = Executors.newSingleThreadExecutor();
    private static final long REFRESH_MS = 2000;

    private DownloadHistoryActivity() {
    }

    /**
     * Main thread, from the launch dispatch. Fails closed like the private gallery: below Android 11
     * or without a secure screen lock only the screen-lock notice shows; otherwise one credential
     * prompt, and cancelling it closes the screen.
     */
    public static void open(Activity activity) {
        try {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            Screen screen = new Screen(activity);
            if (!Gallery.canOpen(activity)) {
                screen.setup(false);
                return;
            }
            InboxLock.confirmViewerWithCredential(activity, () -> {
                try {
                    if (!activity.isFinishing() && !activity.isDestroyed()) screen.setup(true);
                } catch (Exception e) {
                    Logger.printException(() -> "DownloadHistoryActivity setup failed", e);
                    activity.finish();
                }
            });
        } catch (Exception e) {
            Logger.printException(() -> "DownloadHistoryActivity.open failed", e);
            activity.finish();
        }
    }

    private static final class Screen {
        private final Activity activity;
        private final Handler ui = new Handler(Looper.getMainLooper());
        private final Runnable refresh = this::reload;
        private final Adapter adapter = new Adapter();
        private List<DownloadHistory.Entry> entries = new ArrayList<>();
        private TextView empty;
        private ListView list;
        private int loads;
        private boolean closed;

        Screen(Activity activity) {
            this.activity = activity;
        }

        /** {@code unlocked}: the credential was confirmed, so the history shows; otherwise only the screen-lock notice. */
        void setup(boolean unlocked) {
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
            InstagramPreferenceStyle.applySystemBarStyle(activity);

            LinearLayout toolbar = new LinearLayout(activity);
            toolbar.setBaselineAligned(false);
            toolbar.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
            ImageView back = new ImageView(activity);
            UI.setThemedIcon(back, UI.DRAWABLE_ARROW_BACK);
            back.setOnClickListener(v -> activity.finish());
            back.setContentDescription(str("spookd_back"));
            TextView title = new TextView(activity);
            title.setText(str("spookd_download_history"));
            title.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            InstagramPreferenceStyle.applyToolbarLayout(activity, toolbar, back, title, false);
            TextView clear = new TextView(activity);
            clear.setText(str("spookd_download_history_clear"));
            clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            clear.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            clear.setPadding(dp(12), dp(8), dp(4), dp(8));
            clear.setOnClickListener(v -> confirmClear());
            clear.setVisibility(unlocked ? View.VISIBLE : View.GONE);
            toolbar.addView(back);
            toolbar.addView(title);
            toolbar.addView(clear);
            root.addView(toolbar);

            empty = new TextView(activity);
            empty.setText(str(unlocked ? "spookd_download_history_empty" : "spookd_vault_needs_screen_lock"));
            empty.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(24), dp(24), dp(24));
            empty.setVisibility(unlocked ? View.GONE : View.VISIBLE);
            root.addView(empty, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            list = new ListView(activity);
            list.setAdapter(adapter);
            list.setOnItemClickListener((parent, view, position, id) -> openSaved(entries.get(position)));
            list.setVisibility(unlocked ? View.VISIBLE : View.GONE);
            root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

            root.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            });
            if (activity instanceof androidx.activity.ComponentActivity) {
                try {
                    ((androidx.activity.ComponentActivity) activity).getOnBackPressedDispatcher().addCallback(
                            (androidx.lifecycle.LifecycleOwner) activity,
                            new androidx.activity.OnBackPressedCallback(true) {
                                @Override
                                public void handleOnBackPressed() {
                                    activity.finish();
                                }
                            });
                } catch (Throwable ignored) {
                }
            }
            activity.setContentView(root);
            watchLifecycle();
            if (unlocked) reload();
        }

        /** Leaving the app or the screen ends it, as the private gallery does; a rotation keeps it. */
        private void watchLifecycle() {
            Application app = activity.getApplication();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}

                @Override
                public void onActivityStopped(Activity a) {
                    if (a != activity || a.isChangingConfigurations()) return;
                    close();
                    a.finish();
                }

                @Override
                public void onActivityDestroyed(Activity a) {
                    if (a != activity) return;
                    app.unregisterActivityLifecycleCallbacks(this);
                    close();
                }
            });
        }

        private void close() {
            VaultViewer.close(activity);
            closed = true;
            ui.removeCallbacksAndMessages(null);
        }

        /** Main thread; rows still downloading refresh until they end, and Retry goes when its link expires. */
        private void reload() {
            int generation = ++loads;
            JOBS.execute(() -> {
                List<DownloadHistory.Entry> loaded;
                try {
                    loaded = DownloadHistory.get().list();
                } catch (Exception e) {
                    Logger.printException(() -> "Download history: cannot list", e);
                    loaded = new ArrayList<>();
                }
                List<DownloadHistory.Entry> result = loaded;
                ui.post(() -> {
                    if (closed || generation != loads) return;
                    entries = result;
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                    list.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
                    ui.removeCallbacks(refresh);
                    long now = System.currentTimeMillis();
                    long next = Long.MAX_VALUE;
                    for (DownloadHistory.Entry entry : result) {
                        if (DownloadHistory.DOWNLOADING.equals(entry.status)) next = Math.min(next, now + REFRESH_MS);
                        long expiresAt = DownloadHistory.retryExpiresAt(entry.id);
                        if (expiresAt > now) next = Math.min(next, expiresAt);
                    }
                    if (next != Long.MAX_VALUE) ui.postDelayed(refresh, next - now);
                });
            });
        }

        private void confirmClear() {
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setMessage(str("spookd_download_history_clear_confirm"))
                    .setPositiveButton(str("spookd_download_history_clear"), (d, which) -> JOBS.execute(() -> {
                        try {
                            DownloadHistory.get().clear();
                        } catch (Exception e) {
                            Logger.printException(() -> "Download history: cannot clear", e);
                        }
                        ui.post(() -> {
                            if (!closed) reload();
                        });
                    }))
                    .setNegativeButton(str("spookd_cancel"), null)
                    .show();
        }

        /** Opens a saved gallery item in the gallery's viewer, or a download folder file in another app. */
        private void openSaved(DownloadHistory.Entry entry) {
            if (!DownloadHistory.SAVED.equals(entry.status)) return;
            try {
                if (entry.galleryFile != null) {
                    VaultViewer.showFor(activity,
                            () -> new VaultViewer.Opened(new PlainMedia(entry.galleryFile), entry.kind));
                } else if (entry.document != null) {
                    String name = entry.fileName == null ? "" : entry.fileName;
                    activity.startActivity(new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.parse(entry.document), GalleryActions.mimeType(name, entry.kind))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } else {
                    Utils.showToastShort(str("spookd_download_history_gone"));
                }
            } catch (Exception e) {
                Logger.printException(() -> "Download history: cannot open " + entry.id, e);
                Utils.showToastShort(str("spookd_gallery_action_failed"));
            }
        }

        private void retry(DownloadHistory.Entry entry) {
            if (!Utils.isNetworkConnected()) {
                Utils.showToastShort(str("spookd_no_internet"));
                return;
            }
            try {
                if (!DownloadHistory.retry(activity, entry.id, System.currentTimeMillis())) {
                    Utils.showToastShort(str("spookd_download_history_expired"));
                }
            } catch (Exception e) {
                Logger.printException(() -> "Download history: cannot retry " + entry.id, e);
                Utils.showToastShort(str("spookd_gallery_action_failed"));
            }
            adapter.notifyDataSetChanged();
            // The row turns downloading once the download thread starts it.
            ui.removeCallbacks(refresh);
            ui.postDelayed(refresh, REFRESH_MS / 4);
        }

        private static String status(DownloadHistory.Entry entry) {
            if (DownloadHistory.DOWNLOADING.equals(entry.status)) return str("spookd_download_history_downloading");
            if (DownloadHistory.SAVED.equals(entry.status)) {
                return str(entry.inGallery ? "spookd_download_history_saved_gallery" : "spookd_download_history_saved_folder");
            }
            return entry.error == null ? str("spookd_download_history_interrupted")
                    : str("spookd_download_history_failed", entry.error);
        }

        private int dp(int value) {
            return (int) (value * activity.getResources().getDisplayMetrics().density);
        }

        private final class Adapter extends BaseAdapter {
            @Override
            public int getCount() {
                return entries.size();
            }

            @Override
            public Object getItem(int position) {
                return entries.get(position);
            }

            @Override
            public long getItemId(int position) {
                return entries.get(position).id;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                RowView row = convertView instanceof RowView ? (RowView) convertView : new RowView();
                row.bind(entries.get(position));
                return row;
            }
        }

        /** Owner and details, and Retry while a failed download can run again. */
        private final class RowView extends LinearLayout {
            private final TextView name = new TextView(activity);
            private final TextView details = new TextView(activity);
            private final TextView retryButton = new TextView(activity);

            RowView() {
                super(activity);
                setOrientation(HORIZONTAL);
                setGravity(Gravity.CENTER_VERTICAL);
                setPadding(dp(16), dp(10), dp(8), dp(10));
                LinearLayout texts = new LinearLayout(activity);
                texts.setOrientation(VERTICAL);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                name.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                details.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                details.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
                details.setMaxLines(3);
                details.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(name);
                texts.addView(details);
                addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                retryButton.setText(str("spookd_download_history_retry"));
                retryButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                retryButton.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                retryButton.setPadding(dp(12), dp(8), dp(8), dp(8));
                // A clickable child that takes focus would stop the row's own taps.
                retryButton.setFocusable(false);
                addView(retryButton);
            }

            void bind(DownloadHistory.Entry entry) {
                name.setText(!TextUtils.isEmpty(entry.ownerUsername) ? entry.ownerUsername
                        : !TextUtils.isEmpty(entry.fileName) ? entry.fileName : str("spookd_unknown"));
                String kind = str(GalleryItem.VIDEO.equals(entry.kind) ? "spookd_media_video"
                        : GalleryItem.AUDIO.equals(entry.kind) ? "spookd_gallery_audio" : "spookd_media_photo");
                String time = DateUtils.formatDateTime(activity, entry.startedAt,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
                details.setText(kind + " · " + time + " · " + status(entry));
                boolean canRetry = DownloadHistory.FAILED.equals(entry.status) && DownloadHistory.canRetry(entry.id, System.currentTimeMillis());
                retryButton.setVisibility(canRetry ? View.VISIBLE : View.GONE);
                retryButton.setOnClickListener(v -> retry(entry));
            }
        }
    }
}
