/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;

import androidx.annotation.RequiresApi;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

/**
 * Draws the floating tab bar as glass in place of Instagram's pill. The pill stays wrapped with the same insets,
 * so Instagram's color updates still reach it, and it is drawn again if the glass fails.
 */
final class GlassTabBarDrawable extends InsetDrawable {
    private static final float BLUR_RADIUS_DP = 16f;
    private static final float SATURATION = 1.6f;
    private static final int DARK_TINT_ALPHA = 140;
    private static final int LIGHT_TINT_ALPHA = 166;
    private static final int OPAQUE_TINT_ALPHA = 224;
    private static final float RIM_WIDTH_DP = 1f;
    private static final int DARK_RIM_TOP_ALPHA = 72;
    private static final int DARK_RIM_BOTTOM_ALPHA = 16;
    private static final int LIGHT_RIM_TOP_ALPHA = 230;
    private static final int LIGHT_RIM_BOTTOM_ALPHA = 20;
    private static final float SHADOW_RADIUS_DP = 12f;
    private static final float SHADOW_OFFSET_DP = 3f;
    private static final int SHADOW_ALPHA = 56;

    private static final Rect scratch = new Rect();
    private static boolean blurFailed;

    /** Instagram's background, put back when the setting is turned off. */
    final InsetDrawable original;
    private final Rect pill = new Rect();
    private final RectF pillRect = new RectF();
    private final RectF rimRect = new RectF();
    private final Path pillPath = new Path();
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float radius;
    private boolean night, builtNight;
    private boolean systemBlur;
    private boolean broken;
    private Blur blur;

    /** Wraps the pill of Instagram's background with the same insets. */
    static GlassTabBarDrawable wrap(InsetDrawable original) {
        Drawable pill = original.getDrawable();
        // An inset background's padding is its insets plus the wrapped drawable's own padding.
        Rect insets = new Rect();
        original.getPadding(insets);
        if (pill.getPadding(scratch)) {
            insets.left -= scratch.left;
            insets.top -= scratch.top;
            insets.right -= scratch.right;
            insets.bottom -= scratch.bottom;
        }
        return new GlassTabBarDrawable(original, pill, insets);
    }

    private GlassTabBarDrawable(InsetDrawable original, Drawable pill, Rect insets) {
        super(pill, insets.left, insets.top, insets.right, insets.bottom);
        this.original = original;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Dim.dp(RIM_WIDTH_DP));
        shadow.setColor(Color.BLACK);
        shadow.setShadowLayer(Dim.dp(SHADOW_RADIUS_DP), 0f, Dim.dp(SHADOW_OFFSET_DP),
                Color.argb(SHADOW_ALPHA, 0, 0, 0));
    }

    /** Main thread; called from the bar's layout check. */
    void update(boolean night, boolean systemBlur) {
        if (night == this.night && systemBlur == this.systemBlur) return;
        this.night = night;
        this.systemBlur = systemBlur;
        invalidateSelf();
    }

    // Returning no padding keeps View.setBackground from replacing the padding Instagram set on the bar.
    @Override
    public boolean getPadding(Rect padding) {
        padding.setEmpty();
        return false;
    }

    @Override
    public void draw(Canvas canvas) {
        int mode = GlassTabBarMode.OFF;
        int save = canvas.save();
        try {
            mode = GlassTabBarMode.choose(!broken, Build.VERSION.SDK_INT, canvas.isHardwareAccelerated(),
                    systemBlur, blurFailed, FloatingTabBar.barAway());
            if (mode != GlassTabBarMode.OFF) drawGlass(canvas, mode);
        } catch (Throwable t) {
            broken = true;
            mode = GlassTabBarMode.OFF;
            log("GlassTabBarDrawable draw failed, showing Instagram's bar", t);
        } finally {
            canvas.restoreToCount(save);
        }
        if (mode == GlassTabBarMode.OFF) super.draw(canvas);
    }

    private void drawGlass(Canvas canvas, int mode) {
        Rect bounds = getDrawable().getBounds();
        if (bounds.isEmpty()) return;
        if (!bounds.equals(pill) || night != builtNight) build(bounds);

        canvas.save();
        canvas.clipOutPath(pillPath);
        canvas.drawRoundRect(pillRect, radius, radius, shadow);
        canvas.restore();

        boolean blurred = mode == GlassTabBarMode.BLUR && Build.VERSION.SDK_INT >= 31 && drawBlur(canvas);
        int alpha = !blurred ? OPAQUE_TINT_ALPHA : night ? DARK_TINT_ALPHA : LIGHT_TINT_ALPHA;
        int shade = night ? 0 : 255;
        tint.setColor(Color.argb(alpha, shade, shade, shade));
        canvas.drawRoundRect(pillRect, radius, radius, tint);
        canvas.drawRoundRect(rimRect, radius, radius, rim);
    }

    // Only runs when the bounds or the night mode change.
    private void build(Rect bounds) {
        pill.set(bounds);
        pillRect.set(bounds);
        radius = bounds.height() / 2f;
        pillPath.reset();
        pillPath.addRoundRect(pillRect, radius, radius, Path.Direction.CW);
        float half = rim.getStrokeWidth() / 2f;
        rimRect.set(pillRect);
        rimRect.inset(half, half);
        int top = Color.argb(night ? DARK_RIM_TOP_ALPHA : LIGHT_RIM_TOP_ALPHA, 255, 255, 255);
        int bottom = night ? Color.argb(DARK_RIM_BOTTOM_ALPHA, 255, 255, 255) : Color.argb(LIGHT_RIM_BOTTOM_ALPHA, 0, 0, 0);
        rim.setShader(new LinearGradient(0f, pillRect.top, 0f, pillRect.bottom, top, bottom, Shader.TileMode.CLAMP));
        builtNight = night;
    }

    /** Returns false when nothing behind the bar was drawn, so the caller uses the opaque tint. */
    @RequiresApi(31)
    private boolean drawBlur(Canvas canvas) {
        View bar = FloatingTabBar.glassBar(this);
        if (bar == null || !(bar.getParent() instanceof ViewGroup)) return false;
        try {
            if (blur == null) blur = new Blur(Dim.dp(BLUR_RADIUS_DP));
            if (!blur.record(bar, (ViewGroup) bar.getParent(), pill)) return false;
            canvas.save();
            canvas.clipPath(pillPath);
            canvas.drawRenderNode(blur.node);
            canvas.restore();
            return true;
        } catch (Throwable t) {
            blurFailed = true;
            log("GlassTabBarDrawable blur failed, using the tint only", t);
            return false;
        }
    }

    private static void log(String message, Throwable t) {
        try {
            Logger.printException(() -> message, t);
        } catch (Throwable ignored) {
            // Logging must never throw into Instagram.
        }
    }

    @RequiresApi(31)
    private static final class Blur {
        final RenderNode node = new RenderNode("GlassTabBar");

        Blur(float radius) {
            ColorMatrix saturation = new ColorMatrix();
            saturation.setSaturation(SATURATION);
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius,
                    RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturation)),
                    Shader.TileMode.CLAMP));
        }

        /** Records the bar's earlier siblings, lined up with the pill; never the bar or a view holding it. */
        boolean record(View bar, ViewGroup parent, Rect pill) {
            node.setPosition(pill);
            float originX = bar.getLeft() + bar.getTranslationX() + pill.left;
            float originY = bar.getTop() + bar.getTranslationY() + pill.top;
            int end = parent.indexOfChild(bar);
            boolean recorded = false;
            RecordingCanvas canvas = node.beginRecording();
            try {
                for (int i = 0; i < end; i++) {
                    View view = parent.getChildAt(i);
                    if (view.getVisibility() != View.VISIBLE || view.getAlpha() == 0f || view instanceof ViewStub) continue;
                    int save = canvas.save();
                    canvas.translate(view.getLeft() + view.getTranslationX() - view.getScrollX() - originX,
                            view.getTop() + view.getTranslationY() - view.getScrollY() - originY);
                    view.draw(canvas);
                    canvas.restoreToCount(save);
                    recorded = true;
                }
            } finally {
                node.endRecording();
            }
            return recorded;
        }
    }
}
