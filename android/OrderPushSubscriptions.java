package ru.makeevka.fishshop;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public final class OrderPushSubscriptions {

    private static final String HISTORY_KEY =
            "orders_history_v1";

    private static final String PUSH_PREFS =
            "lavka_push_v1";

    private static final String TOKEN_KEY =
            "fcm_token";

    private static final String ACK_KEY =
            "order_subscriptions_v1";

    private static final String ORDER_PATTERN =
            "LV-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
            "[0-9a-f]{4}-[0-9a-f]{12}";

    private static final long ACK_LIFETIME_MS =
            24L * 60L * 60L * 1000L;

    private final Context context;
    private final SharedPreferences historyPrefs;
    private final SharedPreferences pushPrefs;
    private final ShopApi api = new ShopApi();

    private final Handler main =
            new Handler(Looper.getMainLooper());

    private final Map<String, Long> retryAt =
            new HashMap<>();

    private final Map<String, Integer> failures =
            new HashMap<>();

    private final Runnable syncTask = this::syncNext;

    private boolean started;
    private boolean closed;
    private boolean requestRunning;
    private boolean tokenLoading;
    private boolean successNoticeShown;
    private boolean errorNoticeShown;

    private long lastTokenRequest;
    private long nextNetworkRequest;

    private String currentToken = "";

    private final SharedPreferences
            .OnSharedPreferenceChangeListener historyListener =
            (preferences, key) -> {
                if (key == null || HISTORY_KEY.equals(key)) {
                    schedule(500);
                }
            };

    private final SharedPreferences
            .OnSharedPreferenceChangeListener tokenListener =
            (preferences, key) -> {
                if (key == null || TOKEN_KEY.equals(key)) {
                    main.post(() -> {
                        if (closed) return;

                        String token = pushPrefs.getString(
                                TOKEN_KEY,
                                ""
                        );

                        if (validToken(token)) {
                            currentToken = token;
                            schedule(500);
                        }
                    });
                }
            };

    public OrderPushSubscriptions(Activity activity) {
        context = activity.getApplicationContext();

        /*
         * StoreActivity использует getPreferences().
         * Получаем то же хранилище через MainActivity,
         * не меняя имя и расположение истории.
         */
        historyPrefs = activity.getPreferences(
                Context.MODE_PRIVATE
        );

        pushPrefs = context.getSharedPreferences(
                PUSH_PREFS,
                Context.MODE_PRIVATE
        );
    }

    public void start() {
        if (closed || started) return;

        started = true;

        historyPrefs.registerOnSharedPreferenceChangeListener(
                historyListener
        );

        pushPrefs.registerOnSharedPreferenceChangeListener(
                tokenListener
        );

        refresh();
    }

    public void refresh() {
        if (closed || !started) return;

        if (!notificationsAllowed()) {
            return;
        }

        refreshFirebaseToken();
        schedule(500);
    }

    private boolean notificationsAllowed() {
        if (
                Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false;
        }

        NotificationManager manager =
                (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE
                );

        if (
                manager == null ||
                !manager.areNotificationsEnabled()
        ) {
            return false;
        }

        android.app.NotificationChannel channel =
                manager.getNotificationChannel(
                        "lavka_orders_v1"
                );

        return channel == null ||
                channel.getImportance() !=
                        NotificationManager.IMPORTANCE_NONE;
    }

    private boolean validToken(String token) {
        return token != null &&
                token.length() >= 20 &&
                token.length() <= 4096 &&
                token.matches("[A-Za-z0-9_:\\-]+");
    }

    private void refreshFirebaseToken() {
        if (closed || tokenLoading) return;

        long now = System.currentTimeMillis();

        if (
                lastTokenRequest > 0 &&
                now - lastTokenRequest < 60000
        ) {
            return;
        }

        tokenLoading = true;
        lastTokenRequest = now;

        FirebaseMessaging.getInstance()
                .getToken()
                .addOnCompleteListener(task -> {
                    if (closed) return;

                    tokenLoading = false;

                    if (!task.isSuccessful()) {
                        /*
                         * Не мешаем каталогу и оформлению.
                         * Позже попробуем получить адрес снова.
                         */
                        schedule(60000);
                        return;
                    }

                    String token = task.getResult();

                    if (!validToken(token)) {
                        schedule(60000);
                        return;
                    }

                    currentToken = token;

                    pushPrefs.edit()
                            .putString(TOKEN_KEY, token)
                            .apply();

                    schedule(500);
                });
    }

    private void schedule(long delayMs) {
        if (closed || !started) return;

        main.removeCallbacks(syncTask);
        main.postDelayed(syncTask, delayMs);
    }

    private String fingerprint(String token)
            throws Exception {
        byte[] digest = MessageDigest
                .getInstance("SHA-256")
                .digest(
                        token.getBytes(StandardCharsets.UTF_8)
                );

        StringBuilder result = new StringBuilder();

        for (byte value : digest) {
            result.append(
                    Character.forDigit(
                            (value >>> 4) & 15,
                            16
                    )
            );

            result.append(
                    Character.forDigit(
                            value & 15,
                            16
                    )
            );
        }

        return result.toString();
    }

    private boolean activeStatus(String status) {
        return "new".equals(status) ||
                "confirmed".equals(status) ||
                "preparing".equals(status) ||
                "delivering".equals(status) ||
                "ready".equals(status);
    }

    private boolean validOrder(JSONObject order) {
        return order != null &&
                order.optBoolean(
                        "trackingAvailable",
                        false
                ) &&
                order.optString("id")
                        .matches(ORDER_PATTERN) &&
                order.optString("orderAccessToken")
                        .matches("[0-9a-f]{64}") &&
                activeStatus(
                        order.optString("status")
                );
    }

    private JSONObject acknowledgements() {
        try {
            return new JSONObject(
                    pushPrefs.getString(ACK_KEY, "{}")
            );
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private boolean alreadyRegistered(
            JSONObject saved,
            String id,
            String tokenFingerprint
    ) {
        JSONObject entry = saved.optJSONObject(id);

        if (entry == null) return false;

        long checkedAt = entry.optLong("checkedAt", 0);
        long age = System.currentTimeMillis() - checkedAt;

        return tokenFingerprint.equals(
                entry.optString("tokenFingerprint")
        ) &&
                age >= 0 &&
                age < ACK_LIFETIME_MS;
    }

    private void saveAcknowledgement(
            String id,
            String tokenFingerprint
    ) {
        try {
            JSONObject saved = acknowledgements();
            JSONObject entry = new JSONObject();

            entry.put(
                    "tokenFingerprint",
                    tokenFingerprint
            );

            entry.put(
                    "checkedAt",
                    System.currentTimeMillis()
            );

            saved.put(id, entry);

            /*
             * Не записываем ключи заказов повторно.
             * В этом кэше только ID, отпечаток адреса
             * устройства и время проверки.
             */
            pushPrefs.edit()
                    .putString(ACK_KEY, saved.toString())
                    .apply();

        } catch (Exception ignored) {
            /*
             * Если кэш не сохранён, подписка будет
             * проверена повторно. Сервер обрабатывает
             * повторную регистрацию без нового заказа.
             */
        }
    }

    private void syncNext() {
        if (
                closed ||
                !started ||
                requestRunning
        ) {
            return;
        }

        if (!notificationsAllowed()) {
            return;
        }

        if (!validToken(currentToken)) {
            refreshFirebaseToken();
            schedule(60000);
            return;
        }

        long now = System.currentTimeMillis();

        if (now < nextNetworkRequest) {
            schedule(nextNetworkRequest - now);
            return;
        }

        final JSONArray history;
        final String tokenFingerprint;

        try {
            history = new JSONArray(
                    historyPrefs.getString(
                            HISTORY_KEY,
                            "[]"
                    )
            );

            tokenFingerprint = fingerprint(
                    currentToken
            );

        } catch (Exception ignored) {
            /*
             * Повреждённую историю не очищаем
             * и не перезаписываем.
             */
            schedule(60000);
            return;
        }

        JSONObject saved = acknowledgements();

        for (int i = 0; i < history.length(); i++) {
            JSONObject order = history.optJSONObject(i);

            if (!validOrder(order)) {
                continue;
            }

            String id = order.optString("id");

            if (alreadyRegistered(
                    saved,
                    id,
                    tokenFingerprint
            )) {
                continue;
            }

            String attemptKey =
                    id + ":" + tokenFingerprint;

            Long nextTry = retryAt.get(attemptKey);

            if (nextTry != null && now < nextTry) {
                continue;
            }

            register(
                    id,
                    order.optString("orderAccessToken"),
                    currentToken,
                    tokenFingerprint,
                    attemptKey
            );

            return;
        }

        /*
         * Повторная проверка во время жизни экрана.
         * Заказы при этом не создаются.
         */
        schedule(60000);
    }

    private void register(
            String orderId,
            String accessToken,
            String deviceToken,
            String tokenFingerprint,
            String attemptKey
    ) {
        requestRunning = true;

        api.subscribeOrderPush(
                orderId,
                accessToken,
                deviceToken,
                new ShopApi.Callback() {
                    @Override
                    public void onSuccess(JSONObject data) {
                        if (closed) return;

                        requestRunning = false;

                        if (
                                !data.optBoolean("ok", false) ||
                                !data.optBoolean(
                                        "subscribed",
                                        false
                                )
                        ) {
                            failed(
                                    attemptKey,
                                    new ShopApi.ApiError(
                                            0,
                                            "Подписка не подтверждена."
                                    )
                            );
                            return;
                        }

                        saveAcknowledgement(
                                orderId,
                                tokenFingerprint
                        );

                        retryAt.remove(attemptKey);
                        failures.remove(attemptKey);

                        if (!successNoticeShown) {
                            successNoticeShown = true;

                            Toast.makeText(
                                    context,
                                    "Уведомления о заказах подключены",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }

                        /*
                         * Не больше одного нового запроса
                         * подписки за несколько секунд.
                         */
                        nextNetworkRequest =
                                System.currentTimeMillis() + 3000;

                        schedule(3000);
                    }

                    @Override
                    public void onError(
                            ShopApi.ApiError error
                    ) {
                        if (closed) return;

                        requestRunning = false;
                        failed(attemptKey, error);
                    }
                }
        );
    }

    private void failed(
            String attemptKey,
            ShopApi.ApiError error
    ) {
        Integer previous = failures.get(attemptKey);

        int attempt = previous == null
                ? 1
                : Math.min(previous + 1, 10);

        failures.put(attemptKey, attempt);

        long delay;

        if (
                error.statusCode == 400 ||
                error.statusCode == 401 ||
                error.statusCode == 403 ||
                error.statusCode == 404 ||
                error.statusCode == 409 ||
                error.statusCode == 413 ||
                error.statusCode == 415
        ) {
            delay = 60L * 60L * 1000L;
        } else {
            delay = Math.min(
                    15L * 60L * 1000L,
                    30000L * (1L << Math.min(attempt - 1, 5))
            );
        }

        retryAt.put(
                attemptKey,
                System.currentTimeMillis() + delay
        );

        nextNetworkRequest =
                System.currentTimeMillis() + 3000;

        if (!errorNoticeShown) {
            errorNoticeShown = true;

            Toast.makeText(
                    context,
                    "Не удалось подключить уведомления " +
                            "для одного из заказов. " +
                            "Статус можно проверить в истории.",
                    Toast.LENGTH_LONG
            ).show();
        }

        /*
         * Ошибка подписки не удаляет заказ,
         * его секретный ключ, историю или корзину.
         */
        schedule(3000);
    }

    public void close() {
        if (closed) return;

        closed = true;

        main.removeCallbacks(syncTask);

        if (started) {
            historyPrefs
                    .unregisterOnSharedPreferenceChangeListener(
                            historyListener
                    );

            pushPrefs
                    .unregisterOnSharedPreferenceChangeListener(
                            tokenListener
                    );
        }

        api.close();
    }
}
// КОНЕЦ ФАЙЛА
