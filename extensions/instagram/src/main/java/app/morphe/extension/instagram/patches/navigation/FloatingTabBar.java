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
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
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
    private static ValueAnimator slide;
    private static TabBarScrollDecision decision;
    private static WeakReference<Activity> watched = new WeakReference<>(null);
    private static WeakReference<View> tabBar = new WeakReference<>(null);
    private static WeakReference<View> tabBarShadow = new WeakReference<>(null);
    private static WeakReference<View> feed = new WeakReference<>(null);
    private static final Rect visibleRect = new Rect();

    private static final ViewTreeObserver.OnGlobalLayoutListener layoutListener = FloatingTabBar::onLayout;
    private static final ViewTreeObserver.OnScrollChangedListener treeScrollListener = FloatingTabBar::onTreeScrolled;
    private static final View.OnScrollChangeListener feedScrollListener =
            (view, scrollX, scrollY, oldScrollX, oldScrollY) -> onFeedScrolled(view, scrollY - oldScrollY);
    private static final View.OnAttachStateChangeListener feedAttachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }

        @Override public void onViewDetachedFromWindow(View view) {
            try {
                if (view == feed.get()) showNow();
            } catch (Throwable t) {
                Logger.printException(() -> "FloatingTabBar detach failed", t);
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
                        Logger.printException(() -> "FloatingTabBar resume failed", t);
                    }
                }

                @Override public void onActivityDestroyed(Activity activity) {
                    try {
                        if (activity == watched.get()) unwatch();
                    } catch (Throwable t) {
                        Logger.printException(() -> "FloatingTabBar destroy failed", t);
                    }
                }

                @Override public void onActivityCreated(Activity a, Bundle b) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityPaused(Activity a) { }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            });
        } catch (Throwable t) {
            Logger.printException(() -> "FloatingTabBar init failed", t);
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
            activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        }
        // Settings open in another activity, so a change there is picked up here.
        hideOnScroll = Pref.floatingTabBarHideOnScroll();
        View bar = activity.findViewById(tabBarId);
        View shadow = tabBarShadowId == 0 ? null : activity.findViewById(tabBarShadowId);
        tabBar = new WeakReference<>(bar);
        tabBarShadow = new WeakReference<>(shadow);
        showNow();
        onLayout();
    }

    private static void unwatch() {
        showNow();
        Activity activity = watched.get();
        if (activity != null) {
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            observer.removeOnGlobalLayoutListener(layoutListener);
            observer.removeOnScrollChangedListener(treeScrollListener);
        }
        watchingTreeScroll = false;
        track(null);
        watched = new WeakReference<>(null);
        tabBar = new WeakReference<>(null);
        tabBarShadow = new WeakReference<>(null);
    }

    // Layout passes catch the feed being created, replaced, or hidden behind another screen of this activity.
    private static void onLayout() {
        try {
            Activity activity = watched.get();
            if (activity == null) return;
            View list = feed.get();
            if (list == null || !list.isAttachedToWindow()) {
                View pager = activity.findViewById(pagerId);
                list = pager != null && pager.isShown() ? findFeed(pager) : null;
                if (list != null) track(list);
            }
            if (hidden && (list == null || !onScreen(list))) showNow();
        } catch (Throwable t) {
            Logger.printException(() -> "FloatingTabBar layout check failed", t);
        }
    }

    // Registered only while the bar is away, so swiping to another tab brings it back.
    private static void onTreeScrolled() {
        try {
            View list = feed.get();
            if (list == null || !onScreen(list)) showNow();
        } catch (Throwable t) {
            Logger.printException(() -> "FloatingTabBar scroll check failed", t);
        }
    }

    private static void onFeedScrolled(View view, int dy) {
        try {
            if (view != feed.get() || !hideOnScroll) return;
            setHidden(decision.onScrolled(dy, !view.canScrollVertically(-1)));
        } catch (Throwable t) {
            Logger.printException(() -> "FloatingTabBar feed scroll failed", t);
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

    // Pages of the tab pager stay attached and shown while swiped away, so isShown() alone can't tell.
    private static boolean onScreen(View view) {
        return view.isShown() && view.getGlobalVisibleRect(visibleRect) && visibleRect.width() * 2 > view.getWidth();
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
            translate(tabBar.get(), value);
            translate(tabBarShadow.get(), value);
        } catch (Throwable t) {
            Logger.printException(() -> "FloatingTabBar move failed", t);
        }
    }

    // Moves the view by the given share of its distance to the bottom edge of its parent.
    private static void translate(View view, float value) {
        if (view == null || !(view.getParent() instanceof View)) return;
        view.setTranslationY(value * (((View) view.getParent()).getHeight() - view.getTop()));
    }
}
