/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.confirm;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Asks before a like, follow, unfollow, repost, comment, reaction, call or follow request approval goes
 * through. Every entry point runs on the main thread from Instagram's own click handlers and returns
 * true, letting the action run, on any failure.
 */
@SuppressWarnings("unused")
public final class ActionConfirm {
    static final String LIKE = "like";
    static final String FOLLOW = "follow";
    static final String REPOST = "repost";
    static final String COMMENT = "comment";
    static final String UNFOLLOW = "unfollow";
    static final String WITHDRAW = "withdraw";
    static final String REACTION = "reaction";
    static final String CALL = "call";
    static final String FOLLOW_REQUEST = "follow_request";

    // Set only while an approved action runs again: what it was about, and how it looked when asked.
    private static Object pendingToken;
    private static List<Object> pendingState;
    private static final WeakHashMap<View, String> followNames = new WeakHashMap<>();
    // Counts click listener changes, so a rebound button no longer matches an earlier prompt.
    private static final WeakHashMap<View, Integer> bindCounts = new WeakHashMap<>();
    private static boolean stagedFeedLiked;
    private static int repostsUfiId;
    private static int storyLikeId;
    private static int followRequestAcceptId;
    // The story heart's presenter, and the item that presenter was last bound to.
    private static final WeakHashMap<View, Object> storyLikePresenters = new WeakHashMap<>();
    private static final WeakHashMap<Object, Object> storyModels = new WeakHashMap<>();

    private ActionConfirm() {
    }

    /** From the follow button's click; {@code textType} is the label it shows. */
    public static boolean allowFollowClick(View button, Object textType) {
        try {
            String type = textType instanceof Enum ? ((Enum<?>) textType).name() : null;
            String name = followNames.get(button);
            List<Object> state = Arrays.asList(bindCount(button), name, type);
            Boolean replay = replayVerdict(button, state);
            if (replay != null) return replay;
            if (!isFollow(type) || !Pref.confirmFollow()) return true;
            String title = name == null || name.isEmpty()
                    ? str("spookd_confirm_follow_title") : str("spookd_confirm_follow_user_title", name);
            return !ask(button, FOLLOW, title, button, state, button::performClick);
        } catch (Throwable t) {
            Logger.printException(() -> "allowFollowClick failure", t);
            return true;
        }
    }

    /** From the follow button's bind; {@code name} is the account it follows. */
    public static void rememberFollowName(View button, Object status, Object session, String name) {
        try {
            followNames.put(button, name);
        } catch (Throwable t) {
            Logger.printException(() -> "rememberFollowName failure", t);
        }
    }

    /** From a confirmed button's setOnClickListener, which Instagram calls on every bind. */
    public static void onClickListenerSet(View button) {
        try {
            // Counts only tell a prompt from a later bind, so none are needed while nothing can ask.
            if (bindCounts.isEmpty() && !Pref.confirmFollow() && !Pref.confirmRepost() && !Pref.confirmStoryLike()) return;
            bindCounts.put(button, bindCount(button) + 1);
        } catch (Throwable t) {
            Logger.printException(() -> "onClickListenerSet failure", t);
        }
    }

    /** From the feed like handler, before {@link #allowFeedLike}: whether the post is liked already. */
    public static void stageFeedLiked(boolean liked) {
        stagedFeedLiked = liked;
    }

    /** From the feed like handler, after {@link #allowFeedLike} let it go on: the staged value, unchanged. */
    public static boolean stagedFeedLiked() {
        return stagedFeedLiked;
    }

    /** From the feed like handler, which every feed and post viewer heart reaches; it likes or unlikes. */
    public static boolean allowFeedLike(Object handler, Object media, Object mediaState, String module, Object onDone, int position) {
        try {
            List<Object> state = Arrays.asList(stagedFeedLiked);
            Boolean replay = replayVerdict(media, state);
            if (replay != null) return replay;
            if (media == null || stagedFeedLiked || !Pref.confirmLike()) return true;
            Object[] args = {media, mediaState, module, onDone, position};
            return !ask(null, LIKE, str("spookd_confirm_like_title"), media, state, call(feedLikeMethod(handler.getClass()), handler, args));
        } catch (Throwable t) {
            Logger.printException(() -> "allowFeedLike failure", t);
            return true;
        }
    }

    /**
     * From the Reels like handler; {@code refs} holds the handler and its first nine arguments. The
     * eighth argument is a reaction its caller has already stored, so those calls are not asked.
     */
    public static boolean allowReelLike(Object[] refs, boolean enabled, boolean liked, Class<?> owner, String name) {
        try {
            View view = (View) refs[1];
            List<Object> state = Arrays.asList(liked);
            Boolean replay = replayVerdict(view, state);
            if (replay != null) return replay;
            if (view == null || isReaction(refs) || !enabled || liked || !Pref.confirmLike()) return true;
            Object[] args = Arrays.copyOfRange(refs, 1, refs.length + 2);
            args[args.length - 2] = enabled;
            args[args.length - 1] = liked;
            return !ask(view, LIKE, str("spookd_confirm_like_title"), view, state, invoker(owner, name, refs[0], args));
        } catch (Throwable t) {
            Logger.printException(() -> "allowReelLike failure", t);
            return true;
        }
    }

    /** From the Reels repost handler, a static method; the sixth argument is set when undoing a repost. */
    public static boolean allowReelRepost(Object[] refs, int last, Class<?> owner, String name) {
        try {
            View view = (View) refs[0];
            List<Object> state = Arrays.asList(refs[5]);
            Boolean replay = replayVerdict(view, state);
            if (replay != null) return replay;
            if (view == null || refs[5] != null || !Pref.confirmRepost()) return true;
            Object[] args = Arrays.copyOf(refs, refs.length + 1);
            args[refs.length] = last;
            return !ask(view, REPOST, str("spookd_confirm_repost_title"), view, state, invoker(owner, name, null, args));
        } catch (Throwable t) {
            Logger.printException(() -> "allowReelRepost failure", t);
            return true;
        }
    }

    /** From the classic comment box's static post method, with the text and post it holds. */
    public static boolean allowComment(Object composer, CharSequence text, Object media, Class<?> owner, String name) {
        try {
            List<Object> state = Arrays.asList(text == null ? null : text.toString(), media);
            Boolean replay = replayVerdict(composer, state);
            if (replay != null) return replay;
            if (composer == null || isBlank(text) || !Pref.confirmComment()) return true;
            return !ask(null, COMMENT, str("spookd_confirm_comment_title"), composer, state,
                    invoker(owner, name, null, new Object[]{composer}));
        } catch (Throwable t) {
            Logger.printException(() -> "allowComment failure", t);
            return true;
        }
    }

    /**
     * From the comment sheet's static send, before it touches the composer; {@code refs} holds its
     * reference arguments: the activity, the composer, the text at 10 and the post at 7.
     */
    public static boolean allowCommentSend(Object[] refs, boolean last, Class<?> owner, String name) {
        try {
            Object composer = refs[1];
            Object text = refs[10];
            List<Object> state = Arrays.asList(text, refs[7]);
            Boolean replay = replayVerdict(composer, state);
            if (replay != null) return replay;
            if (composer == null || !(text instanceof String) || isBlank((String) text) || !Pref.confirmComment()) return true;
            Object[] args = Arrays.copyOf(refs, refs.length + 1);
            args[refs.length] = last;
            Runnable send = invoker(owner, name, null, args);
            Activity activity = refs[0] instanceof Activity ? (Activity) refs[0] : null;
            return !ask(null, COMMENT, str("spookd_confirm_comment_title"), composer, state, () -> {
                if (activity == null || !activity.isFinishing()) send.run();
            });
        } catch (Throwable t) {
            Logger.printException(() -> "allowCommentSend failure", t);
            return true;
        }
    }

    /** From the follow button's click handler, once it knows the account's follow status and privacy. */
    public static boolean allowUnfollow(Object[] refs, Object status, Object privacy, String name) {
        try {
            Object controller = refs[2];
            List<Object> state = Arrays.asList(status, privacy);
            Boolean replay = replayVerdict(controller, state);
            if (replay != null) return replay;
            String action = unfollowAction(enumName(status), enumName(privacy));
            if (controller == null || action == null || !Pref.confirmUnfollow()) return true;
            return !ask(null, action, str("spookd_confirm_" + action + "_title"), controller, state,
                    invoker(controller.getClass(), name, null, refs));
        } catch (Throwable t) {
            Logger.printException(() -> "allowUnfollow failure", t);
            return true;
        }
    }

    /** From the bouncy UFI button's click, which the feed repost button and the story heart share. */
    public static boolean allowUfiClick(View button) {
        try {
            if (repostsUfiId == 0) repostsUfiId = idOf(button, "reposts_ufi_icon");
            if (storyLikeId == 0) storyLikeId = idOf(button, "toolbar_like_button");
            int id = button.getId();
            boolean repost = id == repostsUfiId;
            // An untracked heart could approve a story other than the one asked about.
            if (!repost && (id != storyLikeId || !storyLikePresenters.containsKey(button))) return true;
            // Selected means reposted or liked already, so the tap undoes it.
            List<Object> state = Arrays.asList(bindCount(button), button.isSelected(),
                    repost ? null : storyItemOf(storyLikePresenters.get(button)));
            Boolean replay = replayVerdict(button, state);
            if (replay != null) return replay;
            if (button.isSelected() || !(repost ? Pref.confirmRepost() : Pref.confirmStoryLike())) return true;
            String title = str(repost ? "spookd_confirm_repost_title" : "spookd_confirm_story_like_title");
            return !ask(button, repost ? REPOST : LIKE, title, button, state, button::performClick);
        } catch (Throwable t) {
            Logger.printException(() -> "allowUfiClick failure", t);
            return true;
        }
    }

    /** From the story heart component's constructor: the presenter whose story the heart likes. */
    public static void rememberStoryLikePresenter(View button, Object presenter) {
        try {
            storyLikePresenters.put(button, presenter);
            if (!storyModels.containsKey(presenter)) storyModels.put(presenter, null);
        } catch (Throwable t) {
            Logger.printException(() -> "rememberStoryLikePresenter failure", t);
        }
    }

    /** From every story viewer presenter's bind; only the story heart's presenters are kept. */
    public static void onStoryItemBound(Object presenter, Object model) {
        try {
            if (storyModels.containsKey(presenter)) storyModels.put(presenter, model);
        } catch (Throwable t) {
            Logger.printException(() -> "onStoryItemBound failure", t);
        }
    }

    /** The story {@code presenter} shows: its bound model's ReelItem, else the model itself. */
    static Object storyItemOf(Object presenter) {
        Object model = storyModels.get(presenter);
        if (model == null) return null;
        try {
            for (Field field : model.getClass().getDeclaredFields()) {
                if (field.getType().getName().equals("com.instagram.model.reels.ReelItem")) {
                    field.setAccessible(true);
                    return field.get(model);
                }
            }
        } catch (IllegalAccessException e) {
            Logger.printException(() -> "storyItemOf failure", e);
        }
        return model;
    }

    /** From the story reply bar's quick reaction send; {@code refs} holds the handler and its six arguments. */
    public static boolean allowStoryReaction(Object[] refs, Class<?> owner, String name) {
        try {
            Object emoji = refs[5];
            List<Object> state = Arrays.asList(emoji, refs[4]);
            Boolean replay = replayVerdict(refs[0], state);
            if (replay != null) return replay;
            if (refs[0] == null || !(emoji instanceof String) || ((String) emoji).isEmpty() || !Pref.confirmStoryLike()) return true;
            return !ask(null, REACTION, str("spookd_confirm_reaction_title", emoji), refs[0], state,
                    invoker(owner, name, refs[0], Arrays.copyOfRange(refs, 1, refs.length)));
        } catch (Throwable t) {
            Logger.printException(() -> "allowStoryReaction failure", t);
            return true;
        }
    }

    /** From the chat header's static button click; {@code refs} holds the view, the button and the header. */
    public static boolean allowCall(Object[] refs, Object type, Class<?> owner, String name) {
        try {
            View view = (View) refs[0];
            List<Object> state = Arrays.asList(type);
            Boolean replay = replayVerdict(view, state);
            if (replay != null) return replay;
            String title = callTitle(enumName(type));
            if (view == null || title == null || !Pref.confirmCall()) return true;
            return !ask(view, CALL, str(title), view, state, invoker(owner, name, null, refs));
        } catch (Throwable t) {
            Logger.printException(() -> "allowCall failure", t);
            return true;
        }
    }

    /** From the follow requests list's row click; only its Accept button is asked. */
    public static boolean allowFollowRequestClick(View button) {
        try {
            if (followRequestAcceptId == 0) followRequestAcceptId = idOf(button, "row_requested_user_accept");
            if (button.getId() != followRequestAcceptId) return true;
            List<Object> state = Arrays.asList(button.isEnabled());
            Boolean replay = replayVerdict(button, state);
            if (replay != null) return replay;
            if (!Pref.confirmFollowRequest()) return true;
            return !ask(button, FOLLOW_REQUEST, str("spookd_confirm_follow_request_title"), button, state, button::performClick);
        } catch (Throwable t) {
            Logger.printException(() -> "allowFollowRequestClick failure", t);
            return true;
        }
    }

    /** From the static accept of a follow request; {@code refs} holds the activity and the next four arguments. */
    public static boolean allowFollowRequest(Object[] refs, boolean last, Class<?> owner, String name) {
        try {
            // An approved replay, such as the requests list's Accept button, was asked already.
            if (pendingToken != null || !Pref.confirmFollowRequest()) return true;
            Object[] args = Arrays.copyOf(refs, refs.length + 1);
            args[refs.length] = last;
            Runnable accept = invoker(owner, name, null, args);
            Activity activity = refs[0] instanceof Activity ? (Activity) refs[0] : null;
            return !ask(null, FOLLOW_REQUEST, str("spookd_confirm_follow_request_title"), refs[4], Arrays.asList(refs[4]), () -> {
                if (activity == null || !activity.isFinishing()) accept.run();
            });
        } catch (Throwable t) {
            Logger.printException(() -> "allowFollowRequest failure", t);
            return true;
        }
    }

    /** What a tap on a follow button with this status asks, or null when it follows or Instagram asks itself. */
    static String unfollowAction(String status, String privacy) {
        if ("FOLLOWSTATUSREQUESTED".equals(status)) return WITHDRAW;
        // Instagram shows its own dialog before unfollowing an account that is not public.
        return "FOLLOWSTATUSFOLLOWING".equals(status) && "PrivacyStatusPublic".equals(privacy) ? UNFOLLOW : null;
    }

    /** The dialog title for a chat header button type, or null when the button starts no call. */
    static String callTitle(String buttonType) {
        if ("AUDIO_CALL".equals(buttonType)) return "spookd_confirm_audio_call_title";
        if ("VIDEO_CALL".equals(buttonType)) return "spookd_confirm_video_call_title";
        return null;
    }

    private static String enumName(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : null;
    }

    private static int idOf(View view, String name) {
        return ResourceUtils.getIdentifier(view.getContext(), ResourceType.ID, name);
    }

    static boolean isFollow(String textType) {
        return "FOLLOW".equals(textType) || "FOLLOW_BACK".equals(textType);
    }

    static boolean isReaction(Object[] reelLikeRefs) {
        return reelLikeRefs[8] != null;
    }

    static boolean isBlank(CharSequence text) {
        return text == null || text.toString().trim().isEmpty();
    }

    /**
     * While an approved action replays: true when {@code token} still looks as it did when asked,
     * false when it changed, so the replay is dropped. Null when this call is not that replay.
     */
    static Boolean replayVerdict(Object token, List<Object> state) {
        if (token == null || token != pendingToken) return null;
        return sameState(pendingState, state);
    }

    /** Text, flags and counters compare by value; accounts, posts and views by identity. */
    static boolean sameState(List<Object> before, List<Object> now) {
        if (before == null || now == null || before.size() != now.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            Object a = before.get(i);
            Object b = now.get(i);
            if (a == b) continue;
            boolean byValue = a instanceof String || a instanceof Boolean || a instanceof Integer;
            if (!byValue || !a.equals(b)) return false;
        }
        return true;
    }

    /** Runs {@code action} once, letting {@code token} through only while it matches {@code state}. */
    static void runConfirmed(Object token, List<Object> state, Runnable action) {
        pendingToken = token;
        pendingState = state;
        try {
            action.run();
        } catch (Throwable t) {
            Logger.printException(() -> "Confirmed action failed", t);
        } finally {
            pendingToken = null;
            pendingState = null;
        }
    }

    private static int bindCount(View button) {
        Integer count = bindCounts.get(button);
        return count == null ? 0 : count;
    }

    /** Shows the dialog; false when it cannot be shown, so the action goes ahead. */
    private static boolean ask(View anchor, String action, String title, Object token, List<Object> state, Runnable rerun) {
        Activity activity = activityOf(anchor == null ? null : anchor.getContext());
        if (activity == null || activity.isFinishing()) return false;
        AlertDialog dialog = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setTitle(title)
                .setNegativeButton(str("spookd_cancel"), null)
                .setPositiveButton(str("spookd_confirm_" + action + "_action"), (shown, which) -> {
                    // A recycled or closed screen means the prompt no longer matches what is shown.
                    if (anchor == null || anchor.isAttachedToWindow()) runConfirmed(token, state, rerun);
                })
                .create();
        // Leaving the screen, where Reels and the comment sheet may move on, withdraws the question.
        Application application = activity.getApplication();
        CloseOnPause closeOnPause = new CloseOnPause(activity, dialog);
        application.registerActivityLifecycleCallbacks(closeOnPause);
        dialog.setOnDismissListener(shown -> application.unregisterActivityLifecycleCallbacks(closeOnPause));
        dialog.show();
        return true;
    }

    private static final class CloseOnPause implements Application.ActivityLifecycleCallbacks {
        private final Activity activity;
        private final AlertDialog dialog;

        CloseOnPause(Activity activity, AlertDialog dialog) {
            this.activity = activity;
            this.dialog = dialog;
        }

        @Override
        public void onActivityPaused(Activity paused) {
            if (paused == activity) dialog.dismiss();
        }

        @Override
        public void onActivityCreated(Activity created, Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity started) {
        }

        @Override
        public void onActivityResumed(Activity resumed) {
        }

        @Override
        public void onActivityStopped(Activity stopped) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity saved, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity destroyed) {
        }
    }

    private static Runnable invoker(Class<?> owner, String name, Object receiver, Object[] args) throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == args.length) {
                return call(method, receiver, args);
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    /** The feed like handler's (Media, state, String, Function0, int) method on {@code owner}. */
    private static Method feedLikeMethod(Class<?> owner) throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 5 && method.getReturnType() == void.class
                    && types[0].getName().equals("com.instagram.feed.media.Media") && types[2] == String.class
                    && types[3].getName().equals("kotlin.jvm.functions.Function0") && types[4] == int.class) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + " feed like method");
    }

    private static Runnable call(Method method, Object receiver, Object[] args) {
        method.setAccessible(true);
        return () -> {
            try {
                method.invoke(receiver, args);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return Utils.getActivity();
    }
}
