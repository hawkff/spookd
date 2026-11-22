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
import android.content.res.Resources;
import android.view.View;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
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

    // Set only while a confirmed action runs again, so its own checks let it through.
    private static Object bypass;
    private static final WeakHashMap<View, String> followNames = new WeakHashMap<>();

    private ActionConfirm() {
    }

    /** From the follow button's click; {@code textType} is the label it shows. */
    public static boolean allowFollowClick(View button, Object textType) {
        try {
            if (isBypassed(button)) return true;
            String type = textType instanceof Enum ? ((Enum<?>) textType).name() : null;
            if (!isFollow(type) || !Pref.confirmFollow()) return true;
            String name = followNames.get(button);
            String title = name == null || name.isEmpty()
                    ? str("piko_confirm_follow_title") : str("piko_confirm_follow_user_title", name);
            return !ask(button, FOLLOW, title, button, button::performClick);
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

    /** From a feed like or repost button's click; the button is selected once liked or reposted. */
    public static boolean allowUfiClick(View button) {
        try {
            if (isBypassed(button) || button.getId() == View.NO_ID) return true;
            String idName;
            try {
                idName = button.getResources().getResourceEntryName(button.getId());
            } catch (Resources.NotFoundException e) {
                return true;
            }
            String action = ufiAction(idName, button.isSelected());
            if (action == null || !isEnabled(action)) return true;
            return !ask(button, action, str("piko_confirm_" + action + "_title"), button, button::performClick);
        } catch (Throwable t) {
            Logger.printException(() -> "allowUfiClick failure", t);
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
            if (view == null || isBypassed(view) || isReaction(refs) || !enabled || liked || !Pref.confirmLike()) return true;
            Object[] args = Arrays.copyOfRange(refs, 1, refs.length + 2);
            args[args.length - 2] = enabled;
            args[args.length - 1] = liked;
            return !ask(view, LIKE, str("piko_confirm_like_title"), view, invoker(owner, name, refs[0], args));
        } catch (Throwable t) {
            Logger.printException(() -> "allowReelLike failure", t);
            return true;
        }
    }

    /** From the Reels repost handler, a static method; the sixth argument is set when undoing a repost. */
    public static boolean allowReelRepost(Object[] refs, int last, Class<?> owner, String name) {
        try {
            View view = (View) refs[0];
            if (view == null || isBypassed(view) || refs[5] != null || !Pref.confirmRepost()) return true;
            Object[] args = Arrays.copyOf(refs, refs.length + 1);
            args[refs.length] = last;
            return !ask(view, REPOST, str("piko_confirm_repost_title"), view, invoker(owner, name, null, args));
        } catch (Throwable t) {
            Logger.printException(() -> "allowReelRepost failure", t);
            return true;
        }
    }

    /** From the comment composer's static post method; {@code text} is what the composer holds. */
    public static boolean allowComment(Object composer, CharSequence text, Class<?> owner, String name) {
        try {
            if (composer == null || isBypassed(composer) || isBlank(text) || !Pref.confirmComment()) return true;
            return !ask(null, COMMENT, str("piko_confirm_comment_title"), composer,
                    invoker(owner, name, null, new Object[]{composer}));
        } catch (Throwable t) {
            Logger.printException(() -> "allowComment failure", t);
            return true;
        }
    }

    static boolean isFollow(String textType) {
        return "FOLLOW".equals(textType) || "FOLLOW_BACK".equals(textType);
    }

    /** The action a feed button starts, or null when it undoes one or is another button. */
    static String ufiAction(String idName, boolean selected) {
        if (selected) return null;
        if ("row_feed_button_like".equals(idName)) return LIKE;
        if ("reposts_ufi_icon".equals(idName)) return REPOST;
        return null;
    }

    static boolean isReaction(Object[] reelLikeRefs) {
        return reelLikeRefs[8] != null;
    }

    static boolean isBlank(CharSequence text) {
        return text == null || text.toString().trim().isEmpty();
    }

    static boolean isBypassed(Object token) {
        return token != null && bypass == token;
    }

    /** Runs {@code action} once with {@code token} let through; the bypass ends with it. */
    static void runConfirmed(Object token, Runnable action) {
        bypass = token;
        try {
            action.run();
        } catch (Throwable t) {
            Logger.printException(() -> "Confirmed action failed", t);
        } finally {
            bypass = null;
        }
    }

    private static boolean isEnabled(String action) {
        return LIKE.equals(action) ? Pref.confirmLike() : REPOST.equals(action) && Pref.confirmRepost();
    }

    /** Shows the dialog; false when it cannot be shown, so the action goes ahead. */
    private static boolean ask(View anchor, String action, String title, Object token, Runnable rerun) {
        Activity activity = activityOf(anchor == null ? null : anchor.getContext());
        if (activity == null || activity.isFinishing()) return false;
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setTitle(title)
                .setNegativeButton(str("piko_cancel"), null)
                .setPositiveButton(str("piko_confirm_" + action + "_action"), (dialog, which) -> runConfirmed(token, rerun))
                .show();
        return true;
    }

    private static Runnable invoker(Class<?> owner, String name, Object receiver, Object[] args) throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
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
        throw new NoSuchMethodException(owner.getName() + "." + name);
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
