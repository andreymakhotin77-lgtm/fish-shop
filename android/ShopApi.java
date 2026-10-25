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
import java.util.concurrent.RejectedExecutionException;

public final class ShopApi {

    private static final String BASE_URL =
            "https://fish-shop-tttj.onrender.com";

    private static final int MAX_RESPONSE_BYTES =
            2 * 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 120000;

    private final Handler main =
            new Handler(Looper.getMainLooper());

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    private volatile boolean closed = false;

    private volatile HttpURLConnection activeConnection;

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

        /*
         * Конфликт 409 не удаляет неподтверждённый заказ:
         * он требует проверки, а не нового оформления.
         */
        public boolean shouldClearPendingOrder() {
            return statusCode == 400
                    || statusCode == 413
                    || statusCode == 415;
        }
    }

    public void loadProducts(Callback callback) {
        request(
                "GET",
                "/api/products",
                null,
                callback
        );
    }

    public void sendOrder(
            JSONObject order,
            Callback callback
    ) {
        /*
         * Всё тело передаётся без изменения.
         * В новой версии интерфейса здесь также будет
         * поле orderAccessToken — секретный ключ заказа.
         */
        request(
                "POST",
                "/api/orders",
                order,
                callback
        );
    }

    /*
     * Получить сведения об одном заказе.
     *
     * Ключ передаётся в JSON по HTTPS.
     * Он не добавляется в адресную строку.
     *
     * Этот метод ничего не создаёт
     * и не меняет статус заказа.
     */
    public void loadOrder(
            String orderId,
            String orderAccessToken,
            Callback callback
    ) {
        if (callback == null || closed) {
            return;
        }

        if (
                orderId == null
                        || !orderId.matches(
                        "LV-[0-9a-f]{8}-[0-9a-f]{4}-" +
                        "[0-9a-f]{4}-[0-9a-f]{4}-" +
                        "[0-9a-f]{12}"
                )
                        || orderAccessToken == null
                        || !orderAccessToken.matches(
                        "[0-9a-f]{64}"
                )
        ) {
            postError(
                    callback,
                    new ApiError(
                            0,
                            "На телефоне нет корректного ключа " +
                            "просмотра этого заказа. " +
                            "Уточните статус у магазина."
                    )
            );
            return;
        }

        try {
            JSONObject body = new JSONObject();

            body.put("id", orderId);
            body.put(
                    "orderAccessToken",
                    orderAccessToken
            );

            request(
                    "POST",
                    "/api/orders/lookup",
                    body,
                    callback
            );

        } catch (Exception error) {
            postError(
                    callback,
                    new ApiError(
                            0,
                            "Не удалось подготовить " +
                            "проверку статуса заказа."
                    )
            );
        }
    }

    private void request(
            String method,
            String endpoint,
            JSONObject body,
            Callback callback
    ) {
        if (callback == null || closed) {
            return;
        }

        /*
         * Создаём снимок тела до запуска фонового потока.
         * Изменения исходного JSONObject после этого
         * не изменят отправляемый запрос.
         */
        final String payload =
                body == null ? null : body.toString();

        try {
            worker.execute(() -> {
                if (closed) return;

                HttpURLConnection connection = null;

                try {
                    URL url = new URL(
                            BASE_URL + endpoint
                    );

                    connection =
                            (HttpURLConnection)
                                    url.openConnection();

                    activeConnection = connection;

                    if (closed) return;

                    connection.setRequestMethod(method);

                    connection.setConnectTimeout(
                            CONNECT_TIMEOUT_MS
                    );

                    connection.setReadTimeout(
                            READ_TIMEOUT_MS
                    );

                    connection.setInstanceFollowRedirects(false);
                    connection.setUseCaches(false);

                    connection.setRequestProperty(
                            "Accept",
                            "application/json"
                    );

                    connection.setRequestProperty(
                            "Cache-Control",
                            "no-store"
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

                        try (
                                OutputStream output =
                                        connection.getOutputStream()
                        ) {
                            output.write(bytes);
                        }
                    }

                    int status =
                            connection.getResponseCode();

                    InputStream stream =
                            status >= 200 && status < 300
                                    ? connection.getInputStream()
                                    : connection.getErrorStream();

                    String response =
                            readResponse(stream);

                    JSONObject data;

                    try {
                        data = new JSONObject(response);

                    } catch (Exception invalidJson) {
                        /*
                         * Не считаем неожиданный ответ
                         * подтверждённым отказом магазина.
                         * Поэтому statusCode = 0:
                         * неподтверждённый заказ сохраняется.
                         */
                        throw new ApiError(
                                0,
                                "Сервер вернул неожиданный ответ. " +
                                "Если вы отправляли заказ, " +
                                "его результат пока не подтверждён. " +
                                "Используйте проверку прежнего " +
                                "заказа, а не новое оформление."
                        );
                    }

                    if (status < 200 || status >= 300) {
                        throw new ApiError(
                                status,
                                errorMessage(data, status)
                        );
                    }

                    postSuccess(callback, data);

                } catch (ApiError error) {
                    postError(callback, error);

                } catch (Exception error) {
                    postError(
                            callback,
                            new ApiError(
                                    0,
                                    "Не удалось получить ответ сервера. " +
                                    "Проверьте интернет. Первый запрос " +
                                    "после простоя может занять " +
                                    "около минуты. Если вы отправляли " +
                                    "заказ, его результат пока " +
                                    "не подтверждён. Если проверяли " +
                                    "статус — повторите проверку позже."
                            )
                    );

                } finally {
                    if (connection != null) {
                        if (activeConnection == connection) {
                            activeConnection = null;
                        }

                        connection.disconnect();
                    }
                }
            });

        } catch (RejectedExecutionException error) {
            postError(
                    callback,
                    new ApiError(
                            0,
                            "Подключение уже закрыто. " +
                            "Откройте приложение заново."
                    )
            );
        }
    }

    private String errorMessage(
            JSONObject data,
            int status
    ) {
        Object value = data.opt("error");

        if (value instanceof String) {
            String message = ((String) value).trim();

            if (!message.isEmpty()) {
                return message.length() > 1500
                        ? message.substring(0, 1500)
                        : message;
            }
        }

        return "Ошибка сервера: " + status;
    }

    private void postSuccess(
            Callback callback,
            JSONObject data
    ) {
        if (closed) return;

        main.post(() -> {
            if (!closed) {
                callback.onSuccess(data);
            }
        });
    }

    private void postError(
            Callback callback,
            ApiError error
    ) {
        if (closed) return;

        main.post(() -> {
            if (!closed) {
                callback.onError(error);
            }
        });
    }

    private String readResponse(
            InputStream stream
    ) throws Exception {
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
                            "Ответ сервера слишком большой. " +
                            "Результат запроса не подтверждён."
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
        closed = true;

        worker.shutdownNow();

        HttpURLConnection connection =
                activeConnection;

        if (connection != null) {
            connection.disconnect();
        }
    }
}
// КОНЕЦ ФАЙЛА
