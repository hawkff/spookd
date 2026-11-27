/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings.preference.widgets;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.preference.Preference;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.settings.ActivityHook;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.utils.IgStr;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.ResourceUtils;

public final class InstagramPreferenceStyle {
    static final long NAVIGATION_DRAG_DURATION_MS = 120L;
    private static final String TAG_TITLE = "spookd_instagram_pref_title";
    private static final String TAG_SUMMARY = "spookd_instagram_pref_summary";
    private static final String TAG_TEXT_COLUMN = "spookd_instagram_pref_text_column";
    private static final String TAG_RESULT_CATEGORY = "spookd_instagram_search_result_category";
    private static final String TAG_RESULT_CATEGORY_ROW = "spookd_instagram_search_result_category_row";
    private static final String TAG_RESULT_CATEGORY_ICON = "spookd_instagram_search_result_category_icon";
    private static final String TAG_SWITCH = "spookd_instagram_pref_switch";
    private static final String TAG_TRAILING = "spookd_instagram_pref_trailing";
    private static final String IGDS_SWITCH_CLASS_NAME =
            "com.instagram.igds.components.switchbutton.IgdsSwitch";

    public static final int TRAILING_NONE = 0;
    public static final int TRAILING_SWITCH = 1;
    public static final int TRAILING_CHEVRON = 2;
    private static final int TRAILING_CHECK = 3;

    private InstagramPreferenceStyle() {
    }

    private static String chevronDrawableName(int layoutDirection) {
        return layoutDirection == View.LAYOUT_DIRECTION_RTL
                ? UI.DRAWABLE_CHEVRON_RIGHT_RTL
                : UI.DRAWABLE_CHEVRON_RIGHT;
    }

    public static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()
        );
    }

    public static void applyToolbarLayout(
            Context context,
            LinearLayout toolbar,
            ImageView back,
            TextView title
    ) {
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(context, SpookdType.SPACE_M), 0,
                dp(context, SpookdType.SPACE_M), 0);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, SpookdType.TOOLBAR_HEIGHT)));

        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setPaddingRelative(0, 0, dp(context, SpookdType.SPACE_L), 0);
        int backSize = dp(context, SpookdType.MIN_TOUCH);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(backSize, backSize);
        backParams.gravity = Gravity.CENTER_VERTICAL;
        back.setLayoutParams(backParams);

        applyToolbarTitle(title);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.gravity = Gravity.CENTER_VERTICAL;
        titleParams.leftMargin = dp(context, SpookdType.SPACE_M);
        title.setLayoutParams(titleParams);
    }

    /** Instagram's app bar title: bold, on one line. */
    public static void applyToolbarTitle(TextView title) {
        SpookdType.apply(title, SpookdType.TITLE);
        title.setIncludeFontPadding(false);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
    }

    /** Matches platform preference dialogs to Instagram's resolved theme and type scale. */
    public static Context dialogContext(Context context) {
        int themeRes = UI.isDarkMode()
                ? android.R.style.Theme_DeviceDefault
                : android.R.style.Theme_DeviceDefault_Light;
        return SpookdType.dialogContext(context, themeRes);
    }

    public static int backgroundColor() {
        return UI.getBackgroundColor();
    }

    public static int pressedBackgroundColor() {
        int fallback = UI.getThemedColour("igds_color_secondary_background");
        return ResourceUtils.getColor("igds_elevated_highlight_background", fallback);
    }

    public static int searchBackgroundColor() {
        int themedBackground = backgroundColor();
        int luminance = 299 * Color.red(themedBackground)
                + 587 * Color.green(themedBackground)
                + 114 * Color.blue(themedBackground);
        if (luminance < 128000) {
            return Color.rgb(43, 48, 54);
        }
        // 7% toward the text color: near Instagram's light field on white, and still visible on a tinted page.
        return lightSearchFieldColor(themedBackground, primaryTextColor());
    }

    static int lightSearchFieldColor(int page, int text) {
        return blend(page, text, 0.07f);
    }

    static int blend(int base, int over, float ratio) {
        return Color.rgb(
                Math.round(Color.red(base) + (Color.red(over) - Color.red(base)) * ratio),
                Math.round(Color.green(base) + (Color.green(over) - Color.green(base)) * ratio),
                Math.round(Color.blue(base) + (Color.blue(over) - Color.blue(base)) * ratio)
        );
    }

    /**
     * The card of spookd's dark dialogs: Instagram's elevated background, or its highlight when a
     * theme flattens elevation into the page. 0 in light theme, which keeps the white card.
     */
    public static int dialogSurfaceColor() {
        if (!UI.isDarkMode()) {
            return 0;
        }
        return elevatedSurface(
                backgroundColor(),
                UI.getThemedColour("igds_color_elevated_background"),
                pressedBackgroundColor()
        );
    }

    static int elevatedSurface(int page, int elevated, int highlight) {
        return elevated != page && elevated != Color.BLACK ? elevated : highlight;
    }

    public static int primaryTextColor() {
        return UI.getThemedColour("igds_color_primary_text");
    }

    public static int secondaryTextColor() {
        return UI.getThemedColour("igds_color_secondary_text");
    }

    /** Readable on both themes; a disabled row is told apart by its title losing primary color. */
    public static int disabledTextColor() {
        return secondaryTextColor();
    }

    public static int separatorColor() {
        return UI.getThemedColour("igds_color_separator");
    }

    private static void alignTextToLocaleStart(TextView textView) {
        textView.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        textView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    }

    public static int selectionColor() {
        return ResourceUtils.getColor("igds_primary_button", 0xff0095f6);
    }

    public static void applySystemBarStyle(Activity activity) {
        activity.getWindow().setStatusBarColor(backgroundColor());
        activity.getWindow().setNavigationBarColor(backgroundColor());

        int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
        if (UI.isDarkMode()) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    public static View createPreferenceView(Context context, int trailingType) {
        return createPreferenceView(context,trailingType,null);
    }

    public static View createPreferenceView(Context context, int trailingType, String iconResName) {
        PreferenceRow row = new PreferenceRow(context, trailingType);
        row.setOrientation(trailingType == TRAILING_SWITCH ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int edge = dp(context, SpookdType.SPACE_L);
        int vertical = dp(context, SpookdType.SPACE_M);
        row.setPadding(edge, vertical, edge, vertical);
        row.setMinimumHeight(dp(context, SpookdType.MIN_TOUCH));
        row.setBackgroundColor(backgroundColor());
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if(iconResName!=null){
            row.setIcon(iconResName);
        }

        if (trailingType == TRAILING_SWITCH) {
            LinearLayout titleRow = new LinearLayout(context);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(titleRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            row.setHighlightView(titleRow);

            TextView title = new TextView(context);
            title.setTag(TAG_TITLE);
            SpookdType.apply(title, SpookdType.BODY);
            title.setTextColor(row.primaryColor);
            title.setIncludeFontPadding(true);
            title.setSingleLine(false);
            alignTextToLocaleStart(title);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            );
            titleParams.setMarginEnd(dp(context, SpookdType.SPACE_M));
            titleRow.addView(title, titleParams);

            CompoundButton switchView = createNativeSwitch(nativeSwitchContext(context));
            switchView.setClickable(false);
            switchView.setFocusable(false);
            switchView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            switchView.setTag(TAG_SWITCH);
            titleRow.addView(switchView, new LinearLayout.LayoutParams(dp(context, 52), dp(context, 32)));

            TextView summary = new TextView(context);
            summary.setTag(TAG_SUMMARY);
            SpookdType.apply(summary, SpookdType.SECONDARY);
            summary.setTextColor(row.secondaryColor);
            alignTextToLocaleStart(summary);
            summary.setPadding(0, dp(context, SpookdType.SPACE_XS), 0, 0);
            row.addView(summary, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            return row;
        }

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setTag(TAG_TEXT_COLUMN);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        textParams.setMarginEnd(dp(context, SpookdType.SPACE_M));
        row.addView(textColumn, textParams);

        TextView title = new TextView(context);
        title.setTag(TAG_TITLE);
        SpookdType.apply(title, SpookdType.BODY);
        title.setTextColor(row.primaryColor);
        title.setIncludeFontPadding(true);
        title.setSingleLine(false);
        alignTextToLocaleStart(title);
        textColumn.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView summary = new TextView(context);
        summary.setTag(TAG_SUMMARY);
        SpookdType.apply(summary, SpookdType.SECONDARY);
        summary.setTextColor(row.secondaryColor);
        alignTextToLocaleStart(summary);
        summary.setPadding(0, dp(context, SpookdType.SPACE_XS), 0, 0);
        textColumn.addView(summary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        if (trailingType == TRAILING_CHEVRON) {
            ImageView trailing = new ImageView(context);
            trailing.setTag(TAG_TRAILING);
            UI.setThemedIcon(
                    trailing,
                    chevronDrawableName(
                            context.getResources().getConfiguration().getLayoutDirection()
                    ),
                    "igds_color_secondary_icon"
            );
            trailing.setScaleType(ImageView.ScaleType.CENTER);
            row.addView(trailing, new LinearLayout.LayoutParams(dp(context, 22), dp(context, 34)));
        }

        return row;
    }

    public static View createSearchResultView(Context context) {
        View view = createPreferenceView(context, TRAILING_CHEVRON);

        TextView description = view.findViewWithTag(TAG_SUMMARY);
        if (description != null) {
            description.setMaxLines(2);
            description.setEllipsize(TextUtils.TruncateAt.END);
        }

        LinearLayout textColumn = view.findViewWithTag(TAG_TEXT_COLUMN);
        if (textColumn != null) {
            LinearLayout categoryRow = new LinearLayout(context);
            categoryRow.setTag(TAG_RESULT_CATEGORY_ROW);
            categoryRow.setOrientation(LinearLayout.HORIZONTAL);
            categoryRow.setGravity(Gravity.CENTER_VERTICAL);
            categoryRow.setVisibility(View.GONE);
            textColumn.addView(categoryRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            ImageView categoryIcon = new ImageView(context);
            categoryIcon.setTag(TAG_RESULT_CATEGORY_ICON);
            categoryIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            categoryIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    dp(context, 16),
                    dp(context, 16)
            );
            iconParams.setMarginEnd(dp(context, SpookdType.SPACE_XS));
            categoryRow.addView(categoryIcon, iconParams);

            TextView category = new TextView(context);
            category.setTag(TAG_RESULT_CATEGORY);
            SpookdType.apply(category, SpookdType.SECONDARY);
            category.setTextColor(secondaryTextColor());
            category.setIncludeFontPadding(true);
            alignTextToLocaleStart(category);
            category.setMaxLines(1);
            category.setEllipsize(TextUtils.TruncateAt.END);
            categoryRow.addView(category, new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            ));
        }

        return view;
    }

    public static void bindText(Preference preference, View view) {
        Context context = view.getContext();
        boolean enabled = preference.isEnabled();
        boolean chevron = view instanceof PreferenceRow
                && ((PreferenceRow) view).trailingType == TRAILING_CHEVRON;
        boolean useNormalColors = enabled || chevron;

        TextView title = view.findViewWithTag(TAG_TITLE);
        TextView summary = view.findViewWithTag(TAG_SUMMARY);
        View trailing = view.findViewWithTag(TAG_TRAILING);

        PreferenceRow row = view instanceof PreferenceRow ? (PreferenceRow) view : null;
        int disabledColor = row != null ? row.disabledColor : disabledTextColor();
        int titleColor = !useNormalColors ? disabledColor
                : row != null ? row.primaryColor : primaryTextColor();
        int summaryColor = !useNormalColors ? disabledColor
                : row != null ? row.secondaryColor : secondaryTextColor();

        if (title != null) {
            title.setText(titleWithNewLabel(preference));
            title.setTextColor(titleColor);
            title.setEnabled(enabled);
        }

        if (summary != null) {
            CharSequence summaryText = preference.getSummary();
            boolean hasSummary = !TextUtils.isEmpty(summaryText);
            summary.setText(hasSummary ? summaryText : "");
            summary.setVisibility(hasSummary ? View.VISIBLE : View.GONE);
            summary.setTextColor(summaryColor);
            summary.setEnabled(enabled);
        }

        if (trailing != null) {
            // Only chevron rows have a trailing icon, and its tint is set when it is created.
            trailing.setEnabled(enabled);
        }

        if (chevron) {
            view.setAlpha(enabled ? 1.0f : 0.5f);
        }
        view.setEnabled(enabled);
    }

    /** Main thread. The title, with a NEW badge on a row added in this release while the labels are shown. */
    private static CharSequence titleWithNewLabel(Preference preference) {
        CharSequence title = preference.getTitle();
        String key = preference.getKey();
        if (title == null || key == null || !Settings.NEW_KEYS.contains(key) || !Pref.showNewLabels()) {
            return title;
        }
        return withNewLabel(title, IgStr.str("spookd_new_label"));
    }

    static CharSequence withNewLabel(CharSequence title, String label) {
        SpannableStringBuilder text = new SpannableStringBuilder(title).append(' ');
        int start = text.length();
        text.append(label);
        text.setSpan(NewLabelSpan.INSTANCE, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    public static void bindSearchResult(
            Preference preference,
            View view,
            CharSequence categoryText,
            String categoryIconResourceName
    ) {
        bindText(preference, view);

        Context context = view.getContext();
        View categoryRow = view.findViewWithTag(TAG_RESULT_CATEGORY_ROW);
        ImageView categoryIcon = view.findViewWithTag(TAG_RESULT_CATEGORY_ICON);
        TextView category = view.findViewWithTag(TAG_RESULT_CATEGORY);
        boolean hasCategory = !TextUtils.isEmpty(categoryText);
        boolean hasCategoryIcon = hasCategory
                && !TextUtils.isEmpty(categoryIconResourceName);

        if (categoryRow != null) {
            categoryRow.setVisibility(hasCategory ? View.VISIBLE : View.GONE);
            categoryRow.setPaddingRelative(0, dp(context, SpookdType.SPACE_XS), 0, 0);
        }

        if (categoryIcon != null) {
            categoryIcon.setVisibility(hasCategoryIcon ? View.VISIBLE : View.GONE);
            if (hasCategoryIcon) {
                UI.setThemedIcon(categoryIcon, categoryIconResourceName);
            }
        }

        if (category != null) {
            category.setText(hasCategory ? categoryText : "");
            category.setEnabled(preference.isEnabled());
        }
    }

    public static void setTrailingVisible(View view, boolean visible) {
        View trailing = view.findViewWithTag(TAG_TRAILING);
        if (trailing != null) {
            trailing.setVisibility(visible ? View.VISIBLE : View.GONE);
            trailing.invalidate();
        }
    }

    public static void bindIcon(View view, String iconResName) {
        if (view instanceof PreferenceRow) {
            ((PreferenceRow) view).setIcon(iconResName);
        }
    }

    public static void setPressedHighlightEnabled(View view, boolean enabled) {
        if (view instanceof PreferenceRow) {
            ((PreferenceRow) view).setPressedHighlightEnabled(enabled);
        }
    }

    public static CompoundButton findSwitch(View view) {
        return view.findViewWithTag(TAG_SWITCH);
    }

    public static View createNavigationCheckRow(
            Context context,
            CharSequence title,
            String iconName,
            boolean checked
    ) {
        PreferenceRow row = new PreferenceRow(context, TRAILING_CHECK);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 56));
        row.setPadding(dp(context, 12), dp(context, 4), dp(context, 8), dp(context, 4));

        CheckBox checkBox = new CheckBox(context);
        checkBox.setClickable(false);
        checkBox.setFocusable(false);
        checkBox.setGravity(Gravity.CENTER);
        checkBox.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        checkBox.setChecked(checked);
        row.addView(checkBox, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 48)));

        ImageView iconView = new ImageView(context);
        UI.setThemedIcon(iconView, iconName);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                dp(context, 24),
                dp(context, 24)
        );
        iconParams.setMarginStart(dp(context, 8));
        iconParams.setMarginEnd(dp(context, 12));
        row.addView(iconView, iconParams);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        SpookdType.apply(titleView, SpookdType.BODY);
        titleView.setTextColor(row.primaryColor);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
        );
        titleParams.setMarginEnd(dp(context, 8));
        row.addView(titleView, titleParams);
        row.setHighlightView(titleView);

        FrameLayout dragTarget = new FrameLayout(context);
        dragTarget.setTag(TAG_TRAILING);
        dragTarget.setContentDescription(title);
        DragHandleView dragIcon = new DragHandleView(context);
        dragIcon.setAlpha(0.72f);
        FrameLayout.LayoutParams dragIconParams = new FrameLayout.LayoutParams(
                dp(context, 24),
                dp(context, 24),
                Gravity.CENTER
        );
        dragTarget.addView(dragIcon, dragIconParams);
        row.addView(dragTarget, new LinearLayout.LayoutParams(
                dp(context, 48),
                dp(context, 48)
        ));

        row.setSwitchAccessibilityChecked(checked);
        row.setContentDescription(title);

        return row;
    }

    public static boolean isNavigationDragHandleHit(View row, float rawX, float rawY) {
        View dragTarget = row.findViewWithTag(TAG_TRAILING);
        if (dragTarget == null || dragTarget.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        dragTarget.getLocationOnScreen(location);
        return rawX >= location[0]
                && rawX < location[0] + dragTarget.getWidth()
                && rawY >= location[1]
                && rawY < location[1] + dragTarget.getHeight();
    }

    public static void setNavigationRowDragging(
            View view,
            boolean dragging,
            boolean animate
    ) {
        if (view instanceof PreferenceRow
                && ((PreferenceRow) view).trailingType == TRAILING_CHECK) {
            ((PreferenceRow) view).setDragState(dragging, animate);
        }
    }

    public static void setNativeSwitchChecked(
            CompoundButton switchView,
            boolean checked,
            boolean animate
    ) {
        if (!animate || switchView.isChecked() == checked) {
            switchView.setChecked(checked);
            return;
        }

        try {
            switchView.getClass()
                    .getMethod("setCheckedAnimated", boolean.class)
                    .invoke(switchView, checked);
        } catch (ReflectiveOperationException ignored) {
            switchView.setChecked(checked);
        }
    }

    private static Context nativeSwitchContext(Context context) {
        Configuration overrideConfiguration = new Configuration();
        overrideConfiguration.uiMode = UI.isDarkMode()
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;

        ContextThemeWrapper switchContext = new ContextThemeWrapper(context, 0);
        switchContext.applyOverrideConfiguration(overrideConfiguration);
        switchContext.getTheme().setTo(context.getTheme());
        return switchContext;
    }

    public static boolean useMaterialSwitchStyle(View view, boolean original) {
        Context context = view.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity && ActivityHook.isSpookd((Activity) context)) {
                return true;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        if (context instanceof Activity && ActivityHook.isSpookd((Activity) context)) {
            return true;
        }
        return original;
    }

    private static CompoundButton createNativeSwitch(Context context) {
        try {
            CompoundButton switchView = Class
                    .forName(IGDS_SWITCH_CLASS_NAME, true, context.getClassLoader())
                    .asSubclass(CompoundButton.class)
                    .getConstructor(Context.class)
                    .newInstance(context);
            return switchView;
        } catch (ReflectiveOperationException | ClassCastException exception) {
            throw new IllegalStateException("Unable to create Instagram's IgdsSwitch", exception);
        }
    }

    public static void bindSwitchAccessibility(View view, boolean checked) {
        if (view instanceof PreferenceRow) {
            ((PreferenceRow) view).setSwitchAccessibilityChecked(checked);
        }
    }

    public static boolean consumeSwitchClickAllowed(View view) {
        if (view instanceof PreferenceRow) {
            return ((PreferenceRow) view).consumeSwitchClickAllowed();
        }
        return true;
    }

    private static class PreferenceRow extends LinearLayout {
        private final Paint pressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect switchHitRect = new Rect();
        private final int trailingType;
        private View highlightView;
        private boolean pressedHighlightEnabled;
        private boolean pressedHighlightAllowed;
        private boolean switchClickAllowed = true;
        private boolean drawPressedHighlight;
        private boolean drawDragHighlight;
        private int dragOverlayColor;
        private boolean switchAccessibilityChecked;
        private ImageView iconView;
        private String iconName;
        private int pressedColor;
        final int primaryColor = primaryTextColor();
        final int secondaryColor = secondaryTextColor();
        final int disabledColor = disabledTextColor();


        PreferenceRow(Context context, int trailingType) {
            this(context, trailingType, null);
        }

        PreferenceRow(Context context, int trailingType, String iconResName) {
            super(context);
            this.trailingType = trailingType;
            setOrientation(LinearLayout.HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setWillNotDraw(false);
            if (trailingType == TRAILING_CHECK) {
                setOutlineProvider(ViewOutlineProvider.BOUNDS);
                setClipToOutline(false);
            }
        }

        private void initIconView(Context context) {
            iconView = new ImageView(context);

            int iconSize = dp(context, 24);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(iconSize, iconSize);

            params.setMarginEnd(dp(context, SpookdType.SPACE_M));
            iconView.setLayoutParams(params);

            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(iconView, 0);
        }

        void setIcon(String iconResName) {
            if (iconResName == null) {
                iconName = null;
                if (iconView != null) {
                    iconView.setImageDrawable(null);
                    iconView.setVisibility(View.GONE);
                }
                return;
            }
            if (iconResName.equals(iconName)) {
                return;
            }
            if (iconView == null) {
                initIconView(getContext());
            }
            UI.setThemedIcon(iconView, iconResName);
            iconView.setVisibility(View.VISIBLE);
            iconName = iconResName;
        }

        void setHighlightView(View highlightView) {
            this.highlightView = highlightView;
            pressedHighlightEnabled = true;
        }

        void setPressedHighlightEnabled(boolean enabled) {
            pressedHighlightEnabled = enabled;
            if (!enabled) {
                pressedHighlightAllowed = false;
                setDrawPressedHighlight(false);
            }
        }

        void setSwitchAccessibilityChecked(boolean checked) {
            switchAccessibilityChecked = checked;
        }

        void setDragState(boolean dragging, boolean animateState) {
            drawDragHighlight = dragging;
            dragOverlayColor = UI.isDarkMode() ? 0xff3f3f3f : 0x0d000000;
            if (dragging && !UI.isDarkMode()) {
                TypedArray colors = getContext().obtainStyledAttributes(
                        new int[]{android.R.attr.colorBackgroundFloating}
                );
                setBackgroundColor(colors.getColor(0, backgroundColor()) | 0xff000000);
                colors.recycle();
            } else {
                setBackground(null);
            }
            invalidate();

            animate().cancel();
            float translationZ = dragging
                    ? dp(getContext(), 5f) : 0f;
            if (animateState) {
                animate()
                        .translationZ(translationZ)
                        .setDuration(NAVIGATION_DRAG_DURATION_MS)
                        .start();
            } else {
                setTranslationZ(translationZ);
            }
        }

        @Override
        public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(event);
            if (trailingType == TRAILING_SWITCH || trailingType == TRAILING_CHECK) {
                event.setClassName(trailingType == TRAILING_SWITCH
                        ? "android.widget.Switch"
                        : "android.widget.CheckBox");
                event.setChecked(switchAccessibilityChecked);
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (trailingType == TRAILING_SWITCH || trailingType == TRAILING_CHECK) {
                info.setClassName(trailingType == TRAILING_SWITCH
                        ? "android.widget.Switch"
                        : "android.widget.CheckBox");
                info.setCheckable(true);
                info.setChecked(switchAccessibilityChecked);
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pressedHighlightAllowed = shouldDrawPressedHighlight(event.getX(), event.getY());
                switchClickAllowed = shouldHandleSwitchClick(event.getY());
            } else if (action == MotionEvent.ACTION_CANCEL) {
                pressedHighlightAllowed = false;
                setDrawPressedHighlight(false);
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            setDrawPressedHighlight(pressed && pressedHighlightAllowed);
            if (!pressed) {
                pressedHighlightAllowed = false;
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (drawPressedHighlight || drawDragHighlight) {
                pressedPaint.setStyle(Paint.Style.FILL);
                pressedPaint.setColor(
                        drawDragHighlight ? dragOverlayColor : pressedColor()
                );
                int top = highlightTop();
                int bottom = highlightBottom();
                canvas.drawRect(0, top, getWidth(), bottom, pressedPaint);
            }
            super.dispatchDraw(canvas);
        }

        private int pressedColor() {
            if (pressedColor == 0) {
                pressedColor = pressedBackgroundColor();
            }
            return pressedColor;
        }

        private boolean shouldDrawPressedHighlight(float x, float y) {
            if (!pressedHighlightEnabled || !isEnabled()) {
                return false;
            }

            if (trailingType != TRAILING_SWITCH) {
                return true;
            }

            if (highlightView == null || y < highlightTop() || y > highlightBottom()) {
                return false;
            }

            // Highlight the switch row when its text area is pressed, but never when the
            // switch itself is tapped; the switch provides its own touch feedback.
            View excludedView = findViewWithTag(TAG_SWITCH);
            if (excludedView == null) {
                return true;
            }

            int horizontalSlop = dp(getContext(), 10);
            int verticalSlop = dp(getContext(), 3);
            switchHitRect.set(0, 0, excludedView.getWidth(), excludedView.getHeight());
            offsetDescendantRectToMyCoords(excludedView, switchHitRect);
            switchHitRect.inset(-horizontalSlop, -verticalSlop);
            return !switchHitRect.contains(Math.round(x), Math.round(y));
        }

        private boolean shouldHandleSwitchClick(float y) {
            return trailingType != TRAILING_SWITCH || highlightView == null || (y >= highlightTop() && y <= highlightBottom());
        }

        private boolean consumeSwitchClickAllowed() {
            boolean allowed = switchClickAllowed;
            switchClickAllowed = true;
            return allowed;
        }

        private int highlightTop() {
            if (highlightView == null) {
                return 0;
            }
            return Math.max(0, highlightView.getTop() - dp(getContext(), SpookdType.SPACE_M));
        }

        private int highlightBottom() {
            if (highlightView == null) {
                return getHeight();
            }
            // Ends where the summary begins.
            return Math.min(getHeight(), highlightView.getBottom() + dp(getContext(), SpookdType.SPACE_XS));
        }

        private void setDrawPressedHighlight(boolean drawPressedHighlight) {
            if (this.drawPressedHighlight == drawPressedHighlight) {
                return;
            }
            this.drawPressedHighlight = drawPressedHighlight;
            invalidate();
        }
    }

    /** Draws its text as a small filled pill; shared by all rows, drawn on the main thread only. */
    static final class NewLabelSpan extends ReplacementSpan {
        static final NewLabelSpan INSTANCE = new NewLabelSpan();
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private int fillColor;

        private Paint labelPaint(Paint text) {
            labelPaint.set(text);
            labelPaint.setTextSize(text.getTextSize() * 0.7f);
            labelPaint.setFakeBoldText(true);
            return labelPaint;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            Paint label = labelPaint(paint);
            return Math.round(label.measureText(text, start, end) + label.getTextSize() * 1.2f);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                         int top, int y, int bottom, Paint paint) {
            float center = y + (paint.ascent() + paint.descent()) / 2f;
            Paint label = labelPaint(paint);
            float padding = label.getTextSize() * 0.6f;
            float half = (label.descent() - label.ascent()) / 2f;
            bounds.set(x, center - half, x + label.measureText(text, start, end) + 2 * padding, center + half);
            if (fillColor == 0) fillColor = selectionColor();
            label.setColor(fillColor);
            canvas.drawRoundRect(bounds, half, half, label);
            label.setColor(Color.WHITE);
            canvas.drawText(text, start, end, x + padding, center - (label.ascent() + label.descent()) / 2f, label);
        }
    }

    private static final class DragHandleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DragHandleView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(secondaryTextColor());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float lineWidth = dp(getContext(), 16f);
            float stroke = dp(getContext(), 2f);
            float left = (getWidth() - lineWidth) / 2.0f;
            float right = left + lineWidth;
            float radius = stroke / 2.0f;

            for (int line = 0; line < 3; line++) {
                float centerY = dp(
                        getContext(),
                        8f + 4f * line
                );
                canvas.drawRoundRect(
                        left,
                        centerY - radius,
                        right,
                        centerY + radius,
                        radius,
                        radius,
                        paint
                );
            }
        }
    }
}
