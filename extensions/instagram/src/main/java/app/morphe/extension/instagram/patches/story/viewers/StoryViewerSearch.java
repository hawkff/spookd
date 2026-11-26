package app.morphe.extension.instagram.patches.story.viewers;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.instagram.common.session.UserSession;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.patches.analyzer.FollowUser;
import app.morphe.extension.instagram.patches.analyzer.SpookdFollowDb;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramSearchStyle;
import app.morphe.extension.instagram.settings.preference.widgets.SpookdType;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Searches the viewers Instagram loaded for your own story, filters them with the last Profile
 * analyzer scan or the starred list, and loads the rest one page at a time through the viewer
 * list's own paging. Everything here runs on the main thread except reading the scan.
 */
@SuppressWarnings("unused")
public final class StoryViewerSearch {
    private static final long PAGE_TIMEOUT_MS = 20_000;
    // A short pause between the extra page requests.
    private static final long PAGE_GAP_MS = 600;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long OLD_SCAN_MS = 7 * DAY_MS;

    // Each viewer list adapter with the number of its latest rebuild.
    private static final Map<Object, Long> ADAPTERS = new WeakHashMap<>();
    private static long rebuilds;
    private static Search open;

    private StoryViewerSearch() {
    }

    /** At the start of the viewer list screen's onViewCreated: holding its settings button opens the search. */
    public static void onDashboardViewCreated(Object fragment, View root) {
        try {
            if (!Pref.searchStoryViewers() || root == null) return;
            int id = ResourceUtils.getIdentifier(root.getContext(), ResourceType.ID, "button_settings");
            View settings = id == 0 ? null : root.findViewById(id);
            if (settings == null) return;
            WeakReference<Object> screen = new WeakReference<>(fragment);
            settings.setOnLongClickListener(v -> open(v.getContext(), screen.get()));
        } catch (Throwable t) {
            Logger.printException(() -> "StoryViewerSearch: could not add the search", t);
        }
    }

    /** At the start of the viewer list screen's onDestroyView: closes its search and ends its Load all. */
    public static void onDashboardViewDestroyed(Object fragment) {
        try {
            Search search = open;
            if (search == null) return;
            Object screen = search.screen.get();
            if (screen == null || screen == fragment) search.close();
        } catch (Throwable t) {
            Logger.printException(() -> "StoryViewerSearch: could not close the search", t);
        }
    }

    /** At the start of each rebuild of a story's viewer list, which follows every page Instagram loads. */
    public static void onViewersRebuilt(Object adapter) {
        try {
            if (!Pref.searchStoryViewers()) return;
            ADAPTERS.put(adapter, ++rebuilds);
            Search search = open;
            // Posted, so the search reads the list once it is rebuilt.
            if (search != null && search.adapter == adapter) Utils.runOnMainThread(search::onRebuilt);
        } catch (Throwable t) {
            Logger.printException(() -> "StoryViewerSearch: rebuild failed", t);
        }
    }

    private static boolean open(Context context, Object fragment) {
        try {
            if (fragment == null || !Pref.searchStoryViewers()) return false;
            Object story = currentStory(fragment);
            Object adapter = null;
            long newest = -1;
            if (story != null) {
                for (Map.Entry<Object, Long> entry : ADAPTERS.entrySet()) {
                    if (entry.getValue() > newest && story.equals(adapterStory(entry.getKey()))) {
                        adapter = entry.getKey();
                        newest = entry.getValue();
                    }
                }
            }
            if (adapter == null) {
                Utils.showToastShort(str("spookd_viewer_search_not_loaded"));
                return true;
            }
            UserSession session = adapterSession(adapter);
            new Search(context, fragment, adapter, session == null ? null : session.getUserId()).show();
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "StoryViewerSearch: could not open the search", t);
            return false;
        }
    }

    // The patch replaces these bodies with reads of Instagram's viewer list screen.

    /** The story whose viewer list the screen shows. */
    private static Object currentStory(Object fragment) {
        return null;
    }

    /** The story of one viewer list. */
    private static Object adapterStory(Object adapter) {
        return null;
    }

    private static UserSession adapterSession(Object adapter) {
        return null;
    }

    /** The Instagram viewers loaded so far, without yourself, as the list shows them. */
    private static List<?> adapterViewers(Object adapter) {
        return null;
    }

    /** The viewers of a story shared to Facebook, without yourself. */
    private static List<?> facebookViewers(Object adapter) {
        return null;
    }

    /** The viewers Instagram marks as spam. */
    private static List<?> spamViewers(Object adapter) {
        return null;
    }

    private static Object viewerUser(Object viewer) {
        return null;
    }

    /** Whether Instagram has another page of viewers for this list. */
    private static boolean hasMoreViewers(Object adapter) {
        return false;
    }

    /** The cursor of the next page; it moves with every page Instagram receives. */
    private static String viewersCursor(Object adapter) {
        return null;
    }

    /** Whether Instagram's own request for a page is out. */
    private static boolean viewersBusy(Object adapter) {
        return false;
    }

    /** Asks for the next page through the call scrolling to the end uses, which does nothing while a page is out. */
    private static void loadMoreViewers(Object adapter) {
    }

    /** The last Profile analyzer scan of one account, as follow groups. */
    private static final class FollowData {
        static final FollowData NO_ANALYZER = new FollowData(0);
        static final FollowData NO_SCAN = new FollowData(0);

        final long scannedAt;
        final Set<String> followers = new HashSet<>();
        final Set<String> notFollowingBack = new HashSet<>();

        FollowData(long scannedAt) {
            this.scannedAt = scannedAt;
        }

        /** Background thread. */
        static FollowData read(Context context, String account) {
            if (!SettingsStatus.profileAnalyzer) return NO_ANALYZER;
            if (account == null || account.isEmpty()) return NO_SCAN;
            try {
                SpookdFollowDb db = SpookdFollowDb.getInstance(context);
                SpookdFollowDb.Scan scan = db.latestScan(account);
                if (scan == null) return NO_SCAN;
                FollowData data = new FollowData(scan.finishedAt);
                addIds(data.followers, db.followersNotFollowedBack(account));
                addIds(data.followers, db.mutuals(account));
                addIds(data.notFollowingBack, db.notFollowingBack(account));
                return data;
            } catch (Exception e) {
                Logger.printException(() -> "StoryViewerSearch: could not read the scan", e);
                return NO_SCAN;
            }
        }

        private static void addIds(Set<String> ids, Collection<FollowUser> users) {
            for (FollowUser user : users) ids.add(user.id);
        }
    }

    /** One open search dialog over one story's viewer list. */
    private static final class Search {
        final Context context;
        final WeakReference<Object> screen;
        final Object adapter;
        final String account;
        final String starsKey;
        Set<String> starred;
        // Instagram's viewer entries with what was read from them, so a rebuild reads only new ones.
        final Map<Object, ViewerFilter.Viewer> known = new IdentityHashMap<>();
        final List<ViewerFilter.Viewer> shown = new ArrayList<>();
        AlertDialog dialog;
        EditText query;
        TextView status;
        ArrayAdapter<String> rows;
        Button loadAllButton;
        int filter = ViewerFilter.ALL;
        FollowData follow;
        int loaded;
        boolean loading;
        boolean awaiting;
        boolean stalled;
        boolean rowFailed;
        String cursorAtRequest;
        long requestedAt;
        long busySince;

        Search(Context context, Object fragment, Object adapter, String account) {
            this.context = context;
            this.screen = new WeakReference<>(fragment);
            this.adapter = adapter;
            this.account = account;
            starsKey = ViewerStars.key(account);
            starred = starsKey == null ? new HashSet<>() : ViewerStars.ids(SharedPref.getStringPref(starsKey, "{}"));
        }

        /** Drops the static reference first, so every pending paging and refresh callback ends. */
        void close() {
            loading = false;
            awaiting = false;
            if (open == this) open = null;
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }

        void show() {
            Context themed = InstagramPreferenceStyle.dialogContext(context);
            int edge = InstagramPreferenceStyle.dp(themed, SpookdType.SPACE_XL);
            LinearLayout content = new LinearLayout(themed);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(edge, InstagramPreferenceStyle.dp(themed, SpookdType.SPACE_S), edge, 0);

            query = new EditText(themed);
            query.setSingleLine(true);
            query.setHint(str("spookd_viewer_search_hint"));
            SpookdType.applyDialogInput(query);
            InstagramSearchStyle.applyInputStyle(query);
            query.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    InstagramSearchStyle.removeBackgroundSpans(s);
                    render();
                }
            });
            content.addView(query);

            RadioGroup filters = new RadioGroup(themed);
            filters.setOrientation(LinearLayout.HORIZONTAL);
            String[] labels = {str("spookd_viewer_search_all"), str("spookd_viewer_search_followers"),
                    str("spookd_viewer_search_not_following_back"), str("spookd_viewer_search_starred")};
            int first = 0;
            for (int i = 0; i < labels.length; i++) {
                RadioButton option = new RadioButton(themed);
                option.setId(View.generateViewId());
                option.setTag(i);
                option.setText(labels[i]);
                SpookdType.apply(option, SpookdType.SECONDARY);
                option.setTextColor(InstagramPreferenceStyle.primaryTextColor());
                filters.addView(option);
                if (i == 0) first = option.getId();
            }
            filters.check(first);
            filters.setOnCheckedChangeListener((group, checkedId) -> {
                View option = group.findViewById(checkedId);
                if (option == null) return;
                filter = (Integer) option.getTag();
                render();
            });
            HorizontalScrollView filterRow = new HorizontalScrollView(themed);
            filterRow.addView(filters);
            content.addView(filterRow);

            status = new TextView(themed);
            SpookdType.apply(status, SpookdType.SECONDARY);
            status.setTextColor(InstagramPreferenceStyle.secondaryTextColor());
            content.addView(status);

            rows = new ArrayAdapter<>(themed, android.R.layout.simple_list_item_1, new ArrayList<>());
            ListView list = new ListView(themed);
            list.setAdapter(rows);
            list.setOnItemClickListener((parent, view, position, id) -> openProfile(position));
            list.setOnItemLongClickListener((parent, view, position, id) -> {
                toggleStar(position);
                return true;
            });
            content.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    InstagramPreferenceStyle.dp(themed, 320)));

            dialog = new AlertDialog.Builder(themed)
                    .setTitle(str("spookd_search_story_viewers"))
                    .setView(content)
                    .setNeutralButton(str("spookd_viewer_search_load_all"), null)
                    .setNegativeButton(str("spookd_close"), null)
                    .create();
            dialog.setOnShowListener(d -> {
                loadAllButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                // Set here, so a press keeps the dialog open.
                if (loadAllButton != null) loadAllButton.setOnClickListener(v -> loadAll());
                render();
            });
            dialog.setOnDismissListener(d -> close());
            open = this;
            dialog.show();
            readFollowData();
        }

        private void readFollowData() {
            Context app = context.getApplicationContext();
            Utils.runOnBackgroundThread(() -> {
                FollowData data = FollowData.read(app, account);
                Utils.runOnMainThread(() -> {
                    follow = data;
                    if (open == this) render();
                });
            });
        }

        void onRebuilt() {
            if (open != this) return;
            render();
            if (!awaiting) return;
            ViewerPaging.Step step = ViewerPaging.afterRebuild(cursorAtRequest, cursor(), hasMore(), busy());
            if (step == ViewerPaging.Step.WAIT) return;
            awaiting = false;
            if (step == ViewerPaging.Step.STOP) {
                stop();
            } else {
                // requestPage also ends the run when Instagram has no more pages.
                Utils.runOnMainThreadDelayed(this::requestPage, PAGE_GAP_MS);
            }
        }

        private void loadAll() {
            if (loading) return;
            loading = true;
            stalled = false;
            busySince = 0;
            requestPage();
        }

        private void requestPage() {
            if (open != this || !loading) return;
            ViewerPaging.Step step = ViewerPaging.beforeRequest(hasMore(), busy());
            if (step == ViewerPaging.Step.DONE) {
                loading = false;
                render();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (step == ViewerPaging.Step.WAIT) {
                // Instagram is already fetching a page; ask again once it is back instead of sending another.
                if (busySince == 0) busySince = now;
                if (ViewerPaging.afterTimeout(true, now - busySince, PAGE_TIMEOUT_MS) == ViewerPaging.Step.STOP) {
                    stop();
                } else {
                    Utils.runOnMainThreadDelayed(this::requestPage, PAGE_GAP_MS);
                }
                return;
            }
            busySince = 0;
            awaiting = true;
            cursorAtRequest = cursor();
            requestedAt = now;
            try {
                loadMoreViewers(adapter);
            } catch (Throwable t) {
                Logger.printException(() -> "StoryViewerSearch: Instagram did not load the next page", t);
                stop();
                return;
            }
            render();
            Utils.runOnMainThreadDelayed(() -> checkTimeout(now), PAGE_TIMEOUT_MS);
        }

        private void checkTimeout(long at) {
            if (open != this || !awaiting || requestedAt != at) return;
            long waited = SystemClock.uptimeMillis() - at;
            if (ViewerPaging.afterTimeout(busy(), waited, 3 * PAGE_TIMEOUT_MS) == ViewerPaging.Step.WAIT) {
                Utils.runOnMainThreadDelayed(() -> checkTimeout(at), PAGE_TIMEOUT_MS);
            } else {
                stop();
            }
        }

        private void stop() {
            awaiting = false;
            loading = false;
            stalled = true;
            if (open == this) render();
        }

        private String cursor() {
            try {
                return viewersCursor(adapter);
            } catch (Throwable t) {
                Logger.printException(() -> "StoryViewerSearch: paging cursor unreadable", t);
                return null;
            }
        }

        private boolean busy() {
            try {
                return viewersBusy(adapter);
            } catch (Throwable t) {
                Logger.printException(() -> "StoryViewerSearch: paging state unreadable", t);
                return false;
            }
        }

        private boolean hasMore() {
            try {
                return hasMoreViewers(adapter);
            } catch (Throwable t) {
                Logger.printException(() -> "StoryViewerSearch: paging state unreadable", t);
                return false;
            }
        }

        private void render() {
            if (dialog == null || status == null) return;
            List<ViewerFilter.Viewer> viewers = viewers();
            loaded = viewers.size();
            FollowData data = follow != null ? follow : FollowData.NO_SCAN;
            List<ViewerFilter.Viewer> matches = ViewerFilter.filter(viewers, query.getText().toString(), filter,
                    data.followers, data.notFollowingBack, starred);
            shown.clear();
            shown.addAll(matches);
            rows.setNotifyOnChange(false);
            rows.clear();
            for (ViewerFilter.Viewer viewer : matches) rows.add(label(viewer, starred.contains(viewer.id)));
            rows.notifyDataSetChanged();

            boolean more = hasMore();
            if (loadAllButton != null) loadAllButton.setEnabled(more && !loading);
            String paging = loading ? str("spookd_viewer_search_loading", count(loaded))
                    : stalled ? str("spookd_viewer_search_stopped")
                    : more ? str("spookd_viewer_search_partial")
                    : str("spookd_viewer_search_all_loaded");
            status.setText(str("spookd_viewer_search_count", count(matches.size()), count(loaded))
                    + "\n" + paging + "\n" + followText() + "\n" + str("spookd_viewer_search_tip"));
        }

        private List<ViewerFilter.Viewer> viewers() {
            try {
                return ViewerFilter.combine(account, read(adapterViewers(adapter), ViewerFilter.INSTAGRAM),
                        read(facebookViewers(adapter), ViewerFilter.FACEBOOK),
                        read(spamViewers(adapter), ViewerFilter.SPAM));
            } catch (Throwable t) {
                Logger.printException(() -> "StoryViewerSearch: viewer list unreadable", t);
                return Collections.emptyList();
            }
        }

        private List<ViewerFilter.Viewer> read(List<?> entries, int kind) {
            if (entries == null) return Collections.emptyList();
            List<ViewerFilter.Viewer> out = new ArrayList<>(entries.size());
            for (Object entry : entries) {
                ViewerFilter.Viewer viewer = known.get(entry);
                if (viewer == null) {
                    try {
                        UserData user = new UserData(viewerUser(entry));
                        viewer = new ViewerFilter.Viewer(user.getUserId(), user.getUsername(), user.getRawFullName(),
                                kind);
                    } catch (Throwable t) {
                        if (!rowFailed) Logger.printException(() -> "StoryViewerSearch: viewer unreadable", t);
                        rowFailed = true;
                        continue;
                    }
                    known.put(entry, viewer);
                }
                out.add(viewer);
            }
            return out;
        }

        private String followText() {
            if (follow == null) return str("spookd_viewer_search_follow_reading");
            if (follow == FollowData.NO_ANALYZER) return str("spookd_viewer_search_no_analyzer");
            if (follow.scannedAt == 0) return str("spookd_viewer_search_no_scan");
            String date = DateFormat.getMediumDateFormat(context).format(new Date(follow.scannedAt));
            long age = System.currentTimeMillis() - follow.scannedAt;
            return age > OLD_SCAN_MS ? str("spookd_viewer_search_scan_old", date, count((int) (age / DAY_MS)))
                    : str("spookd_viewer_search_scan", date);
        }

        private void openProfile(int position) {
            if (position < 0 || position >= shown.size()) return;
            String username = shown.get(position).username;
            if (username == null || username.isEmpty()) return;
            dialog.dismiss();
            PikoUtils.openUrl("instagram://user?username=" + Uri.encode(username), true);
        }

        private void toggleStar(int position) {
            if (position < 0 || position >= shown.size()) return;
            ViewerFilter.Viewer viewer = shown.get(position);
            if (viewer.id == null) return;
            if (starsKey == null) return;
            String stars = ViewerStars.toggled(SharedPref.getStringPref(starsKey, "{}"), viewer.id, viewer.username);
            SharedPref.setStringPref(starsKey, stars);
            starred = ViewerStars.ids(stars);
            render();
        }

        private static String label(ViewerFilter.Viewer viewer, boolean starred) {
            String name = viewer.fullName == null || viewer.fullName.isEmpty() ? "" : " · " + viewer.fullName;
            String source = viewer.kind == ViewerFilter.FACEBOOK ? " · " + str("spookd_viewer_search_facebook")
                    : viewer.kind == ViewerFilter.SPAM ? " · " + str("spookd_viewer_search_spam") : "";
            return (starred ? "★ " : "") + "@" + (viewer.username == null ? "" : viewer.username) + name + source;
        }

        private static String count(int value) {
            return NumberFormat.getIntegerInstance().format(value);
        }
    }
}
