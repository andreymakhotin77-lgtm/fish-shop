package ru.makeevka.fishshop;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ShopApi {

    private static final String BASE_URL =
            "https://fish-shop-tttj.onrender.com";

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final Handler main =
            new Handler(Looper.getMainLooper());

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    public interface Callback {
        void onSuccess(JSONObject data);
        void onError(ApiError error);
    }

    public static final class ApiError extends Exception {

        public final int statusCode;

        public ApiError(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public boolean shouldClearPendingOrder() {
            return statusCode == 400
                    || statusCode == 409
                    || statusCode == 413
                    || statusCode == 415;
        }
    }

    public void loadProducts(Callback callback) {
        request("GET", "/api/products", null, callback);
    }

    public void sendOrder(JSONObject order, Callback callback) {
        request("POST", "/api/orders", order, callback);
    }

    private void request(
            String method,
            String endpoint,
            JSONObject body,
            Callback callback
    ) {
        // Создаём снимок тела до запуска фонового потока.
        final String payload = body == null ? null : body.toString();

        worker.execute(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(BASE_URL + endpoint);

                connection =
                        (HttpURLConnection) url.openConnection();

                connection.setRequestMethod(method);
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(120000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);

                connection.setRequestProperty(
                        "Accept",
                        "application/json"
                );

                if (payload != null) {
                    byte[] bytes = payload.getBytes(
                            StandardCharsets.UTF_8
                    );

                    connection.setDoOutput(true);

                    connection.setRequestProperty(
                            "Content-Type",
                            "application/json; charset=utf-8"
                    );

                    connection.setFixedLengthStreamingMode(
                            bytes.length
                    );

                    try (OutputStream output =
                                 connection.getOutputStream()) {
                        output.write(bytes);
                    }
                }

                int status = connection.getResponseCode();

                InputStream stream =
                        status >= 200 && status < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream();

                String response = readResponse(stream);

                JSONObject data;

                try {
                    data = new JSONObject(response);
                } catch (Exception invalidJson) {
                    throw new ApiError(
                            status,
                            "Сервер вернул неожиданный ответ. " +
                            "Попробуйте позже."
                    );
                }

                if (status < 200 || status >= 300) {
                    throw new ApiError(
                            status,
                            data.optString(
                                    "error",
                                    "Ошибка сервера: " + status
                            )
                    );
                }

                main.post(() -> callback.onSuccess(data));

            } catch (ApiError error) {
                main.post(() -> callback.onError(error));

            } catch (Exception error) {
                ApiError networkError = new ApiError(
                        0,
                        "Не удалось получить ответ сервера. " +
                        "Проверьте интернет. Первый запрос после " +
                        "простоя может занять около минуты. " +
                        "Если вы отправляли заказ, его результат " +
                        "пока не подтверждён."
                );

                main.post(() -> callback.onError(networkError));

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private String readResponse(InputStream stream)
            throws Exception {

        if (stream == null) {
            return "";
        }

        try (
                InputStream input = stream;
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;

            while ((count = input.read(buffer)) != -1) {
                total += count;

                if (total > MAX_RESPONSE_BYTES) {
                    throw new ApiError(
                            0,
                            "Ответ сервера слишком большой."
                    );
                }

                output.write(buffer, 0, count);
            }

            return new String(
                    output.toByteArray(),
                    StandardCharsets.UTF_8
            );
        }
    }

    public void close() {
        worker.shutdown();
    }
}
