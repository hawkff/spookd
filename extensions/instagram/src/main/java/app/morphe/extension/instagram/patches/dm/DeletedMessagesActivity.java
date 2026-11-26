/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.SparseBooleanArray;
import android.util.StateSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Build;
import android.window.OnBackInvokedDispatcher;
import androidx.annotation.RequiresApi;

import com.instagram.common.session.UserSession;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.db.SpookdMessageDb;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.patches.dm.vault.MediaVault;
import app.morphe.extension.instagram.patches.dm.vault.VaultViewer;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.instagram.settings.ActivityHook;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.settings.preference.widgets.SpookdType;
import app.morphe.extension.instagram.utils.MediaUrls;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SelectionCheck;

public final class DeletedMessagesActivity {

    private static final Map<Activity, Controller> sControllers = new WeakHashMap<>();
    // Serial, so a delete queued by a screen that was closed runs before a reopened screen loads.
    private static final Executor sDbJobs = Executors.newSingleThreadExecutor();
    private static final long PICTURE_REQUEST_GAP_MS = 1500;
    private static final long PROFILE_FETCH_TIMEOUT_SECONDS = 20;
    private static final ExecutorService sPictureRefresh = Executors.newSingleThreadExecutor();
    // Cached, so a profile request that never returns blocks only its own thread.
    private static final ExecutorService sProfileFetch = Executors.newCachedThreadPool();
    private static final int PAGE_SIZE = SpookdMessageDb.DELETED_PAGE_SIZE;
    // Rows within this distance of the end load the next page.
    private static final int LOAD_MORE_AHEAD = 20;

    private DeletedMessagesActivity() {}

    static boolean areAllSelected(int checkedCount, int itemCount) {
        return itemCount > 0 && checkedCount == itemCount;
    }

    static void refreshVisibleSelectionRows(ListView list, boolean selectionMode) {
        int firstPosition = list.getFirstVisiblePosition();
        for (int childIndex = 0; childIndex < list.getChildCount(); childIndex++) {
            int position = firstPosition + childIndex;
            if (position >= list.getCount()) break;
            View row = list.getChildAt(childIndex);
            SelectionCheck selectionView = row.findViewWithTag("selection");
            if (selectionView == null) continue;
            boolean checked = list.isItemChecked(position);
            selectionView.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            selectionView.setChecked(checked);
            row.setPadding(selectionMode ? Dim.dp8 : Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);
            row.setActivated(checked);
        }
    }

    private static String guessExtension(String url, String type) {
        String path = Uri.parse(url).getPath();
        Matcher m = Pattern
                .compile("(?i)\\.(jpg|jpeg|webp|heic|png|mp4|mov|m4a|aac|mp3|ogg|gif)$")
                .matcher(path != null ? path : "");
        if (m.find()) return "." + m.group(1).toLowerCase(Locale.ROOT);
        if ("voice_media".equals(type) || "audio".equals(type)) return ".m4a";
        if ("video".equals(type)) return ".mp4";
        if ("animated_media".equals(type)) return ".gif";
        return ".jpg";
    }

    private static boolean isVideo(String url, String type) {
        if ("video".equals(type) || "clip".equals(type) || "xma_clip".equals(type)) return true;
        return MediaUrls.isVideo(url);
    }

    private static boolean isExpiredMediaUrl(String url) {
        try {
            int i = url.indexOf("oe=");
            if (i < 1 || (url.charAt(i - 1) != '?' && url.charAt(i - 1) != '&')) return false;
            int end = i + 3;
            while (end < url.length() && Character.digit(url.charAt(end), 16) >= 0) end++;
            if (end == i + 3) return false;
            return Long.parseLong(url.substring(i + 3, end), 16) * 1000L < System.currentTimeMillis();
        } catch (Exception e) {
            return false;
        }
    }

    private static String mediaLabel(String type, String url) {
        if (type == null) return "[" + str("spookd_media_unknown") + "]";
        // Instagram uses these item types for both photos and videos, including saved rows.
        if (("media".equals(type) || "raven_media".equals(type) || "visual_media".equals(type))
                && isVideo(url, type)) {
            type = "video";
        }
        String label;
        switch (type) {
            case "media":
            case "image":          label = str("spookd_media_photo"); break;
            case "video":          label = str("spookd_media_video"); break;
            case "voice_media":
            case "audio":          label = str("spookd_media_voice"); break;
            case "animated_media": label = str("spookd_media_gif"); break;
            case "reel_share":     label = str("spookd_media_reel"); break;
            case "story_share":    label = str("spookd_media_story"); break;
            case "media_share":    label = str("spookd_media_post"); break;
            case "clip":
            case "xma_clip":       label = str("spookd_media_reel"); break;
            default:               return "[" + type + "]";
        }
        return "[" + label + "]";
    }

    public static void setupOnActivity(Activity activity) {
        Controller controller = new Controller(activity);
        synchronized (sControllers) {
            sControllers.put(activity, controller);
        }
        controller.setup();
        if (activity.getApplication() != null) {
            activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) { if (a == activity) VaultViewer.close(a); }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override
                public void onActivityDestroyed(Activity a) {
                    if (a == activity) {
                        VaultViewer.close(a);
                        controller.close();
                        a.getApplication().unregisterActivityLifecycleCallbacks(this);
                        synchronized (sControllers) {
                            sControllers.remove(activity);
                        }
                    }
                }
            });
        }
    }

    public static boolean handleBackPressed(Activity activity) {
        Controller controller;
        synchronized (sControllers) {
            controller = sControllers.get(activity);
        }
        if (controller != null && controller.isSelecting()) {
            controller.cancelSelection();
            return true;
        }
        return false;
    }

    /**
     * Picture refresh thread: the cached avatar of each user when it has not expired, else one
     * profile request per user, paced and time-limited; a request that times out moves on to the
     * next user. Stops when interrupted. Returns how many pictures were stored.
     */
    private static int refreshPictureUrls(Context context, UserSession session, String viewerId,
                                          Set<String> userIds) {
        int updated = 0;
        boolean requested = false;
        SpookdMessageDb db = SpookdMessageDb.getInstance(context);
        for (String userId : userIds) {
            if (Thread.currentThread().isInterrupted()) break;
            Future<UserData> fetch = null;
            try {
                UserData user = SavedMessagesHook.cachedUser(session, userId, viewerId);
                String url = user == null ? null : user.getAvatarUrl();
                if (url == null) {
                    // A pause between requests, so a long list is not fetched in a burst.
                    if (requested) Thread.sleep(PICTURE_REQUEST_GAP_MS);
                    if (Thread.currentThread().isInterrupted()) break;
                    requested = true;
                    fetch = sProfileFetch.submit(() -> UserData.fetchProfile(session, userId));
                    user = fetch.get(PROFILE_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    url = user == null ? null : user.getAvatarUrl();
                }
                if (url != null && db.putPicture(userId, user.getUsername(), url)) updated++;
            } catch (TimeoutException e) {
                // One profile that does not answer leaves the others to refresh.
                fetch.cancel(true);
            } catch (InterruptedException e) {
                if (fetch != null) fetch.cancel(true);
                break;
            } catch (Exception e) {
                Logger.printException(() -> "refreshPictureUrls: ", e);
            }
        }
        return updated;
    }

    private static class Controller {
        private final Activity activity;
        private List<String[]> messages;
        private Map<String, String> storedModes = Collections.emptyMap();
        private Map<String, String> pictures = Collections.emptyMap();
        private final AvatarLoader avatars = new AvatarLoader();
        private View moreAction;
        private boolean refreshingPictures;
        private Future<?> refreshJob;
        private boolean hasMore;
        // The user chose All: every row of the log, loaded or not, is meant.
        private boolean wholeLogSelected;
        private ListView messageList;
        private MessageAdapter adapter;
        private SimpleDateFormat timestampFormatter;
        private View backAction;
        private TextView toolbarTitle;
        private View selectAllAction;
        private SelectionCheck selectAllCheck;
        private TextView selectAllLabel;
        private TextView cancelSelectionAction;
        private Button deleteAction;
        private boolean selectionMode;
        private boolean selectionToolbarVisible;
        private final DeletedLogLoads loads = new DeletedLogLoads();
        private View pendingEmpty;

        Controller(Activity activity) {
            this.activity = activity;
        }

        void setup() {
            activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER);
            messages = new ArrayList<>();
            Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
            String skeleton = DateFormat.is24HourFormat(activity) ? "yMMMdHm" : "yMMMdhm";
            timestampFormatter = new SimpleDateFormat(
                    DateFormat.getBestDateTimePattern(locale, skeleton), locale);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
            InstagramPreferenceStyle.applySystemBarStyle(activity);

            LinearLayout toolbar = new LinearLayout(activity);
            toolbar.setBaselineAligned(false);
            toolbar.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());

            ImageView back = new ImageView(activity);
            UI.setThemedIcon(back, UI.DRAWABLE_ARROW_BACK);
            back.setOnClickListener(v -> handleBack());
            backAction = back;

            toolbarTitle = new TextView(activity);
            toolbarTitle.setText(str("spookd_all_deleted_messages"));
            toolbarTitle.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            InstagramPreferenceStyle.applyToolbarLayout(
                    activity, toolbar, back, toolbarTitle);

            LinearLayout selectAll = new LinearLayout(activity);
            selectAll.setOrientation(LinearLayout.VERTICAL);
            selectAll.setGravity(Gravity.CENTER);
            selectAll.setMinimumWidth(Dim.dp48);
            selectAll.setMinimumHeight(Dim.dp48);
            selectAll.setVisibility(View.GONE);
            selectAll.setContentDescription(str("spookd_all"));

            selectAllCheck = new SelectionCheck(
                    InstagramPreferenceStyle.dialogContext(activity), selectionControlDrawable());
            selectAll.addView(selectAllCheck, new LinearLayout.LayoutParams(
                    Dim.dp48, Dim.dp48));

            selectAllLabel = new TextView(activity);
            selectAllLabel.setText(str("spookd_all"));
            SpookdType.apply(selectAllLabel, SpookdType.CAPTION);
            selectAllLabel.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            selectAllLabel.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = -InstagramPreferenceStyle.dp(activity, 10);
            selectAll.addView(selectAllLabel, labelParams);
            selectAll.setOnClickListener(v -> toggleAll());
            bindSelectionAccessibility(selectAll, selectAllCheck);
            selectAllAction = selectAll;

            TextView cancelSelection = new TextView(activity);
            cancelSelection.setText(str("spookd_cancel"));
            SpookdType.apply(cancelSelection, SpookdType.BODY);
            cancelSelection.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            cancelSelection.setGravity(Gravity.CENTER);
            cancelSelection.setMinWidth(Dim.dp48);
            cancelSelection.setPadding(Dim.dp8, 0, Dim.dp8, 0);
            cancelSelection.setVisibility(View.GONE);
            cancelSelection.setOnClickListener(v -> cancelSelection());
            cancelSelectionAction = cancelSelection;

            ImageView more = new ImageView(activity);
            UI.setThemedIcon(more, "instagram_more_vertical_outline_24");
            more.setScaleType(ImageView.ScaleType.CENTER);
            more.setContentDescription(str("spookd_more_options"));
            more.setOnClickListener(v -> showMoreOptions());
            moreAction = more;

            toolbar.addView(back);
            toolbar.addView(selectAll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            toolbar.addView(toolbarTitle);
            toolbar.addView(cancelSelection, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Dim.dp48));
            toolbar.addView(more, new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48));
            root.addView(toolbar);

            FrameLayout content = new FrameLayout(activity);
            TextView empty = new TextView(activity);
            empty.setText(str("spookd_no_deleted_messages"));
            SpookdType.apply(empty, SpookdType.BODY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Dim.dp8 * 2, Dim.dp8 * 4, Dim.dp8 * 2, Dim.dp8 * 4);
            empty.setTextColor(InstagramPreferenceStyle.secondaryTextColor());

            messageList = new ListView(activity);
            adapter = new MessageAdapter();
            messageList.setAdapter(adapter);
            messageList.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
            messageList.setDivider(new ColorDrawable(UI.getThemedColour("igds_color_separator")));
            messageList.setDividerHeight(1);
            messageList.setChoiceMode(ListView.CHOICE_MODE_NONE);
            messageList.setOnItemClickListener((parent, view, position, id) -> {
                if (selectionMode) {
                    updateSelectionUi();
                    return;
                }
                String[] message = messages.get(position);
                String messageId = message[0];
                String body = message[3];
                String type = message[4];
                if (SpookdMessageDb.REMOVED_REACTION.equals(type)) return;
                if (storedModes.containsKey(messageId)) {
                    VaultViewer.open(activity, messageId);
                } else if (body != null && body.startsWith("http")) {
                    showMediaOptions(messageId, body, type);
                } else if (type != null && !"text".equals(type)) {
                    Toast.makeText(activity, str("spookd_media_not_available"),
                            Toast.LENGTH_SHORT).show();
                }
            });
            messageList.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView view, int scrollState) {}
                @Override
                public void onScroll(AbsListView view, int first, int visible, int total) {
                    if (total > 0 && first + visible >= total - LOAD_MORE_AHEAD) loadMore();
                }
            });
            messageList.setOnItemLongClickListener((parent, view, position, id) -> {
                if (!selectionMode) {
                    selectionMode = true;
                    messageList.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
                }
                messageList.setItemChecked(position, true);
                updateSelectionUi();
                return true;
            });

            FrameLayout.LayoutParams match = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            content.addView(empty, match);
            content.addView(messageList, match);
            root.addView(content, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

            deleteAction = new Button(activity);
            deleteAction.setText(str("spookd_delete"));
            SpookdType.apply(deleteAction, SpookdType.LABEL);
            deleteAction.setAllCaps(false);
            deleteAction.setTextColor(UI.getThemedColour("igds_color_error_or_destructive"));
            deleteAction.setBackgroundTintList(null);
            GradientDrawable deleteBackground = new GradientDrawable();
            deleteBackground.setColor(InstagramPreferenceStyle.pressedBackgroundColor());
            deleteBackground.setCornerRadius(Dim.dp24);
            deleteAction.setBackground(new RippleDrawable(ColorStateList.valueOf(
                    InstagramPreferenceStyle.secondaryTextColor() & 0x00ffffff | 0x24000000),
                    deleteBackground, null));
            deleteAction.setStateListAnimator(null);
            deleteAction.setElevation(0);
            deleteAction.setMinHeight(Dim.dp48);
            deleteAction.setMinimumHeight(Dim.dp48);
            deleteAction.setVisibility(View.GONE);
            deleteAction.setOnClickListener(v -> confirmDeleteSelected());
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            deleteParams.setMargins(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp12);
            root.addView(deleteAction, deleteParams);
            // After every control exists, since a load updates them.
            loadMessages(null, empty);

            root.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            });

            root.setFocusableInTouchMode(true);
            root.requestFocus();
            root.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    if (isSelecting()) {
                        cancelSelection();
                        return true;
                    }
                }
                return false;
            });

            boolean backRegistered = false;
            if (activity instanceof androidx.activity.ComponentActivity) {
                try {
                    ((androidx.activity.ComponentActivity) activity).getOnBackPressedDispatcher().addCallback(
                            (androidx.lifecycle.LifecycleOwner) activity,
                            new androidx.activity.OnBackPressedCallback(true) {
                                @Override
                                public void handleOnBackPressed() {
                                    handleBack();
                                }
                            }
                    );
                    backRegistered = true;
                } catch (Throwable ignored) {
                }
            }

            if (!backRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Api33BackHelper.register(activity, this::handleBack);
            }

            activity.setContentView(root);
        }

        /** Deletes and reloads off the main thread; the empty view is attached once rows are known. */
        private void loadMessages(List<String> deleteIds, View empty) {
            // One job at a time; parallel jobs could apply an older snapshot last, so a later one waits.
            if (!loads.startReload(deleteIds)) {
                if (empty != null) pendingEmpty = empty;
                updateSelectionUi();
                return;
            }
            // Delete stays disabled until the reload is in.
            updateSelectionUi();
            // Reloads as many rows as are shown, page by page, so the list keeps its length.
            int wanted = Math.max(PAGE_SIZE, messages.size());
            Context app = activity.getApplicationContext();
            sDbJobs.execute(() -> {
                List<String[]> loaded = null;
                Map<String, String> stored = Collections.emptyMap();
                Map<String, String> loadedPictures = Collections.emptyMap();
                boolean more = false;
                try {
                    SpookdMessageDb db = SpookdMessageDb.getInstance(app);
                    if (deleteIds != null) SavedMessagesHook.deleteSavedMessages(deleteIds);
                    List<String[]> rows = new ArrayList<>();
                    List<String[]> page;
                    do {
                        page = db.getDeletedMessages(PAGE_SIZE,
                                rows.isEmpty() ? null : rows.get(rows.size() - 1));
                        rows.addAll(page);
                    } while (page.size() == PAGE_SIZE && rows.size() < wanted);
                    more = page.size() == PAGE_SIZE;
                    loaded = rows;
                    loadedPictures = db.getPictureUrls();
                    if (Pref.dmMediaVault()) MediaVault.get().start();
                    stored = MediaVault.get().storedMessages();
                } catch (Exception e) {
                    Logger.printException(() -> "loadMessages: ", e);
                }
                List<String[]> result = loaded;
                Map<String, String> storedResult = stored;
                Map<String, String> picturesResult = loadedPictures;
                boolean moreResult = more;
                Utils.runOnMainThread(() -> {
                    List<String> queued = loads.finish();
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        runQueued(queued);
                        return;
                    }
                    if (result != null) {
                        messages = result;
                        storedModes = storedResult;
                        pictures = picturesResult;
                        hasMore = moreResult;
                        adapter.notifyDataSetChanged();
                        if (empty != null) messageList.setEmptyView(empty);
                        cancelSelection();
                    } else {
                        updateSelectionUi();
                    }
                    runQueued(queued);
                });
            });
        }

        /** Appends the next page off the main thread; nothing while another load runs. */
        private void loadMore() {
            if (!hasMore || !loads.startAppend()) return;
            // Delete stays disabled until the page is in.
            updateSelectionUi();
            int offset = messages.size();
            String[] last = offset == 0 ? null : messages.get(offset - 1);
            Context app = activity.getApplicationContext();
            sDbJobs.execute(() -> {
                List<String[]> loaded = null;
                try {
                    loaded = SpookdMessageDb.getInstance(app).getDeletedMessages(PAGE_SIZE, last);
                } catch (Exception e) {
                    Logger.printException(() -> "loadMore: ", e);
                }
                List<String[]> page = loaded;
                Utils.runOnMainThread(() -> {
                    List<String> queued = loads.finish();
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        runQueued(queued);
                        return;
                    }
                    if (page != null && messages.size() == offset) {
                        List<String[]> rows = new ArrayList<>(messages);
                        rows.addAll(page);
                        messages = rows;
                        hasMore = page.size() == PAGE_SIZE;
                        // A whole-log selection covers the rows that just came in.
                        if (wholeLogSelected) {
                            for (int position = offset; position < rows.size(); position++) {
                                messageList.setItemChecked(position, true);
                            }
                        }
                        adapter.notifyDataSetChanged();
                    }
                    updateSelectionUi();
                    runQueued(queued);
                });
            });
        }

        /** Main thread: runs the reload or delete asked for while the last load ran; a closed screen only deletes. */
        private void runQueued(List<String> deletes) {
            if (deletes == null) return;
            if (deletes.isEmpty() && (activity.isFinishing() || activity.isDestroyed())) return;
            View empty = pendingEmpty;
            pendingEmpty = null;
            loadMessages(deletes.isEmpty() ? null : deletes, empty);
        }

        /** The saved label of a message whose media is stored, with its view-once mark; null when none is stored. */
        private String savedLabel(String messageId) {
            if (!storedModes.containsKey(messageId)) return null;
            String mark = MediaVault.markResource(storedModes.get(messageId));
            return (mark != null ? str(mark) + "  ·  " : "") + str("spookd_media_saved");
        }

        boolean isSelecting() {
            return selectionMode;
        }

        void handleBack() {
            if (isSelecting()) {
                cancelSelection();
            } else {
                activity.finish();
            }
        }

        void cancelSelection() {
            selectionMode = false;
            wholeLogSelected = false;
            messageList.clearChoices();
            messageList.setChoiceMode(ListView.CHOICE_MODE_NONE);
            updateSelectionUi();
        }

        /** Selects the whole log, rows not loaded yet included, or clears the selection. */
        private void toggleAll() {
            boolean allSelected = wholeLogSelected
                    || (!hasMore && areAllSelected(messageList.getCheckedItemCount(), messages.size()));
            boolean checked = !allSelected && !messages.isEmpty();
            for (int position = 0; position < messages.size(); position++) {
                messageList.setItemChecked(position, checked);
            }
            wholeLogSelected = checked;
            updateSelectionUi();
        }

        private void updateSelectionUi() {
            if (deleteAction == null) return;
            int checkedCount = messageList.getCheckedItemCount();
            // Unchecking a row ends a whole-log selection.
            if (wholeLogSelected && checkedCount < messages.size()) wholeLogSelected = false;
            boolean selecting = isSelecting();
            boolean hasSelection = checkedCount > 0;
            if (selectionToolbarVisible != selecting) {
                applySelectionToolbarLayout(selecting);
                selectionToolbarVisible = selecting;
            }
            toolbarTitle.setText(!selecting ? str("spookd_all_deleted_messages")
                    : wholeLogSelected && hasMore ? str("spookd_whole_log_selected")
                    : String.format(Locale.getDefault(), str("spookd_selected_count"), checkedCount));
            backAction.setVisibility(selecting ? View.GONE : View.VISIBLE);
            selectAllAction.setVisibility(selecting ? View.VISIBLE : View.GONE);
            // Checked only when the whole log is selected, not just the loaded pages.
            selectAllCheck.setChecked(wholeLogSelected
                    || (!hasMore && areAllSelected(checkedCount, messages.size())));
            cancelSelectionAction.setVisibility(selecting ? View.VISIBLE : View.GONE);
            moreAction.setVisibility(selecting ? View.GONE : View.VISIBLE);
            deleteAction.setVisibility(selecting ? View.VISIBLE : View.GONE);
            deleteAction.setEnabled(hasSelection && !loads.isBusy());
            deleteAction.setAlpha(hasSelection && !loads.isBusy() ? 1f : 0.5f);
            refreshVisibleSelectionRows(messageList, selecting);
        }

        private void applySelectionToolbarLayout(boolean selecting) {
            LinearLayout toolbar = (LinearLayout) toolbarTitle.getParent();
            if (!selecting) {
                toolbar.setMinimumHeight(0);
                InstagramPreferenceStyle.applyToolbarLayout(
                        activity, toolbar, (ImageView) backAction, toolbarTitle);
                return;
            }

            toolbar.setGravity(Gravity.TOP);
            // Keeps the 48dp actions centered where the normal toolbar centers its row.
            toolbar.setPadding(Dim.dp8, Dim.dp4, Dim.dp16, 0);
            toolbar.setMinimumHeight(InstagramPreferenceStyle.dp(activity, SpookdType.TOOLBAR_HEIGHT));
            toolbar.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
            toolbar.requestLayout();
            LinearLayout.LayoutParams titleParams =
                    (LinearLayout.LayoutParams) toolbarTitle.getLayoutParams();
            titleParams.height = Dim.dp48;
            titleParams.gravity = Gravity.TOP;
            titleParams.leftMargin = InstagramPreferenceStyle.dp(activity, 10);
            toolbarTitle.setGravity(Gravity.CENTER_VERTICAL);
            toolbarTitle.setLayoutParams(titleParams);
        }

        private void confirmDeleteSelected() {
            if (wholeLogSelected) {
                confirmDeleteWholeLog();
                return;
            }
            SparseBooleanArray checked = messageList.getCheckedItemPositions();
            ArrayList<String> selectedIds = new ArrayList<>();
            for (int position = 0; position < messages.size(); position++) {
                if (checked.get(position)) selectedIds.add(messages.get(position)[0]);
            }
            if (selectedIds.isEmpty()) return;

            String prompt = String.format(Locale.getDefault(),
                    str("spookd_delete_selected_confirm"), selectedIds.size());
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setMessage(prompt)
                    .setPositiveButton(str("spookd_delete"), (dialog, which) -> {
                        loadMessages(selectedIds, null);
                        updateSelectionUi();
                    })
                    .setNegativeButton(str("spookd_cancel"), null)
                    .show();
        }

        /**
         * Reads the ids of every unsent message and removed reaction off the main thread, then asks to
         * delete exactly those; stored view-once media of live messages is never part of it.
         */
        private void confirmDeleteWholeLog() {
            if (loads.isBusy()) return;
            Context app = activity.getApplicationContext();
            sDbJobs.execute(() -> {
                List<String> ids = null;
                try {
                    ids = SpookdMessageDb.getInstance(app).getDeletedLogIds();
                } catch (Exception e) {
                    Logger.printException(() -> "confirmDeleteWholeLog: ", e);
                }
                List<String> result = ids;
                Utils.runOnMainThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (!wholeLogSelected) return;
                    if (result == null || result.isEmpty()) {
                        Utils.showToastShort(str(result == null
                                ? "spookd_delete_log_failed" : "spookd_delete_log_nothing"));
                        return;
                    }
                    new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                            .setMessage(String.format(Locale.getDefault(),
                                    str("spookd_delete_whole_log_confirm"), result.size()))
                            .setPositiveButton(str("spookd_delete"), (dialog, which) -> {
                                // Serial with the reload below, so the reload sees the rows gone.
                                sDbJobs.execute(() -> {
                                    try {
                                        SavedMessagesHook.deleteDeletedLogEntries(result);
                                    } catch (Exception e) {
                                        Logger.printException(() -> "deleteWholeLog: ", e);
                                        Utils.runOnMainThread(() ->
                                                Utils.showToastShort(str("spookd_delete_log_failed")));
                                    }
                                });
                                loadMessages(null, null);
                                updateSelectionUi();
                            })
                            .setNegativeButton(str("spookd_cancel"), null)
                            .show();
                });
            });
        }

        private void showMoreOptions() {
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setItems(new CharSequence[]{str("spookd_refresh_profile_pictures")},
                            (dialog, which) -> refreshPictures())
                    .show();
        }

        /**
         * Refreshes the picture of each listed sender once, from Instagram's in-memory user cache when
         * it has the user, else with one profile request; sequential on a background thread, and it
         * stops when the screen closes.
         */
        private void refreshPictures() {
            if (refreshingPictures) return;
            UserSession session = SavedMessagesHook.currentSession();
            if (session == null) {
                Toast.makeText(activity, str("spookd_refresh_profile_pictures_unavailable"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Set<String> userIds = new LinkedHashSet<>();
            for (String[] message : messages) {
                if (message[6] != null && !message[6].isEmpty()) userIds.add(message[6]);
            }
            if (userIds.isEmpty()) return;
            refreshingPictures = true;
            Toast.makeText(activity, str("spookd_refresh_profile_pictures_started"),
                    Toast.LENGTH_SHORT).show();
            String viewerId = session.getUserId();
            Context app = activity.getApplicationContext();
            // Weak, so a refresh still running holds neither this screen nor its activity.
            WeakReference<Controller> owner = new WeakReference<>(this);
            refreshJob = sPictureRefresh.submit(() -> {
                int updated = refreshPictureUrls(app, session, viewerId, userIds);
                Utils.runOnMainThread(() -> {
                    Controller controller = owner.get();
                    if (controller != null) controller.onPicturesRefreshed(updated);
                });
            });
        }

        /** Main thread, once a refresh ended or was cancelled. */
        private void onPicturesRefreshed(int updated) {
            refreshingPictures = false;
            refreshJob = null;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            Toast.makeText(activity, String.format(Locale.getDefault(),
                    str("spookd_refresh_profile_pictures_done"), updated), Toast.LENGTH_SHORT).show();
            avatars.clearFailures();
            loadMessages(null, null);
        }

        /** Main thread, when the screen is destroyed. */
        void close() {
            avatars.close();
            if (refreshJob != null) refreshJob.cancel(true);
        }

        private void showMediaOptions(String messageId, String url, String type) {
            try {
                final boolean isAudio = "voice_media".equals(type) || "audio".equals(type)
                        || url.matches("(?i).*\\.(m4a|aac|mp3|ogg)(\\?.*)?$");
                final boolean isVideo = isVideo(url, type);
                final boolean isShare = url.contains("instagram.com/reel/")
                        || url.contains("instagram.com/p/") || url.contains("instagram.com/tv/");

                final CharSequence[] options = new CharSequence[] {
                    str("spookd_download_current_media"),
                    isShare ? str("spookd_open_share_in_instagram")
                            : isAudio ? str("spookd_open_voice_with_player")
                            : isVideo ? str("spookd_open_video_externally")
                            : str("spookd_open_image_externally"),
                    str("spookd_copy_media_link"),
                };

                new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setTitle(str("spookd_download_options"))
                    .setItems(options, (d, which) -> {
                        try {
                            if (which == 0) {
                                String fileName = "piko_" + messageId + guessExtension(url, type);
                                DownloadUtils.downloadMediaUrl(activity, url, Constants.DEFAULT_DM_FOLDER, fileName);
                            } else if (which == 2) {
                                Utils.setClipboard(MediaUrls.forViewing(url));
                                Utils.showToastShort(str("spookd_copied_media_link"));
                            } else {
                                Uri uri = Uri.parse(url);
                                // Captured message text can also start with "http" without using a web scheme.
                                String scheme = uri.getScheme();
                                if (scheme == null
                                        || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                                    return;
                                }
                                boolean igLink = url.contains("instagram.com/reel/")
                                        || url.contains("instagram.com/p/")
                                        || url.contains("instagram.com/tv/");
                                boolean opened = false;
                                if (igLink) {
                                    try {
                                        activity.startActivity(new Intent(Intent.ACTION_VIEW, uri)
                                                .setPackage("com.instagram.android"));
                                        opened = true;
                                    } catch (Exception ignored) {}
                                }
                                if (!opened) {
                                    String path = uri.getPath();
                                    boolean isImage = "image".equals(type) || (path != null
                                            && path.matches("(?i).*\\.(jpg|jpeg|png|webp|heic|heif|avif|gif)$"));
                                    if (!igLink && !isAudio && !isVideo && isImage) {
                                        ActivityHook.handleUrlIntent(false, url);
                                    } else {
                                        Intent i = new Intent(Intent.ACTION_VIEW, uri);
                                        if (isAudio) i.setDataAndType(uri, "audio/*");
                                        else if (isVideo && !igLink) i.setDataAndType(uri, "video/*");
                                        activity.startActivity(Intent.createChooser(i, null));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Logger.printException(() -> "showMediaOptions action: ", e);
                        }
                    })
                    .show();
            } catch (Exception e) {
                Logger.printException(() -> "showMediaOptions: ", e);
            }
        }

        private Drawable messageRowBackground() {
            int background = InstagramPreferenceStyle.backgroundColor();
            int highlight = InstagramPreferenceStyle.pressedBackgroundColor();
            StateListDrawable content = new StateListDrawable();
            content.addState(new int[]{android.R.attr.state_activated}, new ColorDrawable(highlight));
            content.addState(StateSet.WILD_CARD, new ColorDrawable(background));
            return new RippleDrawable(ColorStateList.valueOf(highlight), content, null);
        }

        private Drawable selectionControlDrawable() {
            TypedArray attributes = InstagramPreferenceStyle.dialogContext(activity)
                    .obtainStyledAttributes(new int[]{android.R.attr.listChoiceIndicatorMultiple});
            try {
                return attributes.getDrawable(0);
            } finally {
                attributes.recycle();
            }
        }

        private void bindSelectionAccessibility(View view, SelectionCheck check) {
            view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    if (selectionMode) info.setClassName("android.widget.CheckBox");
                    info.setCheckable(selectionMode);
                    info.setChecked(selectionMode && check.isChecked());
                }
            });
        }

        private class MessageAdapter extends BaseAdapter {
            @Override public int getCount() { return messages.size(); }
            @Override public Object getItem(int pos) { return messages.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                LinearLayout contentColumn;
                SelectionCheck selectionView;
                TextView senderView, contentView, metaView;

                if (convertView == null) {
                    row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setBackground(messageRowBackground());

                    selectionView = new SelectionCheck(
                            InstagramPreferenceStyle.dialogContext(activity), selectionControlDrawable());
                    selectionView.setTag("selection");
                    LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                            Dim.dp48, Dim.dp48);
                    checkParams.setMarginEnd(Dim.dp8);
                    row.addView(selectionView, checkParams);

                    ImageView avatarView = new ImageView(activity);
                    avatarView.setTag("avatar");
                    avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    GradientDrawable circle = new GradientDrawable();
                    circle.setShape(GradientDrawable.OVAL);
                    circle.setColor(InstagramPreferenceStyle.pressedBackgroundColor());
                    avatarView.setBackground(circle);
                    avatarView.setClipToOutline(true);
                    avatarView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                            Dim.dp40, Dim.dp40);
                    avatarParams.setMarginEnd(Dim.dp12);
                    row.addView(avatarView, avatarParams);

                    contentColumn = new LinearLayout(activity);
                    contentColumn.setOrientation(LinearLayout.VERTICAL);

                    senderView = new TextView(activity);
                    SpookdType.apply(senderView, SpookdType.SECONDARY);
                    senderView.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
                    senderView.setTag("s");

                    contentView = new TextView(activity);
                    SpookdType.apply(contentView, SpookdType.BODY);
                    contentView.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                    contentView.setPadding(0, Dim.dp4, 0, Dim.dp4);
                    contentView.setTag("c");

                    metaView = new TextView(activity);
                    SpookdType.apply(metaView, SpookdType.CAPTION);
                    metaView.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
                    metaView.setTag("m");

                    contentColumn.addView(senderView);
                    contentColumn.addView(contentView);
                    contentColumn.addView(metaView);
                    row.addView(contentColumn, new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                } else {
                    row = (LinearLayout) convertView;
                    selectionView = row.findViewWithTag("selection");
                    senderView = row.findViewWithTag("s");
                    contentView = row.findViewWithTag("c");
                    metaView    = row.findViewWithTag("m");
                }

                String[] msg = messages.get(position);
                avatars.load(msg[6] != null ? pictures.get(msg[6]) : null, row.findViewWithTag("avatar"));
                String sender    = msg[2];
                String content   = msg[3];
                String type      = msg[4];
                long   timestamp = 0;
                try { timestamp = Long.parseLong(msg[5]); } catch (Exception ignored) {}

                final String who;
                if (sender != null && !sender.isEmpty()) {
                    who = "@" + sender;
                } else {
                    who = str("spookd_unknown");
                }
                senderView.setText(who);
                boolean isMediaUrl = content != null && content.startsWith("http");
                String saved = savedLabel(msg[0]);
                if (SpookdMessageDb.REMOVED_REACTION.equals(type)) {
                    String message = isMediaUrl ? mediaLabel(msg[8], content)
                            : content != null && !content.isEmpty() ? content
                            : "[" + (msg[8] != null ? msg[8] : str("spookd_media_unknown")) + "]";
                    contentView.setText(String.format(Locale.getDefault(),
                            str("spookd_removed_reaction"), msg[7], message));
                } else if (isMediaUrl) {
                    contentView.setText(mediaLabel(type, content) + "  ·  " + (saved != null ? saved
                            : str(isExpiredMediaUrl(content) ? "spookd_media_expired" : "spookd_tap_to_view")));
                } else {
                    String text = content != null && !content.isEmpty() ? content
                            : (type != null ? "[" + type + "]" : str("spookd_media_deleted_generic"));
                    contentView.setText(saved != null ? text + "  ·  " + saved : text);
                }
                metaView.setText(timestampFormatter.format(new Date(timestamp)));
                boolean checked = messageList.isItemChecked(position);
                selectionView.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
                selectionView.setChecked(checked);
                row.setPadding(selectionMode ? Dim.dp8 : Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);
                row.setActivated(checked);

                return row;
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static class Api33BackHelper {
        static void register(Activity activity, Runnable onBack) {
            try {
                activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        onBack::run
                );
            } catch (Throwable ignored) {
            }
        }
    }
}
