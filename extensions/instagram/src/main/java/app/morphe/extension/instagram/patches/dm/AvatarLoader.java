/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Utils;

/** Loads small profile pictures for the deleted messages list off the main thread, cached in memory. */
final class AvatarLoader implements AutoCloseable {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int TARGET_PX = 160;
    // Shared by every screen, so reopening the list shows known pictures at once.
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(128);

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    // Main thread: the link each view wants now, and the views waiting for a link in flight.
    private final Map<ImageView, String> wanted = new WeakHashMap<>();
    private final Map<String, List<ImageView>> pending = new HashMap<>();
    private volatile boolean closed;

    /** Main thread: shows the picture at url, or clears the view when url is null or the fetch fails. */
    void load(String url, ImageView view) {
        wanted.put(view, url);
        view.setImageDrawable(null);
        if (closed || url == null) return;
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        List<ImageView> waiting = pending.get(url);
        if (waiting != null) {
            waiting.add(view);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(view);
        pending.put(url, waiting);
        workers.execute(() -> {
            Bitmap bitmap = closed ? null : fetch(url);
            if (bitmap != null) CACHE.put(url, bitmap);
            Utils.runOnMainThread(() -> {
                List<ImageView> views = pending.remove(url);
                if (closed || bitmap == null || views == null) return;
                for (ImageView target : views) {
                    if (url.equals(wanted.get(target))) target.setImageBitmap(bitmap);
                }
            });
        });
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
                    || connection.getContentLengthLong() > MAX_BYTES) return null;
            byte[] data;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (output.size() + length > MAX_BYTES) return null;
                    output.write(buffer, 0, length);
                }
                data = output.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (Math.min(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= TARGET_PX) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Main thread, when the screen is destroyed. */
    @Override
    public void close() {
        closed = true;
        workers.shutdownNow();
        pending.clear();
        wanted.clear();
    }
}
