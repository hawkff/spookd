package app.morphe.extension.instagram.patches.download.gallery.picker;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import app.morphe.extension.instagram.patches.download.gallery.Gallery;
import app.morphe.extension.instagram.settings.preference.widgets.SpookdType;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * The From private gallery entry of Instagram's media picker sheet, which the DM composer and the
 * comment photo button both open. The picked item comes back through the sheet's own result for
 * a photo picked in another app, so Instagram imports and sends it as it would that one.
 */
public final class GalleryPicker {
    /** Intent extra that opens the private gallery to pick one photo or video. */
    public static final String EXTRA_PICK = "spookd_gallery_pick";
    // The sheet imports this request's result URI as external media.
    private static final int EXTERNAL_MEDIA_REQUEST = 1002;
    private static final String GALLERY_VIEW = "com.instagram.ui.widget.gallery.GalleryView";

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
    static Intent pickIntent(String packageName) {
        return new Intent()
                .setClassName(packageName, "com.instagram.modal.ModalActivity")
                .putExtra("piko", true)
                .putExtra("fragment_arguments", new Bundle())
                .putExtra("piko_launch_type", "private_gallery")
                .putExtra(EXTRA_PICK, true);
    }

    /** Main thread. */
    private static void open(Fragment sheet, View entry) {
        try {
            if (!sheet.isAdded()) return;
            sheet.startActivityForResult(pickIntent(entry.getContext().getPackageName()), EXTERNAL_MEDIA_REQUEST);
        } catch (Throwable t) {
            Logger.printException(() -> "GalleryPicker: cannot open the gallery", t);
        }
    }

    /** First match in the tree, depth first. */
    private static FrameLayout findGallery(View view) {
        if (view instanceof FrameLayout && GALLERY_VIEW.equals(view.getClass().getName())) return (FrameLayout) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            FrameLayout found = findGallery(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The photo grid is a child fragment that shows up after the sheet's view, so the entry goes in
     * on the first layout that has it; leaving the window stops the watch.
     */
    private static final class EntryPlacer implements ViewTreeObserver.OnGlobalLayoutListener,
            View.OnAttachStateChangeListener {
        private final Fragment sheet;
        private final View root;

        EntryPlacer(Fragment sheet, View root) {
            this.sheet = sheet;
            this.root = root;
            root.getViewTreeObserver().addOnGlobalLayoutListener(this);
            root.addOnAttachStateChangeListener(this);
        }

        @Override
        public void onGlobalLayout() {
            try {
                FrameLayout gallery = findGallery(root);
                if (gallery == null) return;
                stop();
                gallery.addView(entry(gallery), entryParams(gallery));
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

        private TextView entry(FrameLayout gallery) {
            float density = gallery.getResources().getDisplayMetrics().density;
            TextView entry = new TextView(gallery.getContext());
            entry.setText(str("spookd_gallery_picker_entry"));
            SpookdType.apply(entry, SpookdType.LABEL);
            entry.setTextColor(Color.WHITE);
            entry.setGravity(Gravity.CENTER);
            entry.setMinHeight((int) (SpookdType.MIN_TOUCH * density));
            int padding = (int) (SpookdType.SPACE_M * density);
            entry.setPadding(padding, 0, padding, 0);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xCC000000);
            background.setCornerRadius(SpookdType.MIN_TOUCH * density / 2);
            entry.setBackground(background);
            entry.setElevation(4 * density);
            entry.setOnClickListener(v -> open(sheet, v));
            return entry;
        }

        private FrameLayout.LayoutParams entryParams(FrameLayout gallery) {
            int margin = (int) (SpookdType.SPACE_S * gallery.getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            params.setMargins(margin, margin, margin, margin);
            return params;
        }
    }
}
