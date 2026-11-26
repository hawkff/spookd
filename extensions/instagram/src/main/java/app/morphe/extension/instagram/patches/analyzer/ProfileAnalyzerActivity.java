package app.morphe.extension.instagram.patches.analyzer;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

/** The profile analyzer results of the current account, built in code inside the app's modal activity. */
public final class ProfileAnalyzerActivity {

    private static final int LOAD = 0;
    private static final int CLEAR_HISTORY = 1;
    private static final int DELETE_DATA = 2;
    // History survives scan pruning; older changes stay stored but are not listed.
    private static final int HISTORY_LIMIT = 500;
    // Serial, so a clear or delete runs before the reload that follows it.
    private static final Executor DB_JOBS = Executors.newSingleThreadExecutor();

    private ProfileAnalyzerActivity() {
    }

    /** A section title with its count, or a section's empty text when count is -1. */
    static final class Label {
        final String key;
        final int count;

        Label(String key, int count) {
            this.key = key;
            this.count = count;
        }
    }

    /** Main thread, once the Inbox lock allowed it. */
    public static void setupOnActivity(Activity activity) {
        Controller controller = new Controller(activity);
        controller.setup();
        if (activity.getApplication() == null) return;
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) { if (a == activity) controller.onResumed(); }
            @Override public void onActivityPaused(Activity a) { if (a == activity) controller.onPaused(); }
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override
            public void onActivityDestroyed(Activity a) {
                if (a == activity) {
                    controller.onPaused();
                    a.getApplication().unregisterActivityLifecycleCallbacks(this);
                }
            }
        });
    }

    /** Section titles in screen order, each followed by its rows or by its empty text. */
    static List<Object> rows(List<FollowUser> notFollowingBack, List<FollowUser> notFollowedBack,
                             List<FollowUser> mutuals, List<SpookdFollowDb.Change> history) {
        List<Object> out = new ArrayList<>();
        section(out, "spookd_profile_analyzer_not_following_back", notFollowingBack);
        section(out, "spookd_profile_analyzer_not_followed_back", notFollowedBack);
        section(out, "spookd_profile_analyzer_mutuals", mutuals);
        section(out, "spookd_profile_analyzer_history", history);
        return out;
    }

    private static void section(List<Object> out, String key, List<?> items) {
        out.add(new Label(key, items.size()));
        if (items.isEmpty()) out.add(new Label(key + "_empty", -1));
        else out.addAll(items);
    }

    /** Up to two letters from the full name, else one from the username, else "?". */
    static String initials(String username, String fullName) {
        StringBuilder out = new StringBuilder();
        int letters = 0;
        if (fullName != null) {
            for (String word : fullName.trim().split("\\s+")) {
                int at = firstLetterOrDigit(word);
                if (at < 0) continue;
                out.appendCodePoint(word.codePointAt(at));
                if (++letters == 2) break;
            }
        }
        if (letters == 0 && username != null) {
            int at = firstLetterOrDigit(username);
            if (at >= 0) out.appendCodePoint(username.codePointAt(at));
        }
        return out.length() == 0 ? "?" : out.toString().toUpperCase(Locale.getDefault());
    }

    private static int firstLetterOrDigit(String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) return i;
            i += Character.charCount(codePoint);
        }
        return -1;
    }

    /** The string key that says what happened; formatted with the old value, then the new one. */
    static String historyKey(String kind, String oldValue, String newValue) {
        if (kind == null) return null;
        boolean hadOld = oldValue != null && !oldValue.isEmpty();
        boolean hasNew = newValue != null && !newValue.isEmpty();
        switch (kind) {
            case SpookdFollowDb.NEW_FOLLOWER: return "spookd_profile_analyzer_new_follower";
            case SpookdFollowDb.LOST_FOLLOWER: return "spookd_profile_analyzer_lost_follower";
            case SpookdFollowDb.STARTED_FOLLOWING: return "spookd_profile_analyzer_started_following";
            case SpookdFollowDb.STOPPED_FOLLOWING: return "spookd_profile_analyzer_stopped_following";
            case SpookdFollowDb.USERNAME:
                return hadOld ? "spookd_profile_analyzer_username_changed"
                        : "spookd_profile_analyzer_username_set";
            case SpookdFollowDb.FULL_NAME:
                if (!hasNew) return "spookd_profile_analyzer_name_removed";
                return hadOld ? "spookd_profile_analyzer_name_changed"
                        : "spookd_profile_analyzer_name_set";
            case SpookdFollowDb.PICTURE: return "spookd_profile_analyzer_picture_changed";
            default: return null;
        }
    }

    /**
     * One latest known username per user of a newest-first history: the stored identity, else the
     * newest event that carries a username.
     */
    static Map<String, String> latestUsernames(List<SpookdFollowDb.Change> history) {
        Map<String, String> out = new HashMap<>();
        for (SpookdFollowDb.Change change : history) {
            if (out.containsKey(change.userId)) continue;
            String username = change.user != null ? change.user.username : null;
            // For name and picture changes the new value is not a username.
            if (username == null && !SpookdFollowDb.FULL_NAME.equals(change.kind)
                    && !SpookdFollowDb.PICTURE.equals(change.kind)) {
                username = change.newValue;
            }
            if (username != null && !username.isEmpty()) out.put(change.userId, username);
        }
        return out;
    }

    private static final class Loaded {
        final String accountId;
        final SpookdFollowDb.Scan scan;
        final List<Object> rows;
        final Map<String, String> usernames;
        final boolean hasHistory;
        final boolean hasVisits;

        Loaded(String accountId, SpookdFollowDb.Scan scan, List<Object> rows,
               Map<String, String> usernames, boolean hasHistory, boolean hasVisits) {
            this.accountId = accountId;
            this.scan = scan;
            this.rows = rows;
            this.usernames = usernames;
            this.hasHistory = hasHistory;
            this.hasVisits = hasVisits;
        }
    }

    private static final class RowViews {
        TextView initials, username, verified, fullName, change, note, date;
    }

    private static final class Controller {
        private final Activity activity;
        // Held here, since the analyzer keeps it only weakly.
        private final Runnable refresh = this::refreshScanState;
        private String accountId;
        private List<Object> rows = new ArrayList<>();
        private Map<String, String> usernames = new HashMap<>();
        private SimpleDateFormat dateFormat;
        private TextView summary;
        private Button startAction, clearAction, deleteAction;
        private RowsAdapter adapter;
        private boolean hasScan, hasHistory, hasVisits, wasScanning, paused;

        Controller(Activity activity) {
            this.activity = activity;
        }

        void setup() {
            Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
            String skeleton = DateFormat.is24HourFormat(activity) ? "yMMMdHm" : "yMMMdhm";
            dateFormat = new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale);

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
            TextView title = new TextView(activity);
            title.setText(str("spookd_profile_analyzer"));
            title.setTextColor(InstagramPreferenceStyle.primaryTextColor());
            InstagramPreferenceStyle.applyToolbarLayout(activity, toolbar, back, title, false);
            toolbar.addView(back);
            toolbar.addView(title);
            root.addView(toolbar);

            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp8);
            summary = new TextView(activity);
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            summary.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
            header.addView(summary);
            startAction = actionButton(header, str(ProfileAnalyzer.ROW_START),
                    InstagramPreferenceStyle.primaryTextColor());
            startAction.setOnClickListener(v -> {
                if (sameAccount()) ProfileAnalyzer.startScan(activity);
            });
            clearAction = actionButton(header, str("spookd_profile_analyzer_clear_history"),
                    InstagramPreferenceStyle.primaryTextColor());
            clearAction.setOnClickListener(v -> confirm("spookd_profile_analyzer_clear_history_confirm",
                    "spookd_profile_analyzer_clear", CLEAR_HISTORY));
            deleteAction = actionButton(header, str("spookd_profile_analyzer_delete_data"),
                    UI.getThemedColour("igds_color_error_or_destructive"));
            deleteAction.setOnClickListener(v -> confirm("spookd_profile_analyzer_delete_data_confirm",
                    "spookd_delete", DELETE_DATA));

            ListView list = new ListView(activity);
            list.addHeaderView(header, null, false);
            adapter = new RowsAdapter();
            list.setAdapter(adapter);
            list.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
            list.setDivider(new ColorDrawable(UI.getThemedColour("igds_color_separator")));
            list.setDividerHeight(1);
            list.setOnItemClickListener((parent, view, position, id) -> openProfile(
                    parent.getItemAtPosition(position)));
            root.addView(list, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

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

            ProfileAnalyzer.onScreenShown(refresh);
            show(ProfileAnalyzer.currentAccountId());
        }

        void onResumed() {
            ProfileAnalyzer.onScreenShown(refresh);
            // A reload recovers a scan end that came while another screen held the refresh.
            if (paused && sameAccount() && accountId != null) {
                // Sampled first, so a scan that ends during this reload still triggers another one.
                refreshScanState();
                load(LOAD, accountId);
            }
            paused = false;
        }

        void onPaused() {
            paused = true;
            ProfileAnalyzer.onScreenHidden(refresh);
        }

        /** Shows this account's data, or the 'Open your profile once' state without an account. */
        private void show(String account) {
            accountId = account;
            rows = new ArrayList<>();
            usernames = new HashMap<>();
            hasScan = false;
            hasHistory = false;
            hasVisits = false;
            adapter.notifyDataSetChanged();
            int actions = account == null ? View.GONE : View.VISIBLE;
            startAction.setVisibility(actions);
            clearAction.setVisibility(actions);
            deleteAction.setVisibility(actions);
            summary.setText(account == null ? str(ProfileAnalyzer.ROW_OPEN_PROFILE) : "");
            if (account == null) return;
            refreshScanState();
            load(LOAD, account);
        }

        /** Main thread. False, after switching the screen over, when the current account changed. */
        private boolean sameAccount() {
            String current = ProfileAnalyzer.currentAccountId();
            if (Objects.equals(current, accountId)) return true;
            show(current);
            return false;
        }

        private Button actionButton(LinearLayout parent, String text, int textColor) {
            Button button = new Button(activity);
            button.setText(text);
            button.setAllCaps(false);
            button.setTextColor(textColor);
            button.setBackgroundTintList(null);
            GradientDrawable background = new GradientDrawable();
            background.setColor(InstagramPreferenceStyle.pressedBackgroundColor());
            background.setCornerRadius(Dim.dp24);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                    InstagramPreferenceStyle.secondaryTextColor() & 0x00ffffff | 0x24000000),
                    background, null));
            button.setStateListAnimator(null);
            button.setElevation(0);
            button.setMinHeight(Dim.dp48);
            button.setMinimumHeight(Dim.dp48);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = Dim.dp8;
            parent.addView(button, params);
            return button;
        }

        private static void setActionEnabled(Button button, boolean enabled) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.5f);
        }

        /** Main thread: the scan action shows the row's state; a scan that just ended reloads the lists. */
        private void refreshScanState() {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (!sameAccount() || accountId == null) return;
            String state = ProfileAnalyzer.rowSummary(activity);
            boolean scanning = ProfileAnalyzer.isScanning();
            startAction.setText(state);
            setActionEnabled(startAction, str(ProfileAnalyzer.ROW_START).equals(state));
            setActionEnabled(clearAction, hasHistory);
            // A scan that commits after a delete would bring the data back.
            setActionEnabled(deleteAction, (hasScan || hasHistory || hasVisits) && !scanning);
            boolean ended = wasScanning && !scanning;
            wasScanning = scanning;
            if (ended) load(LOAD, accountId);
        }

        private void confirm(String messageKey, String actionKey, int job) {
            if (!sameAccount() || accountId == null) return;
            String account = accountId;
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                    .setMessage(str(messageKey))
                    .setPositiveButton(str(actionKey), (dialog, which) -> {
                        if (!sameAccount() || !account.equals(accountId)) return;
                        if (job == DELETE_DATA && ProfileAnalyzer.isScanning()) return;
                        load(job, account);
                    })
                    .setNegativeButton(str("spookd_cancel"), null)
                    .show();
        }

        /** Runs the job and the queries on the DB thread, then binds on the main thread. */
        private void load(int job, String account) {
            DB_JOBS.execute(() -> {
                Loaded loaded = null;
                try {
                    SpookdFollowDb db = SpookdFollowDb.getInstance(activity);
                    if (job == CLEAR_HISTORY) db.clearHistory(account);
                    else if (job == DELETE_DATA) db.deleteAccount(account);
                    SpookdFollowDb.Scan scan = db.latestScan(account);
                    List<SpookdFollowDb.Change> history = db.history(account, HISTORY_LIMIT);
                    List<Object> found = scan == null ? new ArrayList<>() : rows(
                            db.notFollowingBack(account), db.followersNotFollowedBack(account),
                            db.mutuals(account), history);
                    List<SpookdFollowDb.Visit> visits = db.visits(account);
                    if (!visits.isEmpty() || Pref.trackVisitedProfiles()) {
                        section(found, "spookd_profile_analyzer_visited", visits);
                    }
                    loaded = new Loaded(account, scan, found, latestUsernames(history),
                            !history.isEmpty(), !visits.isEmpty());
                } catch (Exception e) {
                    String error = e.getClass().getSimpleName();
                    Logger.printException(() -> "ProfileAnalyzerActivity: load failed: " + error);
                }
                Loaded result = loaded;
                Utils.runOnMainThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (result == null) {
                        PikoUtils.toast(str("spookd_profile_analyzer_load_failed"));
                        return;
                    }
                    // A result for an account the screen no longer shows is dropped.
                    if (!sameAccount() || !result.accountId.equals(accountId)) return;
                    bind(result);
                });
            });
        }

        private void bind(Loaded loaded) {
            hasScan = loaded.scan != null;
            hasHistory = loaded.hasHistory;
            hasVisits = loaded.hasVisits;
            summary.setText(loaded.scan == null ? str("spookd_profile_analyzer_no_scan")
                    : str("spookd_profile_analyzer_last_scan",
                    dateFormat.format(new Date(loaded.scan.finishedAt)),
                    ProfileAnalyzer.count(loaded.scan.followerCount),
                    ProfileAnalyzer.count(loaded.scan.followingCount)));
            rows = loaded.rows;
            usernames = loaded.usernames;
            adapter.notifyDataSetChanged();
            refreshScanState();
        }

        private void openProfile(Object item) {
            if (!sameAccount() || accountId == null) return;
            String username = item instanceof FollowUser ? ((FollowUser) item).username
                    : item instanceof SpookdFollowDb.Change
                    ? usernames.get(((SpookdFollowDb.Change) item).userId)
                    : item instanceof SpookdFollowDb.Visit ? ((SpookdFollowDb.Visit) item).username : null;
            if (username == null || username.isEmpty()) return;
            PikoUtils.openUrl("instagram://user?username=" + Uri.encode(username), true);
        }

        private final class RowsAdapter extends BaseAdapter {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int position) { return rows.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int position) { return rows.get(position) instanceof Label ? 0 : 1; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) { return !(rows.get(position) instanceof Label); }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Object item = rows.get(position);
                if (item instanceof Label) return labelView((Label) item, (TextView) convertView);
                View row = convertView != null ? convertView : newRow();
                RowViews views = (RowViews) row.getTag();
                if (item instanceof FollowUser) {
                    bindUser(views, (FollowUser) item, null);
                    views.change.setVisibility(View.GONE);
                    views.note.setVisibility(View.GONE);
                    views.date.setVisibility(View.GONE);
                } else if (item instanceof SpookdFollowDb.Visit) {
                    SpookdFollowDb.Visit visit = (SpookdFollowDb.Visit) item;
                    bindUser(views, null, visit.username);
                    views.change.setVisibility(View.GONE);
                    views.note.setVisibility(View.GONE);
                    views.date.setText(dateFormat.format(new Date(visit.visitedAt)));
                    views.date.setVisibility(View.VISIBLE);
                } else {
                    SpookdFollowDb.Change change = (SpookdFollowDb.Change) item;
                    bindUser(views, change.user, usernames.get(change.userId));
                    String key = historyKey(change.kind, change.oldValue, change.newValue);
                    views.change.setText(key == null ? change.kind
                            : str(key, change.oldValue, change.newValue));
                    views.change.setVisibility(View.VISIBLE);
                    views.note.setVisibility(SpookdFollowDb.LOST_FOLLOWER.equals(change.kind)
                            ? View.VISIBLE : View.GONE);
                    views.date.setText(dateFormat.format(new Date(change.seenAt)));
                    views.date.setVisibility(View.VISIBLE);
                }
                return row;
            }

            private TextView labelView(Label label, TextView view) {
                if (view == null) view = new TextView(activity);
                boolean title = label.count >= 0;
                view.setText(title ? str(label.key, ProfileAnalyzer.count(label.count)) : str(label.key));
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, title ? 16 : 14);
                view.setTypeface(null, title ? Typeface.BOLD : Typeface.NORMAL);
                view.setTextColor(title ? InstagramPreferenceStyle.primaryTextColor()
                        : InstagramPreferenceStyle.secondaryTextColor());
                view.setPadding(Dim.dp16, title ? Dim.dp24 : Dim.dp12, Dim.dp16, Dim.dp12);
                return view;
            }

            /** Username, full name and verified mark; the username falls back to the given one. */
            private void bindUser(RowViews views, FollowUser user, String username) {
                String name = user != null && user.username != null ? user.username : username;
                String fullName = user != null ? user.fullName : null;
                views.initials.setText(initials(name, fullName));
                views.username.setText(name != null ? name : str("spookd_unknown"));
                views.verified.setVisibility(user != null && user.verified ? View.VISIBLE : View.GONE);
                views.fullName.setText(fullName);
                views.fullName.setVisibility(fullName != null && !fullName.isEmpty()
                        ? View.VISIBLE : View.GONE);
            }

            private View newRow() {
                RowViews views = new RowViews();
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);

                views.initials = new TextView(activity);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(InstagramPreferenceStyle.pressedBackgroundColor());
                views.initials.setBackground(circle);
                views.initials.setGravity(Gravity.CENTER);
                views.initials.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                views.initials.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                views.initials.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48);
                circleParams.setMarginEnd(Dim.dp12);
                row.addView(views.initials, circleParams);

                LinearLayout column = new LinearLayout(activity);
                column.setOrientation(LinearLayout.VERTICAL);
                LinearLayout nameLine = new LinearLayout(activity);
                nameLine.setOrientation(LinearLayout.HORIZONTAL);
                nameLine.setGravity(Gravity.CENTER_VERTICAL);
                views.username = text(15, InstagramPreferenceStyle.primaryTextColor());
                views.username.setTypeface(null, Typeface.BOLD);
                nameLine.addView(views.username);
                views.verified = text(14, InstagramPreferenceStyle.selectionColor());
                views.verified.setText("\u2713");
                views.verified.setContentDescription(str("spookd_profile_analyzer_verified"));
                views.verified.setPaddingRelative(Dim.dp4, 0, 0, 0);
                nameLine.addView(views.verified);
                column.addView(nameLine);
                views.fullName = text(14, InstagramPreferenceStyle.secondaryTextColor());
                column.addView(views.fullName);
                views.change = text(14, InstagramPreferenceStyle.primaryTextColor());
                views.change.setPadding(0, Dim.dp4, 0, 0);
                column.addView(views.change);
                views.note = text(12, InstagramPreferenceStyle.secondaryTextColor());
                views.note.setText(str("spookd_profile_analyzer_lost_follower_note"));
                column.addView(views.note);
                views.date = text(11, InstagramPreferenceStyle.secondaryTextColor());
                views.date.setPadding(0, Dim.dp4, 0, 0);
                column.addView(views.date);
                row.addView(column, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                row.setTag(views);
                return row;
            }

            private TextView text(int sizeSp, int color) {
                TextView view = new TextView(activity);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
                view.setTextColor(color);
                return view;
            }
        }
    }
}
