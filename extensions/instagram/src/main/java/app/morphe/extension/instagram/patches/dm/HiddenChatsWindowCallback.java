/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import android.app.Activity;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

import app.morphe.extension.shared.Logger;

/** Passes every window event on to the window's own callback, and shows each touch to Hidden Chats afterwards. */
final class HiddenChatsWindowCallback implements Window.Callback {
    private final Activity activity;
    final Window.Callback target;

    HiddenChatsWindowCallback(Activity activity, Window.Callback target) {
        this.activity = activity;
        this.target = target;
    }

    /** The callback this one wraps, or the given one when it is not ours. */
    static Window.Callback unwrap(Window.Callback current) {
        return current instanceof HiddenChatsWindowCallback ? ((HiddenChatsWindowCallback) current).target : current;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        boolean handled = target.dispatchTouchEvent(event);
        try {
            HiddenChats.onTouch(activity, event);
        } catch (Throwable t) {
            Logger.printException(() -> "HiddenChats touch failed", t);
        }
        return handled;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) { return target.dispatchKeyEvent(event); }
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) { return target.dispatchKeyShortcutEvent(event); }
    @Override public boolean dispatchTrackballEvent(MotionEvent event) { return target.dispatchTrackballEvent(event); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return target.dispatchGenericMotionEvent(event); }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return target.dispatchPopulateAccessibilityEvent(event);
    }

    @Override public View onCreatePanelView(int featureId) { return target.onCreatePanelView(featureId); }
    @Override public boolean onCreatePanelMenu(int featureId, Menu menu) { return target.onCreatePanelMenu(featureId, menu); }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return target.onPreparePanel(featureId, view, menu);
    }

    @Override public boolean onMenuOpened(int featureId, Menu menu) { return target.onMenuOpened(featureId, menu); }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return target.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        target.onWindowAttributesChanged(attrs);
    }

    @Override public void onContentChanged() { target.onContentChanged(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { target.onWindowFocusChanged(hasFocus); }
    @Override public void onAttachedToWindow() { target.onAttachedToWindow(); }
    @Override public void onDetachedFromWindow() { target.onDetachedFromWindow(); }
    @Override public void onPanelClosed(int featureId, Menu menu) { target.onPanelClosed(featureId, menu); }
    @Override public boolean onSearchRequested() { return target.onSearchRequested(); }
    @Override public boolean onSearchRequested(SearchEvent event) { return target.onSearchRequested(event); }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        return target.onWindowStartingActionMode(callback);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
        return target.onWindowStartingActionMode(callback, type);
    }

    @Override public void onActionModeStarted(ActionMode mode) { target.onActionModeStarted(mode); }
    @Override public void onActionModeFinished(ActionMode mode) { target.onActionModeFinished(mode); }

    @Override
    public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
        target.onProvideKeyboardShortcuts(data, menu, deviceId);
    }

    @Override public void onPointerCaptureChanged(boolean hasCapture) { target.onPointerCaptureChanged(hasCapture); }
}
