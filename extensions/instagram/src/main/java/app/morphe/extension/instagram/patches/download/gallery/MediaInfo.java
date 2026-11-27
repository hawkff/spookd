package app.morphe.extension.instagram.patches.download.gallery;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import app.morphe.extension.instagram.patches.dm.vault.ViewerMedia;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** The Media info sheet of one private gallery item: what the gallery stored, and what its file says. */
final class MediaInfo {
    private static final Executor PROBE = Executors.newSingleThreadExecutor();

    private MediaInfo() {
    }

    /** What the file says; 0 or null when it does not say. */
    static final class Probe {
        int width;
        int height;
        Long durationMs;
        String codec;

        /** Nothing could be read: not opened, or no size, length or track found. */
        boolean unreadable() {
            return width <= 0 && durationMs == null && codec == null;
        }
    }

    /**
     * Main thread: reads the file {@code open} returns on a background thread, then shows the sheet
     * with Copy link when {@code link} is set. Null or empty values are left out. Never throws.
     */
    static void show(Activity activity, Callable<ViewerMedia> open, String kind, String fileName, long size,
                     String owner, String source, String link, Long savedAt, Long postedAt) {
        PROBE.execute(() -> {
            Probe read = read(open, kind);
            activity.runOnUiThread(() -> {
                try {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        showSheet(activity, read, fileName, size, owner, source, link, savedAt, postedAt);
                    }
                } catch (Exception e) {
                    Logger.printException(() -> "Private gallery: cannot show media info", e);
                }
            });
        });
    }

    /** Probe thread: opens, reads and always closes the media; an unreadable probe when it cannot. */
    static Probe read(Callable<ViewerMedia> open, String kind) {
        Probe probe = new Probe();
        try (ViewerMedia media = open.call()) {
            if (media != null) probe = probe(media, kind);
        } catch (Exception e) {
            Logger.printInfo(() -> "Private gallery: cannot read media info", e);
        } catch (OutOfMemoryError ignored) {
        }
        return probe;
    }

    private static void showSheet(Activity activity, Probe probe, String fileName, long size, String owner,
                                  String source, String link, Long savedAt, Long postedAt) {
        List<String> rows = new ArrayList<>();
        row(rows, "spookd_gallery_info_file_name", fileName);
        row(rows, "spookd_gallery_info_size", Formatter.formatFileSize(activity, size));
        if (probe.unreadable()) rows.add(str("spookd_media_unreadable"));
        row(rows, "spookd_gallery_info_resolution",
                probe.width > 0 && probe.height > 0 ? probe.width + " × " + probe.height : null);
        row(rows, "spookd_gallery_info_duration",
                probe.durationMs == null ? null : DateUtils.formatElapsedTime(probe.durationMs / 1000));
        row(rows, "spookd_gallery_info_codec", probe.codec);
        row(rows, "spookd_gallery_info_owner", owner);
        row(rows, "spookd_gallery_info_source", source);
        row(rows, "spookd_gallery_info_link", link);
        row(rows, "spookd_gallery_info_saved", time(activity, savedAt));
        row(rows, "spookd_gallery_info_posted", time(activity, postedAt));
        AlertDialog.Builder builder = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(activity))
                .setTitle(str("spookd_gallery_media_info"))
                .setMessage(TextUtils.join("\n", rows))
                .setPositiveButton(str("spookd_ok"), null);
        if (!TextUtils.isEmpty(link)) {
            builder.setNeutralButton(str("spookd_gallery_copy_link"), (d, which) -> {
                Utils.setClipboard(link);
                Utils.showToastShort(str("spookd_copied"));
            });
        }
        AlertDialog dialog = builder.create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    private static void row(List<String> rows, String label, String value) {
        if (!TextUtils.isEmpty(value)) rows.add(str(label) + ": " + value);
    }

    private static String time(Activity activity, Long ms) {
        return ms == null || ms <= 0 ? null : DateUtils.formatDateTime(activity, ms,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME);
    }

    /** Probe thread: photo bounds and format, or a video's or audio's size, length and track formats. */
    static Probe probe(ViewerMedia media, String kind) {
        Probe out = new Probe();
        if (GalleryItem.PHOTO.equals(kind)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(media.openStream(), null, bounds);
            out.width = bounds.outWidth;
            out.height = bounds.outHeight;
            out.codec = bounds.outMimeType;
            return out;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(new PrivateGalleryActivity.Source(media));
            int width = PrivateGalleryActivity.dimension(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int height = PrivateGalleryActivity.dimension(retriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int rotation = PrivateGalleryActivity.dimension(retriever,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int[] upright = upright(width, height, rotation);
            out.width = upright[0];
            out.height = upright[1];
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            out.durationMs = duration == null ? null : Long.parseLong(duration);
        } catch (RuntimeException ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(new PrivateGalleryActivity.Source(media));
            List<String> codecs = new ArrayList<>();
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                codecs.add(format.getString(MediaFormat.KEY_MIME));
            }
            out.codec = trackTypes(codecs);
        } catch (Exception ignored) {
        } finally {
            extractor.release();
        }
        return out;
    }

    /** {width, height} as shown: a video turned a quarter either way has its sides swapped. */
    static int[] upright(int width, int height, int rotation) {
        int turn = ((rotation % 360) + 360) % 360;
        return turn == 90 || turn == 270 ? new int[]{height, width} : new int[]{width, height};
    }

    /** The tracks' MIME types in track order, such as "video/avc, audio/mp4a-latm"; null for none. */
    static String trackTypes(List<String> mimes) {
        List<String> known = new ArrayList<>();
        for (String mime : mimes) {
            if (mime != null && !mime.isEmpty()) known.add(mime);
        }
        return known.isEmpty() ? null : String.join(", ", known);
    }
}
