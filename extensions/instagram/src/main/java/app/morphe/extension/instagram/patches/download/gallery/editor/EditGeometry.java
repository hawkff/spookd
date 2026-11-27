package app.morphe.extension.instagram.patches.download.gallery.editor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Crop math of the photo editor, in the straightened frame: the origin is the image center, units
 * are source pixels, y points down, and the image is a {@code w} by {@code h} rectangle turned by
 * {@code degrees} clockwise. Rects are {left, top, right, bottom}.
 */
final class EditGeometry {
    static final int LEFT = 1;
    static final int TOP = 2;
    static final int RIGHT = 4;
    static final int BOTTOM = 8;
    /** Every edge moves by the same amount. */
    static final int MOVE = LEFT | TOP | RIGHT | BOTTOM;
    // Rounding slack, in source pixels.
    private static final double SLACK = 1e-3;
    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,8}$");
    private static final String EDITED = "_edited";

    private EditGeometry() {
    }

    /** Whether every corner of {@code r} lies inside the turned image. */
    static boolean inside(double[] r, double w, double h, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        for (int i = 0; i < 4; i++) {
            double x = i % 2 == 0 ? r[0] : r[2];
            double y = i < 2 ? r[1] : r[3];
            if (Math.abs(x * cos + y * sin) > w / 2 + SLACK || Math.abs(-x * sin + y * cos) > h / 2 + SLACK) {
                return false;
            }
        }
        return true;
    }

    /** {@code r} scaled about the image center just enough to lie inside the turned image; as is when it already does. */
    static double[] fit(double[] r, double w, double h, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double cx = (r[0] + r[2]) / 2;
        double cy = (r[1] + r[3]) / 2;
        double a = (r[2] - r[0]) / 2;
        double b = (r[3] - r[1]) / 2;
        double u = Math.abs(cx * cos + cy * sin) + a * Math.abs(cos) + b * Math.abs(sin);
        double v = Math.abs(-cx * sin + cy * cos) + a * Math.abs(sin) + b * Math.abs(cos);
        double k = Math.min(1, Math.min(w / 2 / u, h / 2 / v));
        return new double[]{r[0] * k, r[1] * k, r[2] * k, r[3] * k};
    }

    /** The largest centered rect whose width over height is {@code ratio}, inside the turned image. */
    static double[] largest(double ratio, double w, double h, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.abs(Math.cos(rad));
        double sin = Math.abs(Math.sin(rad));
        double b = Math.min(w / 2 / (ratio * cos + sin), h / 2 / (ratio * sin + cos));
        double a = ratio * b;
        return new double[]{-a, -b, a, b};
    }

    /**
     * {@code r} with the edges in {@code handle} moved by {@code dx}, {@code dy}, as far as it stays
     * inside the turned image with both sides at least {@code min}. A {@code ratio} above 0 keeps width
     * over height: a corner follows the drag along its diagonal and an edge resizes about the middle of
     * the other axis. Without one, and for {@link #MOVE}, each axis stops at the image on its own.
     * {@code min} drops to the shorter side of {@code r}, so a crop that straightening fitted below it
     * stays editable.
     */
    static double[] drag(double[] r, int handle, double dx, double dy, double ratio, double min,
                         double w, double h, double degrees) {
        min = Math.min(min, Math.min(r[2] - r[0], r[3] - r[1]));
        if (handle == MOVE || ratio <= 0) {
            double[] across = farthest(r, handle & (LEFT | RIGHT), dx, 0, 0, min, w, h, degrees);
            return farthest(across, handle & (TOP | BOTTOM), 0, dy, 0, min, w, h, degrees);
        }
        return farthest(r, handle, dx, dy, ratio, min, w, h, degrees);
    }

    /** {@code r} {@code dWidth} wider and {@code dHeight} taller about its middle (negative narrows), clamped like {@link #drag}. */
    static double[] grown(double[] r, double dWidth, double dHeight, double ratio, double min,
                          double w, double h, double degrees) {
        double[] out = r;
        if (dWidth != 0) {
            out = drag(out, LEFT, -dWidth / 2, 0, ratio, min, w, h, degrees);
            out = drag(out, RIGHT, dWidth / 2, 0, ratio, min, w, h, degrees);
        }
        if (dHeight != 0) {
            out = drag(out, TOP, 0, -dHeight / 2, ratio, min, w, h, degrees);
            out = drag(out, BOTTOM, 0, dHeight / 2, ratio, min, w, h, degrees);
        }
        return out;
    }

    /** The rect of the largest share of the drag that is still valid; {@code r} itself when none is. */
    private static double[] farthest(double[] r, int handle, double dx, double dy, double ratio, double min,
                                     double w, double h, double degrees) {
        if (handle == 0) return r;
        double[] whole = moved(r, handle, dx, dy, ratio);
        if (valid(whole, min, w, h, degrees)) return whole;
        double low = 0;
        double high = 1;
        double[] best = r;
        // Every handle grows or shrinks the rect monotonically, so validity has one boundary along the drag.
        for (int i = 0; i < 24; i++) {
            double mid = (low + high) / 2;
            double[] candidate = moved(r, handle, dx * mid, dy * mid, ratio);
            if (valid(candidate, min, w, h, degrees)) {
                low = mid;
                best = candidate;
            } else {
                high = mid;
            }
        }
        return best;
    }

    private static boolean valid(double[] r, double min, double w, double h, double degrees) {
        return r[2] - r[0] >= min - SLACK && r[3] - r[1] >= min - SLACK && inside(r, w, h, degrees);
    }

    private static double[] moved(double[] r, int handle, double dx, double dy, double ratio) {
        if (ratio <= 0 || handle == MOVE) {
            return new double[]{
                    r[0] + ((handle & LEFT) != 0 ? dx : 0),
                    r[1] + ((handle & TOP) != 0 ? dy : 0),
                    r[2] + ((handle & RIGHT) != 0 ? dx : 0),
                    r[3] + ((handle & BOTTOM) != 0 ? dy : 0)};
        }
        double width = r[2] - r[0];
        double height = r[3] - r[1];
        boolean horizontal = (handle & (LEFT | RIGHT)) != 0;
        boolean vertical = (handle & (TOP | BOTTOM)) != 0;
        double newWidth = width + ((handle & LEFT) != 0 ? -dx : (handle & RIGHT) != 0 ? dx : 0);
        double newHeight = height + ((handle & TOP) != 0 ? -dy : (handle & BOTTOM) != 0 ? dy : 0);
        if (horizontal && vertical) {
            double k = (newWidth * width + newHeight * height) / (width * width + height * height);
            newWidth = width * k;
            newHeight = newWidth / ratio;
        } else if (horizontal) {
            newHeight = newWidth / ratio;
        } else {
            newWidth = newHeight * ratio;
        }
        double left = (handle & LEFT) != 0 ? r[2] - newWidth
                : (handle & RIGHT) != 0 ? r[0] : (r[0] + r[2] - newWidth) / 2;
        double top = (handle & TOP) != 0 ? r[3] - newHeight
                : (handle & BOTTOM) != 0 ? r[1] : (r[1] + r[3] - newHeight) / 2;
        return new double[]{left, top, left + newWidth, top + newHeight};
    }

    /**
     * {left, top, right, bottom} in whole output pixels of crop {@code r} drawn at {@code scale} output
     * pixels per source pixel, counted from the turned image's top left; the size is rounded down, so an
     * unstraightened crop never reaches past the image, and is at least one pixel.
     */
    static int[] outputRect(double[] r, double baseWidth, double baseHeight, double scale) {
        int left = (int) Math.round((r[0] + baseWidth / 2) * scale);
        int top = (int) Math.round((r[1] + baseHeight / 2) * scale);
        int width = Math.max(1, (int) Math.floor((r[2] - r[0]) * scale + SLACK));
        int height = Math.max(1, (int) Math.floor((r[3] - r[1]) * scale + SLACK));
        return new int[]{left, top, left + width, top + height};
    }

    /** {@code r} after the frame turns 90 degrees clockwise. */
    static double[] turned(double[] r) {
        return new double[]{-r[3], r[0], -r[1], r[2]};
    }

    /** {@code r} after the frame is mirrored left to right. */
    static double[] mirrored(double[] r) {
        return new double[]{-r[2], r[1], -r[0], r[3]};
    }

    /** {quarter turns clockwise, 1 when then mirrored} that show a photo with this EXIF orientation upright. */
    static int[] orientation(int exif) {
        switch (exif) {
            case 2: return new int[]{0, 1};
            case 3: return new int[]{2, 0};
            case 4: return new int[]{2, 1};
            case 5: return new int[]{1, 1};
            case 6: return new int[]{1, 0};
            case 7: return new int[]{3, 1};
            case 8: return new int[]{3, 0};
            default: return new int[]{0, 0};
        }
    }

    /**
     * The power-of-two decode step for the preview: still at least screen size however the photo is
     * turned by quarters.
     */
    static int previewSample(int width, int height, int screenWidth, int screenHeight) {
        double needed = Math.max(Math.min((double) screenWidth / width, (double) screenHeight / height),
                Math.min((double) screenWidth / height, (double) screenHeight / width));
        int sample = 1;
        while (sample * 2 * needed <= 1) sample *= 2;
        return sample;
    }

    /** The smallest power-of-two decode step that keeps a {@code width} by {@code height} photo within {@code pixels}. */
    static int budgetSample(int width, int height, long pixels) {
        int sample = 1;
        while ((long) (width / sample) * (height / sample) > Math.max(1, pixels) && sample < 1 << 10) sample *= 2;
        return sample;
    }

    /**
     * A map that keeps the {@code capacity} most recently used entries and drops the oldest beyond that;
     * deleted or replaced photos never reuse a path, so their entries age out.
     */
    static <K, V> Map<K, V> recent(int capacity) {
        return new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }

    /** The edit's name: the original's with {@code _edited} once, and the extension of what is saved. */
    static String editedName(String fileName, boolean png) {
        String base = fileName == null || fileName.isEmpty() ? "photo" : EXTENSION.matcher(fileName).replaceFirst("");
        if (!base.endsWith(EDITED)) base += EDITED;
        return base + (png ? ".png" : ".jpg");
    }
}
