package ru.makeevka.fishshop;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.widget.Toast;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public final class PushNotifications {

    public static final int PERMISSION_REQUEST = 7401;

    private static final String CHANNEL_ID = "lavka_orders_v1";
    private static final String PREFS = "lavka_push_v1";
    private static final String TOKEN_KEY = "fcm_token";
    private static final String SHOWN_TOKEN_KEY = "shown_test_token";

    private PushNotifications() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }

    public static void createChannel(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE
                );

        if (manager == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Заказы «Лавка Вкуса»",
                NotificationManager.IMPORTANCE_DEFAULT
        );

        channel.setDescription(
                "Уведомления магазина и изменения статуса заказов"
        );

        manager.createNotificationChannel(channel);
    }

    public static void initialize(Activity activity) {
        createChannel(activity);

        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {

            activity.requestPermissions(
                    new String[]{
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    PERMISSION_REQUEST
            );

            return;
        }

        prepareTestToken(activity);
    }

    private static void prepareTestToken(Activity activity) {
        FirebaseMessaging.getInstance()
                .getToken()
                .addOnCompleteListener(activity, task -> {
                    if (activity.isFinishing()
                            || activity.isDestroyed()) {
                        return;
                    }

                    if (!task.isSuccessful()) {
                        Toast.makeText(
                                activity,
                                "Не удалось настроить push. "
                                        + "Проверьте интернет и сервисы Google Play. "
                                        + "Остальные функции магазина доступны.",
                                Toast.LENGTH_LONG
                        ).show();

                        return;
                    }

                    String token = task.getResult();

                    if (token == null || token.trim().isEmpty()) {
                        return;
                    }

                    SharedPreferences prefs =
                            preferences(activity);

                    prefs.edit()
                            .putString(TOKEN_KEY, token)
                            .apply();

                    String shownToken =
                            prefs.getString(SHOWN_TOKEN_KEY, "");

                    if (token.equals(shownToken)) {
                        return;
                    }

                    showTestDialog(activity, token);
                });
    }

    private static void showTestDialog(
            Activity activity,
            String token
    ) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Проверка push-уведомлений")
                .setMessage(
                        "Приложение получило адрес для уведомлений.\n\n"
                                + "Нажмите «Скопировать» и используйте "
                                + "этот адрес в Firebase при отправке "
                                + "тестового сообщения.\n\n"
                                + "Автоматические уведомления о заказах "
                                + "будут подключены отдельно.\n\n"
                                + "Адрес устройства не нужно присылать в чат."
                )
                .setPositiveButton("Скопировать", (d, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) activity.getSystemService(
                                    Context.CLIPBOARD_SERVICE
                            );

                    if (clipboard == null) {
                        return;
                    }

                    clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                    "Firebase registration token",
                                    token
                            )
                    );

                    preferences(activity)
                            .edit()
                            .putString(SHOWN_TOKEN_KEY, token)
                            .apply();

                    Toast.makeText(
                            activity,
                            "Адрес устройства скопирован",
                            Toast.LENGTH_LONG
                    ).show();
                })
                .setNegativeButton("Позже", null)
                .create();

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(
                        android.R.color.background_dark
                );
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(Color.rgb(212, 175, 55));

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(Color.rgb(212, 175, 55));
        });

        dialog.show();
    }

    /*
     * Firebase вызывает этот сервис при получении сообщения
     * и при смене адреса устройства.
     *
     * Сервис не отправляет данные покупателей на сервер.
     * Привязку к конкретным заказам подключим отдельно.
     */
    public static class MessagingService
            extends FirebaseMessagingService {

        @Override
        public void onNewToken(String token) {
            super.onNewToken(token);

            preferences(this)
                    .edit()
                    .putString(TOKEN_KEY, token)
                    .apply();
        }

        @Override
        public void onMessageReceived(RemoteMessage message) {
            super.onMessageReceived(message);

            String title = "Лавка Вкуса";
            String body = "";

            RemoteMessage.Notification notification =
                    message.getNotification();

            if (notification != null) {
                if (notification.getTitle() != null) {
                    title = notification.getTitle();
                }

                if (notification.getBody() != null) {
                    body = notification.getBody();
                }
            }

            if (body.trim().isEmpty()) {
                String dataBody = message.getData().get("body");

                if (dataBody != null) {
                    body = dataBody;
                }
            }

            if (notification == null) {
                String dataTitle =
                        message.getData().get("title");

                if (dataTitle != null
                        && !dataTitle.trim().isEmpty()) {
                    title = dataTitle;
                }
            }

            if (body.trim().isEmpty()) {
                return;
            }

            showNotification(title, body);
        }

        private void showNotification(
                String title,
                String body
        ) {
            createChannel(this);

            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(
                            Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            NotificationManager manager =
                    (NotificationManager) getSystemService(
                            Context.NOTIFICATION_SERVICE
                    );

            if (manager == null
                    || !manager.areNotificationsEnabled()) {
                return;
            }

            Intent intent = new Intent(
                    this,
                    MainActivity.class
            );

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );

            PendingIntent pendingIntent =
                    PendingIntent.getActivity(
                            this,
                            0,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT
                                    | PendingIntent.FLAG_IMMUTABLE
                    );

            Notification notification =
                    new Notification.Builder(this, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setColor(Color.rgb(212, 175, 55))
                            .setContentTitle(title)
                            .setContentText(body)
                            .setStyle(
                                    new Notification.BigTextStyle()
                                            .bigText(body)
                            )
                            .setContentIntent(pendingIntent)
                            .setAutoCancel(true)
                            .build();

            int notificationId =
                    (int) (System.currentTimeMillis()
                            & 0x7fffffffL);

            manager.notify(
                    notificationId,
                    notification
            );
        }
    }
}
// КОНЕЦ ФАЙЛА
