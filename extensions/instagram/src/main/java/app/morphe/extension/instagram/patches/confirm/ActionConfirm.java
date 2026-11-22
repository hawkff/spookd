/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.confirm;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Asks before a like, follow, repost or comment goes through. Every entry point runs on the main
 * thread from Instagram's own click handlers and returns true, letting the action run, on any failure.
 */
@SuppressWarnings("unused")
public final class ActionConfirm {
    static final String LIKE = "like";
    static final String FOLLOW = "follow";
    static final String REPOST = "repost";
    static final String COMMENT = "comment";

    // Set only while an approved action runs again: what it was about, and how it looked when asked.
    private static Object pendingToken;
    private static List<Object> pendingState;
    private static final WeakHashMap<View, String> followNames = new WeakHashMap<>();
    // Counts click listener changes, so a rebound button no longer matches an earlier prompt.
    private static final WeakHashMap<View, Integer> bindCounts = new WeakHashMap<>();
    private static boolean stagedFeedLiked;

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
                    ? str("piko_confirm_follow_title") : str("piko_confirm_follow_user_title", name);
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
            return !ask(null, LIKE, str("piko_confirm_like_title"), media, state, call(feedLikeMethod(handler.getClass()), handler, args));
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
            return !ask(view, LIKE, str("piko_confirm_like_title"), view, state, invoker(owner, name, refs[0], args));
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
            return !ask(view, REPOST, str("piko_confirm_repost_title"), view, state, invoker(owner, name, null, args));
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
            return !ask(null, COMMENT, str("piko_confirm_comment_title"), composer, state,
                    invoker(owner, name, null, new Object[]{composer}));
        } catch (Throwable t) {
            Logger.printException(() -> "allowComment failure", t);
            return true;
        }
    }

    /**
     * From the comment sheet's send action; {@code refs} holds the sheet's model and its three
     * arguments, the second being the comment, whose text is {@code text}.
     */
    public static boolean allowCommentSend(Object[] refs, String text, Class<?> owner, String name) {
        try {
            Object comment = refs[2];
            List<Object> state = Arrays.asList(text);
            Boolean replay = replayVerdict(comment, state);
            if (replay != null) return replay;
            if (comment == null || isBlank(text) || !Pref.confirmComment()) return true;
            return !ask(null, COMMENT, str("piko_confirm_comment_title"), comment, state,
                    invoker(owner, name, refs[0], Arrays.copyOfRange(refs, 1, refs.length)));
        } catch (Throwable t) {
            Logger.printException(() -> "allowCommentSend failure", t);
            return true;
        }
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
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setTitle(title)
                .setNegativeButton(str("piko_cancel"), null)
                .setPositiveButton(str("piko_confirm_" + action + "_action"), (dialog, which) -> {
                    // A recycled or closed screen means the prompt no longer matches what is shown.
                    if (anchor == null || anchor.isAttachedToWindow()) runConfirmed(token, state, rerun);
                })
                .show();
        return true;
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
