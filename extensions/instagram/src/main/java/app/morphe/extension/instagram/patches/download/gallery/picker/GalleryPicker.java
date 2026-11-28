package app.morphe.extension.instagram.patches.download.gallery.picker;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import app.morphe.extension.instagram.patches.download.gallery.Gallery;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.settings.preference.widgets.SpookdType;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

/**
 * The From private gallery entry of Instagram's media picker sheet, which the DM composer and the
 * comment photo button both open. The picked item comes back through the sheet's own result for
 * a photo picked in another app, so Instagram imports and sends it as it would that one.
 */
public final class GalleryPicker {
    /** Intent extra that opens the private gallery to pick one photo or video. */
    public static final String EXTRA_PICK = "spookd_gallery_pick";
    /** Intent extra set when the sheet takes photos only, as the comment photo button's does. */
    public static final String EXTRA_PHOTOS_ONLY = "spookd_gallery_photos_only";
    // The sheet imports this request's result URI as external media.
    private static final int EXTERNAL_MEDIA_REQUEST = 1002;
    private static int headerId;
    private static int titleId;
    private static int chevronId;

    private GalleryPicker() {
    }

    /** Main thread, at the start of the sheet's onViewCreated; never throws. */
    public static void onPickerViewCreated(Fragment sheet, View root) {
        try {
            if (!Pref.galleryPicker() || !Gallery.canOpen(root.getContext())) return;
            new EntryPlacer(sheet, root);
        } catch (Throwable t) {
            Logger.printException(() -> "GalleryPicker: cannot watch the picker", t);
        }
    }

    /** The modal screen in private gallery pick mode; the extras match ActivityHook's launch. */
    static Intent pickIntent(String packageName, boolean photosOnly) {
        return new Intent()
                .setClassName(packageName, "com.instagram.modal.ModalActivity")
                .putExtra("piko", true)
                .putExtra("fragment_arguments", new Bundle())
                .putExtra("piko_launch_type", "private_gallery")
                .putExtra(EXTRA_PICK, true)
                .putExtra(EXTRA_PHOTOS_ONLY, photosOnly);
    }

    // The patch replaces this body with a read of the sheet's own flag for offering videos.
    public static boolean allowsVideo(Object sheet) {
        return false;
    }

    /** Main thread. */
    private static void open(Fragment sheet, View entry) {
        try {
            if (!sheet.isAdded()) return;
            sheet.startActivityForResult(pickIntent(entry.getContext().getPackageName(), !allowsVideo(sheet)),
                    EXTERNAL_MEDIA_REQUEST);
        } catch (Throwable t) {
            Logger.printException(() -> "GalleryPicker: cannot open the gallery", t);
        }
    }

    /**
     * The header with the folder title ("Recents") is in a child fragment that shows up after the
     * sheet's view, so the entry goes in on the first layout that has it; leaving the window stops the watch.
     */
    private static final class EntryPlacer implements ViewTreeObserver.OnGlobalLayoutListener,
            View.OnAttachStateChangeListener {
        private final Fragment sheet;
        private final View root;

        EntryPlacer(Fragment sheet, View root) {
            this.sheet = sheet;
            this.root = root;
            if (headerId == 0) {
                headerId = ResourceUtils.getIdentifier(ResourceType.ID, "media_picker_tab_header");
                titleId = ResourceUtils.getIdentifier(ResourceType.ID, "media_picker_header_title_container");
                chevronId = ResourceUtils.getIdentifier(ResourceType.ID, "media_picker_header_chevron");
            }
            root.getViewTreeObserver().addOnGlobalLayoutListener(this);
            root.addOnAttachStateChangeListener(this);
        }

        @Override
        public void onGlobalLayout() {
            try {
                if (headerId == 0 || titleId == 0) {
                    stop();
                    return;
                }
                View header = root.findViewById(headerId);
                // A row layout would put the entry into the row and move the title.
                if (!(header instanceof ViewGroup) || header instanceof LinearLayout) return;
                View title = header.findViewById(titleId);
                if (title == null) return;
                stop();
                View chevron = chevronId == 0 ? null : header.findViewById(chevronId);
                TextView entry = pill(header, v -> open(sheet, v));
                ((ViewGroup) header).addView(entry, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place(header, title, chevron, entry));
            } catch (Throwable t) {
                stop();
                Logger.printException(() -> "GalleryPicker: cannot add the entry", t);
            }
        }

        @Override
        public void onViewAttachedToWindow(View v) {
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            stop();
        }

        private void stop() {
            try {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                root.removeOnAttachStateChangeListener(this);
            } catch (Throwable t) {
                Logger.printException(() -> "GalleryPicker: cannot stop watching", t);
            }
        }

    }

    /** A filled pill in Instagram's colors: 48dp to touch, its fill inset to a 36dp chip. Main thread. */
    static TextView pill(View anchor, View.OnClickListener onClick) {
        float density = anchor.getResources().getDisplayMetrics().density;
        TextView entry = new TextView(anchor.getContext());
        entry.setText(str("spookd_private_gallery"));
        entry.setContentDescription(str("spookd_gallery_picker_entry"));
        SpookdType.apply(entry, SpookdType.LABEL);
        entry.setTextColor(InstagramPreferenceStyle.primaryTextColor());
        entry.setGravity(Gravity.CENTER);
        entry.setSingleLine(true);
        entry.setEllipsize(TextUtils.TruncateAt.END);
        entry.setMinHeight((int) (SpookdType.MIN_TOUCH * density));
        int padding = (int) (SpookdType.SPACE_M * density);
        entry.setPadding(padding, 0, padding, 0);
        int inset = (int) (6 * density);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(InstagramPreferenceStyle.searchBackgroundColor());
        fill.setCornerRadius((SpookdType.MIN_TOUCH - 2 * 6) * density / 2);
        entry.setBackground(new InsetDrawable(fill, 0, inset, 0, inset));
        entry.setOnClickListener(onClick);
        return entry;
    }

    /**
     * Main thread, after each header layout: right of the folder title and its chevron, centered on
     * the title and within the header's end padding, so it covers neither the title nor what is below
     * the header. Hidden when less than a touch target fits.
     */
    private static void place(View header, View title, View chevron, TextView entry) {
        try {
            float density = header.getResources().getDisplayMetrics().density;
            int gap = (int) (SpookdType.SPACE_S * density);
            int left = rightIn(header, title);
            if (chevron != null && chevron.getVisibility() == View.VISIBLE) left = Math.max(left, rightIn(header, chevron));
            left += gap;
            int maxWidth = header.getWidth() - header.getPaddingRight() - gap - left;
            boolean fits = maxWidth >= (int) (SpookdType.MIN_TOUCH * density);
            int visibility = fits ? View.VISIBLE : View.INVISIBLE;
            // Each change asks for a layout, which calls this again with the same values.
            if (entry.getVisibility() != visibility) entry.setVisibility(visibility);
            if (fits && entry.getMaxWidth() != maxWidth) entry.setMaxWidth(maxWidth);
            int[] at = new int[2];
            int[] titleAt = new int[2];
            header.getLocationInWindow(at);
            title.getLocationInWindow(titleAt);
            int centerY = titleAt[1] - at[1] + title.getHeight() / 2;
            entry.setTranslationX(left - entry.getLeft());
            entry.setTranslationY(centerY - entry.getHeight() / 2f - entry.getTop());
        } catch (Throwable t) {
            Logger.printException(() -> "GalleryPicker: cannot place the entry", t);
        }
    }

    /** The right edge of {@code view} in {@code header}'s coordinates. */
    private static int rightIn(View header, View view) {
        int[] at = new int[2];
        int[] viewAt = new int[2];
        header.getLocationInWindow(at);
        view.getLocationInWindow(viewAt);
        return viewAt[0] - at[0] + view.getWidth();
    }
}
