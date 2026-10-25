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

public class ShopApi {

    private static final String BASE_URL =
            "https://fish-shop-tttj.onrender.com";

    private static final int MAX_RESPONSE_BYTES =
            2 * 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 120000;

    private static final String ORDER_ID_PATTERN =
            "LV-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
            "[0-9a-f]{4}-[0-9a-f]{12}";

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

    public static class ApiError extends Exception {

        public final int statusCode;

        public ApiError(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public boolean shouldClearPendingOrder() {
            /*
             * Конфликт 409 не означает, что заказ не создан.
             * Такой запрос нельзя автоматически заменять новым.
             */
            return statusCode == 400 ||
                    statusCode == 413 ||
                    statusCode == 415;
        }
    }

    // -------------------- КАТАЛОГ --------------------

    public void loadProducts(Callback callback) {
        request(
                "GET",
                "/api/products",
                null,
                callback
        );
    }

    // -------------------- РАСПИСАНИЕ --------------------

    /*
     * Загружает все доступные будущие интервалы.
     * В ответе находятся:
     * slots — массив интервалов;
     * scheduleOptions — настройки расписания.
     */
    public void loadReceivingSlots(Callback callback) {
        request(
                "GET",
                "/api/receiving-slots",
                null,
                callback
        );
    }

    /*
     * Загружает интервалы только для выбранного
     * способа получения: delivery или pickup.
     */
    public void loadReceivingSlots(
            String fulfillment,
            Callback callback
    ) {
        if (
                !"delivery".equals(fulfillment) &&
                !"pickup".equals(fulfillment)
        ) {
            postError(
                    callback,
                    new ApiError(
                            0,
                            "Выберите доставку или самовывоз."
                    )
            );
            return;
        }

        request(
                "GET",
                "/api/receiving-slots?fulfillment=" +
                        fulfillment,
                null,
                callback
        );
    }

    // -------------------- ОФОРМЛЕНИЕ --------------------

    public void sendOrder(
            JSONObject order,
            Callback callback
    ) {
        /*
         * Поля заказа передаются без изменения.
         * В том числе receivingSlotId и orderAccessToken.
         */
        request(
                "POST",
                "/api/orders",
                order,
                callback
        );
    }

    // -------------------- ПРОВЕРКА ЗАКАЗА --------------------

    public void loadOrder(
            String orderId,
            String orderAccessToken,
            Callback callback
    ) {
        if (
                orderId == null ||
                !orderId.matches(ORDER_ID_PATTERN) ||
                orderAccessToken == null ||
                !orderAccessToken.matches("[0-9a-f]{64}")
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
            body.put("orderAccessToken", orderAccessToken);

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
                            "проверку заказа."
                    )
            );
        }
    }

    // -------------------- HTTP --------------------

    private void request(
            String method,
            String route,
            JSONObject body,
            Callback callback
    ) {
        if (callback == null) {
            throw new IllegalArgumentException(
                    "Не задан обработчик ответа."
            );
        }

        if (closed) {
            postClosedError(callback);
            return;
        }

        /*
         * Создаём снимок тела до запуска фонового потока.
         * Позднейшие изменения JSONObject не изменят запрос.
         */
        final String payload =
                body == null ? null : body.toString();

        try {
            worker.execute(() -> {
                if (closed) return;

                HttpURLConnection connection = null;

                try {
                    URL url = new URL(BASE_URL + route);

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
                    } catch (Exception error) {
                        /*
                         * Не очищаем неподтверждённый заказ,
                         * если ответ нельзя достоверно прочитать.
                         */
                        throw new ApiError(
                                0,
                                "Сервер вернул неожиданный ответ. " +
                                "Попробуйте позже. Если вы отправляли " +
                                "заказ, его результат пока " +
                                "не подтверждён."
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
                                    "после простоя может занять около " +
                                    "минуты. Если вы отправляли заказ, " +
                                    "его результат пока не подтверждён."
                            )
                    );

                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }

                    if (activeConnection == connection) {
                        activeConnection = null;
                    }
                }
            });

        } catch (RejectedExecutionException error) {
            if (closed) {
                postClosedError(callback);
            } else {
                postError(
                        callback,
                        new ApiError(
                                0,
                                "Не удалось начать запрос. " +
                                "Откройте приложение заново."
                        )
                );
            }
        }
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
            int total = 0;
            int count;

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

    private String errorMessage(
            JSONObject data,
            int status
    ) {
        Object value = data.opt("error");

        if (value instanceof String) {
            String message =
                    ((String) value).trim();

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
        main.post(() -> {
            if (!closed) {
                callback.onError(error);
            }
        });
    }

    private void postClosedError(Callback callback) {
        /*
         * Отдельно сообщаем о попытке нового запроса
         * через уже закрытый клиент.
         */
        main.post(() -> callback.onError(
                new ApiError(
                        0,
                        "Подключение уже закрыто. " +
                        "Откройте приложение заново."
                )
        ));
    }

    // -------------------- ЗАКРЫТИЕ --------------------

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
