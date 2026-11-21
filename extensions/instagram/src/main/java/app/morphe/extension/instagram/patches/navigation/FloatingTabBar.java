/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

/** Slides the tab bar off the bottom of the screen while the home feed scrolls down, and back when it scrolls up. */
public final class FloatingTabBar {
    private static final String MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";
    // The home feed's own list class, so lists of the other tabs in the same pager are left alone.
    private static final String FEED_LIST = "com.instagram.common.ui.widget.recyclerview.MainFeedRecyclerView";
    private static final long SLIDE_MILLIS = 200;

    private static boolean initialized;
    private static boolean idsResolved;
    private static int tabBarId, tabBarShadowId, pagerId;
    private static boolean hideOnScroll;
    private static boolean hidden;
    private static boolean watchingTreeScroll;
    // 0 is in place, 1 is fully below the screen.
    private static float offset;
    // Distances to the parent's bottom edge that the current offset was applied with.
    private static int barDistance, shadowDistance;
    private static ValueAnimator slide;
    private static TabBarScrollDecision decision;
    private static WeakReference<Activity> watched = new WeakReference<>(null);
    private static WeakReference<View> tabBar = new WeakReference<>(null);
    private static WeakReference<View> tabBarShadow = new WeakReference<>(null);
    private static WeakReference<View> feed = new WeakReference<>(null);
    private static final Rect feedRect = new Rect();
    private static final Rect coverRect = new Rect();
    private static final int MAX_COVER_DEPTH = 6;
    private static final int MAX_COVER_VISITS = 24;
    private static int coverVisits;

    private static final ViewTreeObserver.OnGlobalLayoutListener layoutListener = FloatingTabBar::onLayout;
    private static final ViewTreeObserver.OnScrollChangedListener treeScrollListener = FloatingTabBar::onTreeScrolled;
    private static final ViewTreeObserver.OnWindowFocusChangeListener focusListener = FloatingTabBar::onWindowFocus;
    private static final View.OnScrollChangeListener feedScrollListener =
            (view, scrollX, scrollY, oldScrollX, oldScrollY) -> onFeedScrolled(view, scrollY - oldScrollY);
    private static final View.OnAttachStateChangeListener feedAttachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }

        @Override public void onViewDetachedFromWindow(View view) {
            try {
                if (view == feed.get()) showNow();
            } catch (Throwable t) {
                log("FloatingTabBar detach failed", t);
            }
        }
    };

    private FloatingTabBar() {
    }

    /** Called once from the application init hook. */
    public static void init() {
        try {
            if (initialized || !Pref.floatingTabBar()) return;
            initialized = true;
            decision = new TabBarScrollDecision(Dim.dp24, Dim.dp8);
            Application app = (Application) Utils.getContext().getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) {
                    try {
                        if (MAIN_ACTIVITY.equals(activity.getClass().getName())) watch(activity);
                    } catch (Throwable t) {
                        log("FloatingTabBar resume failed", t);
                    }
                }

                @Override public void onActivityDestroyed(Activity activity) {
                    try {
                        if (activity == watched.get()) unwatch();
                    } catch (Throwable t) {
                        log("FloatingTabBar destroy failed", t);
                    }
                }

                @Override public void onActivityCreated(Activity a, Bundle b) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityPaused(Activity a) { }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            });
        } catch (Throwable t) {
            log("FloatingTabBar init failed", t);
        }
    }

    private static void watch(Activity activity) {
        if (!idsResolved) {
            idsResolved = true;
            tabBarId = ResourceUtils.getIdentifier(activity, ResourceType.ID, "tab_bar");
            tabBarShadowId = ResourceUtils.getIdentifier(activity, ResourceType.ID, "tab_bar_shadow");
            pagerId = ResourceUtils.getIdentifier(activity, ResourceType.ID, "swipeable_tab_view_pager");
        }
        if (tabBarId == 0 || pagerId == 0) return;
        if (activity != watched.get()) {
            unwatch();
            watched = new WeakReference<>(activity);
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            observer.addOnGlobalLayoutListener(layoutListener);
            observer.addOnWindowFocusChangeListener(focusListener);
        }
        // Settings open in another activity, so a change there is picked up here.
        hideOnScroll = Pref.floatingTabBarHideOnScroll();
        showNow();
        findBar(activity);
        onLayout();
    }

    private static void unwatch() {
        showNow();
        Activity activity = watched.get();
        if (activity != null) {
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            observer.removeOnGlobalLayoutListener(layoutListener);
            observer.removeOnWindowFocusChangeListener(focusListener);
            observer.removeOnScrollChangedListener(treeScrollListener);
        }
        watchingTreeScroll = false;
        track(null);
        watched = new WeakReference<>(null);
        tabBar = new WeakReference<>(null);
        tabBarShadow = new WeakReference<>(null);
    }

    private static void findBar(Activity activity) {
        View bar = activity.findViewById(tabBarId);
        tabBar = new WeakReference<>(bar);
        View shadow = tabBarShadowId == 0 ? null : activity.findViewById(tabBarShadowId);
        tabBarShadow = new WeakReference<>(shadow);
    }

    // Layout passes catch the feed or the bar being created, replaced, resized, or covered by another screen of this activity.
    private static void onLayout() {
        try {
            Activity activity = watched.get();
            if (activity == null) return;
            View bar = tabBar.get();
            View shadow = tabBarShadow.get();
            if (bar == null || !bar.isAttachedToWindow() || (shadow != null && !shadow.isAttachedToWindow())) {
                // The old views get their own offset cleared, and the new bar starts in place.
                showNow();
                findBar(activity);
            } else if (shadow == null && tabBarShadowId != 0) {
                // The shadow can appear after the bar; one found while the bar is away puts it back through the distance check.
                View found = activity.findViewById(tabBarShadowId);
                if (found != null) tabBarShadow = new WeakReference<>(found);
            }
            if (offset != 0f && (distance(tabBar.get()) != barDistance || distance(tabBarShadow.get()) != shadowDistance)) {
                showNow();
            }

            View list = feed.get();
            if (list == null || !list.isAttachedToWindow()) {
                View pager = activity.findViewById(pagerId);
                list = pager != null && pager.isShown() ? findFeed(pager) : null;
                if (list != null) track(list);
            }
            if (hidden && (list == null || !feedInFront(list))) showNow();
        } catch (Throwable t) {
            log("FloatingTabBar layout check failed", t);
        }
    }

    // Registered only while the bar is away, so swiping to another tab brings it back.
    private static void onTreeScrolled() {
        try {
            View list = feed.get();
            if (list == null || !feedInFront(list)) showNow();
        } catch (Throwable t) {
            log("FloatingTabBar scroll check failed", t);
        }
    }

    // A dialog or sheet in its own window takes the focus from the activity.
    private static void onWindowFocus(boolean hasFocus) {
        try {
            if (!hasFocus) showNow();
        } catch (Throwable t) {
            log("FloatingTabBar focus check failed", t);
        }
    }

    private static void onFeedScrolled(View view, int dy) {
        try {
            if (view != feed.get() || !hideOnScroll) return;
            if (!feedInFront(view)) {
                showNow();
                return;
            }
            setHidden(decision.onScrolled(dy, !view.canScrollVertically(-1)));
        } catch (Throwable t) {
            log("FloatingTabBar feed scroll failed", t);
        }
    }

    private static View findFeed(View view) {
        if (FEED_LIST.equals(view.getClass().getName())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findFeed(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static void track(View list) {
        View old = feed.get();
        if (old == list) return;
        if (old != null) {
            old.setOnScrollChangeListener(null);
            old.removeOnAttachStateChangeListener(feedAttachListener);
        }
        feed = new WeakReference<>(list);
        if (list != null) {
            list.setOnScrollChangeListener(feedScrollListener);
            list.addOnAttachStateChangeListener(feedAttachListener);
        }
    }

    // Tab pages stay shown while swiped away, and a screen drawn over the pager changes neither visibility nor clipping,
    // so the feed must be mostly on screen and not covered by any view drawn after it up to the content root.
    private static boolean feedInFront(View list) {
        // A dialog or sheet in its own window can't be seen in this tree, but it takes the focus.
        if (!list.hasWindowFocus() || !list.isShown()
                || !list.getGlobalVisibleRect(feedRect) || feedRect.width() * 2 <= list.getWidth()) {
            return false;
        }
        long half = (long) feedRect.width() * feedRect.height() / 2;
        View child = list;
        while (child.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) child.getParent();
            for (int i = parent.indexOfChild(child) + 1; i < parent.getChildCount(); i++) {
                if (covers(parent.getChildAt(i), half)) return false;
            }
            if (parent.getId() == android.R.id.content) break;
            child = parent;
        }
        return true;
    }

    // Siblings are compared by child index only; elevation and custom drawing order are not modeled.
    private static boolean covers(View view, long half) {
        if (!visible(view) || !view.getGlobalVisibleRect(coverRect) || !coverRect.intersect(feedRect)
                || (long) coverRect.width() * coverRect.height() <= half) {
            return false;
        }
        coverVisits = 0;
        return notEmpty(view, 0);
    }

    // Errs toward true, which only keeps the bar visible; empty containers and ones holding only stubs are false.
    private static boolean notEmpty(View view, int depth) {
        if (++coverVisits > MAX_COVER_VISITS || depth > MAX_COVER_DEPTH) return true;
        if (!(view instanceof ViewGroup)) return true;
        Drawable background = view.getBackground();
        if (background != null && !(background instanceof ColorDrawable && ((ColorDrawable) background).getAlpha() == 0)) {
            return true;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!visible(child)) {
                // Skipped children count too, so a container full of hidden views cannot run long.
                if (++coverVisits > MAX_COVER_VISITS) return true;
                continue;
            }
            if (notEmpty(child, depth + 1)) return true;
        }
        return false;
    }

    private static boolean visible(View view) {
        return view.getVisibility() == View.VISIBLE && view.getAlpha() != 0f && !(view instanceof ViewStub);
    }

    private static void setHidden(boolean hide) {
        if (hide == hidden) return;
        hidden = hide;
        watchTreeScroll(hide);
        if (slide != null) slide.cancel();
        slide = ValueAnimator.ofFloat(offset, hide ? 1f : 0f);
        slide.setDuration(SLIDE_MILLIS);
        slide.addUpdateListener(animator -> moveTo((float) animator.getAnimatedValue()));
        slide.start();
    }

    /** Puts the bar back at once; does nothing if it was never moved. */
    private static void showNow() {
        decision.reset();
        if (!hidden && offset == 0f && slide == null) return;
        hidden = false;
        watchTreeScroll(false);
        if (slide != null) slide.cancel();
        slide = null;
        moveTo(0f);
    }

    private static void watchTreeScroll(boolean watch) {
        Activity activity = watched.get();
        if (watch == watchingTreeScroll || activity == null) return;
        watchingTreeScroll = watch;
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        if (watch) {
            observer.addOnScrollChangedListener(treeScrollListener);
        } else {
            observer.removeOnScrollChangedListener(treeScrollListener);
        }
    }

    private static void moveTo(float value) {
        try {
            offset = value;
            barDistance = translate(tabBar.get(), value);
            shadowDistance = translate(tabBarShadow.get(), value);
        } catch (Throwable t) {
            log("FloatingTabBar move failed", t);
        }
    }

    /** Moves the view by the given share of its distance to the bottom edge of its parent, and returns that distance. */
    private static int translate(View view, float value) {
        int distance = distance(view);
        if (view != null) view.setTranslationY(value * distance);
        return distance;
    }

    private static int distance(View view) {
        if (view == null || !(view.getParent() instanceof View)) return 0;
        return ((View) view.getParent()).getHeight() - view.getTop();
    }

    private static void log(String message, Throwable t) {
        try {
            Logger.printException(() -> message, t);
        } catch (Throwable ignored) {
            // Logging must never throw into Instagram.
        }
    }
}
