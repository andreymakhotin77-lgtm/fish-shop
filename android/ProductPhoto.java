package ru.makeevka.fishshop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ProductPhoto extends FrameLayout {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_SIDE = 1000;

    private static final Handler MAIN =
            new Handler(Looper.getMainLooper());

    private static final ThreadPoolExecutor WORKERS =
            new ThreadPoolExecutor(
                    2,
                    2,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(60),
                    new ThreadPoolExecutor.AbortPolicy()
            );

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };

    private final ImageView photo;
    private final String imageUrl;

    private Future<?> task;
    private boolean attached;
    private int generation;

    public ProductPhoto(
            Context context,
            String productName,
            String category,
            String imageUrl
    ) {
        super(context);

        this.imageUrl =
                imageUrl == null ? "" : imageUrl.trim();

        setBackgroundColor(Color.rgb(23, 23, 23));
        setClipChildren(true);

        ProductArtwork fallback = new ProductArtwork(
                context,
                productName,
                category
        );

        addView(
                fallback,
                new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                )
        );

        photo = new ImageView(context);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setContentDescription(
                "Фото товара: " + productName
        );
        photo.setVisibility(View.INVISIBLE);

        addView(
                photo,
                new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                )
        );
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        attached = true;
        loadPhoto();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        generation++;

        if (task != null) {
            task.cancel(true);
            task = null;
        }

        super.onDetachedFromWindow();
    }

    private void loadPhoto() {
        if (imageUrl.isEmpty()) {
            return;
        }

        Bitmap cached = CACHE.get(imageUrl);

        if (cached != null) {
            showPhoto(cached);
            return;
        }

        final int requestGeneration = ++generation;

        try {
            task = WORKERS.submit(() -> {
                Bitmap bitmap = download(imageUrl);

                if (bitmap == null) {
                    return;
                }

                CACHE.put(imageUrl, bitmap);

                MAIN.post(() -> {
                    if (
                            attached &&
                            generation == requestGeneration
                    ) {
                        showPhoto(bitmap);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // При большой очереди оставляем золотую иллюстрацию.
        }
    }

    private void showPhoto(Bitmap bitmap) {
        photo.setImageBitmap(bitmap);
        photo.setVisibility(View.VISIBLE);
    }

    private static Bitmap download(String address) {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(address);

            if (
                    !"https".equalsIgnoreCase(url.getProtocol()) ||
                    url.getHost().isEmpty() ||
                    url.getUserInfo() != null
            ) {
                return null;
            }

            connection =
                    (HttpURLConnection) url.openConnection();

            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(true);
            connection.setRequestProperty(
                    "Accept",
                    "image/*"
            );

            int status = connection.getResponseCode();

            if (status != HttpURLConnection.HTTP_OK) {
                return null;
            }

            long length = connection.getContentLengthLong();

            if (length > MAX_BYTES) {
                return null;
            }

            String contentType = connection.getContentType();

            if (
                    contentType != null &&
                    !contentType.toLowerCase(java.util.Locale.ROOT)
                            .startsWith("image/")
            ) {
                return null;
            }

            byte[] bytes;

            try (
                    InputStream input =
                            connection.getInputStream();
                    ByteArrayOutputStream output =
                            new ByteArrayOutputStream()
            ) {
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;

                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        return null;
                    }

                    total += count;

                    if (total > MAX_BYTES) {
                        return null;
                    }

                    output.write(buffer, 0, count);
                }

                bytes = output.toByteArray();
            }

            BitmapFactory.Options bounds =
                    new BitmapFactory.Options();

            bounds.inJustDecodeBounds = true;

            BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.length,
                    bounds
            );

            if (
                    bounds.outWidth <= 0 ||
                    bounds.outHeight <= 0
            ) {
                return null;
            }

            int sample = 1;

            while (
                    bounds.outWidth / sample > MAX_SIDE ||
                    bounds.outHeight / sample > MAX_SIDE
            ) {
                sample *= 2;
            }

            BitmapFactory.Options options =
                    new BitmapFactory.Options();

            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;

            return BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.length,
                    options
            );

        } catch (Exception ignored) {
            return null;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
// КОНЕЦ ФАЙЛА
