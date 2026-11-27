package app.morphe.extension.instagram.patches.download.gallery.editor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleFunction;
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
            int across = handle & (LEFT | RIGHT);
            int down = handle & (TOP | BOTTOM);
            double[] sideways = across == 0 ? r
                    : farthest(r, t -> moved(r, across, dx * t, 0, 0), min, w, h, degrees);
            return down == 0 ? sideways
                    : farthest(sideways, t -> moved(sideways, down, 0, dy * t, 0), min, w, h, degrees);
        }
        return farthest(r, t -> moved(r, handle, dx * t, dy * t, ratio), min, w, h, degrees);
    }

    /**
     * {@code r} {@code dWidth} wider and {@code dHeight} taller about its middle (negative narrows), as far
     * as both sides can go together, so the middle stays put; a {@code ratio} above 0 sizes the other side.
     */
    static double[] grown(double[] r, double dWidth, double dHeight, double ratio, double min,
                          double w, double h, double degrees) {
        double floor = Math.min(min, Math.min(r[2] - r[0], r[3] - r[1]));
        double cx = (r[0] + r[2]) / 2;
        double cy = (r[1] + r[3]) / 2;
        return farthest(r, t -> {
            double width = r[2] - r[0] + dWidth * t;
            double height = r[3] - r[1] + dHeight * t;
            if (ratio > 0 && dWidth != 0) height = width / ratio;
            else if (ratio > 0) width = height * ratio;
            return new double[]{cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2};
        }, floor, w, h, degrees);
    }

    /** {@code at(1)} when valid, else {@code at} of the largest valid share; {@code start} when none is. */
    private static double[] farthest(double[] start, DoubleFunction<double[]> at, double min,
                                     double w, double h, double degrees) {
        double[] whole = at.apply(1);
        if (valid(whole, min, w, h, degrees)) return whole;
        double low = 0;
        double high = 1;
        double[] best = start;
        // Every handle grows or shrinks the rect monotonically, so validity has one boundary along the drag.
        for (int i = 0; i < 24; i++) {
            double mid = (low + high) / 2;
            double[] candidate = at.apply(mid);
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
        int[] x = span((r[0] + baseWidth / 2) * scale, (r[2] + baseWidth / 2) * scale, baseWidth * scale);
        int[] y = span((r[1] + baseHeight / 2) * scale, (r[3] + baseHeight / 2) * scale, baseHeight * scale);
        return new int[]{x[0], y[0], x[1], y[1]};
    }

    /**
     * Whole pixels for {@code start}..{@code end} on an axis of {@code size}: at least one, shifted back
     * when rounding pushed them past the image or past a straightened crop's own extent.
     */
    private static int[] span(double start, double end, double size) {
        int length = Math.max(1, (int) Math.floor(end - start + SLACK));
        int low = (int) Math.min(0, Math.floor(start + SLACK));
        int high = (int) Math.max(Math.floor(size + SLACK), Math.ceil(end - SLACK));
        int from = Math.max(low, Math.min((int) Math.round(start), high - length));
        return new int[]{from, from + length};
    }

    /**
     * Which edge a touch at {@code p} grabs on one axis of a crop from {@code low} to {@code high}:
     * up to {@code slop} outside, and inside at most a third of the crop, so a thin crop keeps its
     * middle for moving. 0 when neither.
     */
    static int edgeAt(float p, float low, float high, float slop, int lowEdge, int highEdge) {
        float inner = Math.min(slop, (high - low) / 3);
        if (p >= low - slop && p <= low + inner) return lowEdge;
        if (p >= high - inner && p <= high + slop) return highEdge;
        return 0;
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
