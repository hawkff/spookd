/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings.preference.widgets;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.CompoundButton;
import android.widget.TextView;

import app.morphe.extension.instagram.patches.customise.font.CustomFont;
import app.morphe.extension.shared.Logger;

/**
 * Instagram's interface font and the one type and spacing scale every spookd screen and dialog
 * uses, matched to Instagram's own settings.
 */
public final class SpookdType {
    /** App bar, dialog and empty-state titles. */
    public static final int TITLE = 0;
    /** Section headers, drawn in the secondary text color. */
    public static final int SECTION = 1;
    /** Row titles, list rows, inputs and dialog messages. */
    public static final int BODY = 2;
    /** Row summaries and other supporting text, drawn in the secondary text color. */
    public static final int SECONDARY = 3;
    /** Buttons and text actions. */
    public static final int LABEL = 4;
    /** Badges, dates and other small labels. */
    public static final int CAPTION = 5;

    private static final float[] SIZE_SP = {18f, 14f, 16f, 14f, 14f, 12f};
    private static final int[] WEIGHT = {700, 600, 400, 400, 600, 400};
    private static final float[] LINE_SPACING = {1f, 1f, 1f, 1.1f, 1f, 1f};

    /** Spacing in dp, on a 4dp grid. */
    public static final int SPACE_XS = 4;
    public static final int SPACE_S = 8;
    public static final int SPACE_M = 12;
    public static final int SPACE_L = 16;
    public static final int SPACE_XL = 24;
    public static final int TOOLBAR_HEIGHT = 56;
    public static final int MIN_TOUCH = 48;
    /** Height of the band between sections, as in Instagram's settings. */
    public static final int SECTION_BREAK = 6;

    private static Typeface[] faces;

    private SpookdType() {
    }

    /** Sets the role's size in sp, weight and line height. Main thread. */
    public static void apply(TextView view, int role) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_SP[role]);
        view.setTypeface(typeface(view.getContext(), WEIGHT[role]));
        view.setLineSpacing(0f, LINE_SPACING[role]);
    }

    /**
     * Instagram's font in the closest of its weights, or the user's custom font in that weight
     * when one is in use.
     */
    public static Typeface typeface(Context context, int weight) {
        Typeface[] loaded = faces(context);
        return CustomFont.assign(loaded[weight >= 700 ? 2 : weight >= 600 ? 1 : 0]);
    }

    private static synchronized Typeface[] faces(Context context) {
        if (faces == null) {
            faces = load(context);
        }
        return faces;
    }

    /** Regular, semibold and bold, falling back to sans-serif when Instagram's font is missing. */
    static Typeface[] load(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface family = font(context, "prism_sans");
            if (family == null) {
                family = Typeface.create("sans-serif", Typeface.NORMAL);
            }
            return new Typeface[]{
                    Typeface.create(family, 400, false),
                    Typeface.create(family, 600, false),
                    Typeface.create(family, 700, false),
            };
        }
        // Before API 28 a family cannot be asked for a weight, so each weight is its own font.
        Typeface regular = font(context, "IGUIBetav9_Regular");
        Typeface semibold = font(context, "IGUIBetav9_SemiBold");
        Typeface bold = font(context, "IGUIBetav9_Bold");
        if (regular != null && semibold != null && bold != null) {
            return new Typeface[]{regular, semibold, bold};
        }
        Typeface sans = Typeface.create("sans-serif", Typeface.NORMAL);
        return new Typeface[]{
                sans,
                Typeface.create("sans-serif-medium", Typeface.NORMAL),
                Typeface.create(sans, Typeface.BOLD),
        };
    }

    private static Typeface font(Context context, String name) {
        try {
            Resources resources = context.getResources();
            int id = resources.getIdentifier(name, "font", context.getPackageName());
            return id == 0 ? null : resources.getFont(id);
        } catch (Exception e) {
            Logger.printException(() -> "Instagram's interface font is unavailable: " + name, e);
            return null;
        }
    }

    /** The scale's weight for a platform text style: medium reads as semibold, as in Instagram. */
    static int interfaceWeight(int weight) {
        return weight >= 650 ? 700 : weight >= 500 ? 600 : 400;
    }

    /**
     * A themed context whose platform dialogs and preference dialogs use the type scale and
     * Instagram's text and accent colors.
     */
    public static Context dialogContext(Context base, int themeResId) {
        return new DialogContext(base, themeResId, null);
    }

    /** As {@link #dialogContext(Context, int)}, with the palette given rather than resolved. */
    static Context dialogContext(Context base, int themeResId, int primary, int secondary, int accent) {
        return new DialogContext(base, themeResId, new int[]{primary, secondary, accent});
    }

    /** Styles a text field a preference dialog creates in code, which no inflater sees. */
    public static void applyDialogInput(TextView view) {
        apply(view, BODY);
        Context context = view.getContext();
        if (context instanceof DialogContext) {
            view.setTextColor(((DialogContext) context).palette()[PRIMARY_COLOR]);
        }
    }

    private static final int PRIMARY_COLOR = 0;
    private static final int SECONDARY_COLOR = 1;
    private static final int ACCENT_COLOR = 2;

    private static final class DialogContext extends ContextThemeWrapper {
        private LayoutInflater inflater;
        private int[] palette;

        DialogContext(Context base, int themeResId, int[] palette) {
            super(base, themeResId);
            this.palette = palette;
        }

        /** Resolved once, when this context's first dialog text is styled. Main thread. */
        int[] palette() {
            if (palette == null) {
                palette = new int[]{
                        InstagramPreferenceStyle.primaryTextColor(),
                        InstagramPreferenceStyle.secondaryTextColor(),
                        InstagramPreferenceStyle.selectionColor(),
                };
            }
            return palette;
        }

        @Override
        public Object getSystemService(String name) {
            if (!LAYOUT_INFLATER_SERVICE.equals(name)) {
                return super.getSystemService(name);
            }
            if (inflater == null) {
                // A clone owned by this context, so setting a factory on it affects nothing else.
                inflater = (LayoutInflater) super.getSystemService(name);
                inflater.setFactory2(new DialogTextFactory(inflater, this));
            }
            return inflater;
        }
    }

    private static final class DialogTextFactory implements LayoutInflater.Factory2 {
        private static final String[] PREFIXES = {
                "android.widget.", "android.webkit.", "android.app.", "android.view."
        };
        private static final int ALERT_TITLE_ID =
                Resources.getSystem().getIdentifier("alertTitle", "id", "android");

        private final LayoutInflater.Factory original;
        private final DialogContext owner;

        DialogTextFactory(LayoutInflater inflater, DialogContext owner) {
            this.original = inflater.getFactory();
            this.owner = owner;
        }

        @Override
        public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
            View view = null;
            if (original instanceof LayoutInflater.Factory2) {
                view = ((LayoutInflater.Factory2) original).onCreateView(parent, name, context, attrs);
            } else if (original != null) {
                view = original.onCreateView(name, context, attrs);
            }
            if (view == null) {
                view = create(name, context, attrs);
            }
            if (view instanceof TextView) {
                style((TextView) view);
            }
            return view;
        }

        @Override
        public View onCreateView(String name, Context context, AttributeSet attrs) {
            return onCreateView(null, name, context, attrs);
        }

        private void style(TextView view) {
            try {
                int id = view.getId();
                if (ALERT_TITLE_ID != 0 && id == ALERT_TITLE_ID) {
                    apply(view, TITLE);
                    view.setTextColor(owner.palette()[PRIMARY_COLOR]);
                } else if (id == android.R.id.message) {
                    apply(view, BODY);
                    view.setTextColor(owner.palette()[SECONDARY_COLOR]);
                } else if (id == android.R.id.text1) {
                    // Rows of list, single-choice and multi-choice dialogs.
                    apply(view, BODY);
                    view.setTextColor(owner.palette()[PRIMARY_COLOR]);
                    if (view instanceof CheckedTextView) {
                        ((CheckedTextView) view).setCheckMarkTintList(
                                ColorStateList.valueOf(owner.palette()[ACCENT_COLOR]));
                    }
                } else if (view instanceof Button && !(view instanceof CompoundButton)) {
                    apply(view, LABEL);
                    view.setAllCaps(false);
                    int accent = owner.palette()[ACCENT_COLOR];
                    view.setTextColor(new ColorStateList(
                            new int[][]{{-android.R.attr.state_enabled}, {}},
                            new int[]{(accent & 0x00ffffff) | 0x80000000, accent}));
                } else {
                    Typeface current = view.getTypeface();
                    int weight = current == null ? 400
                            : Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? current.getWeight()
                            : current.isBold() ? 700 : 400;
                    view.setTypeface(typeface(view.getContext(), interfaceWeight(weight)));
                }
            } catch (Exception e) {
                Logger.printException(() -> "Failed to style dialog text", e);
            }
        }

        /** Creates the view the way the platform inflater would, or leaves it to the platform. */
        private static View create(String name, Context context, AttributeSet attrs) {
            if (name.indexOf('.') != -1) {
                return createView(name, null, context, attrs);
            }
            for (String prefix : PREFIXES) {
                View view = createView(name, prefix, context, attrs);
                if (view != null) {
                    return view;
                }
            }
            return null;
        }

        private static View createView(String name, String prefix, Context context, AttributeSet attrs) {
            // Before API 29 the inflater builds the view in the context it is inflating in.
            LayoutInflater inflater = LayoutInflater.from(context);
            try {
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? inflater.createView(context, name, prefix, attrs)
                        : inflater.createView(name, prefix, attrs);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
    }
}
