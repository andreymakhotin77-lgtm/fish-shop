package ru.makeevka.fishshop;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/*
 * Экран настроек магазина.
 *
 * Самостоятельно не отключает защиту:
 * проверку владельца выполняет MainActivity.
 *
 * История заказов, корзина и ключи просмотра
 * этим классом не изменяются.
 */
public final class ShopSettings {

    // LAVKA_SETTINGS_DIALOG_LIFECYCLE
    private static AlertDialog activeDialog;

    public static void dismiss() {
        AlertDialog previous = activeDialog;
        activeDialog = null;

        if (previous != null && previous.isShowing()) {
            previous.dismiss();
        }
    }

    private static final String PREFS =
            "lavka_device_lock_v1";

    private static final String ENABLED =
            "enabled";

    private static final String DELAY =
            "lock_after_ms";

    private static final int BLACK =
            Color.rgb(14, 15, 17);

    private static final int CARD =
            Color.rgb(25, 27, 30);

    private static final int GOLD =
            Color.rgb(212, 175, 55);

    private static final int WHITE =
            Color.rgb(247, 244, 236);

    private static final int MUTED =
            Color.rgb(166, 167, 163);

    /*
     * Действия выполняет Activity:
     * включение/отключение — с проверкой владельца,
     * редактирование — в существующем профиле.
     */
    public interface Actions {
        void enableProtection();

        void disableProtection();

        void editCustomerData();
    }

    private ShopSettings() {
    }

    private static SharedPreferences preferences(
            Context context
    ) {
        return context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }

    public static boolean protectionEnabled(
            Context context
    ) {
        return preferences(context).getBoolean(
                ENABLED,
                false
        );
    }

    public static long lockAfterMillis(
            Context context
    ) {
        long value = preferences(context).getLong(
                DELAY,
                30000L
        );

        if (value == 0L ||
                value == 30000L ||
                value == 60000L) {
            return value;
        }

        return 30000L;
    }

    public static void show(
            Activity activity,
            Actions actions
    ) {
        if (actions == null ||
                activity.isFinishing() ||
                activity.isDestroyed()) {
            return;
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BLACK);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(
                dp(activity, 20),
                dp(activity, 12),
                dp(activity, 20),
                dp(activity, 20)
        );

        scroll.addView(body);

        boolean enabled =
                protectionEnabled(activity);

        heading(activity, body, "Безопасность");

        description(
                activity,
                body,
                enabled
                        ? "Защита входа включена."
                        : "Защита входа отключена."
        );

        description(
                activity,
                body,
                "Отпечаток, изображение лица и код " +
                "блокировки телефона не передаются магазину."
        );

        AlertDialog dialog =
                new AlertDialog.Builder(
                        activity,
                        android.R.style.Theme_Material_Dialog_Alert
                )
                        .setTitle("Настройки")
                        .setView(scroll)
                        .setPositiveButton("Закрыть", null)
                        .create();

        addButton(
                activity,
                body,
                enabled
                        ? "Отключить защиту входа"
                        : "Включить защиту входа",
                () -> {
                    dialog.dismiss();

                    if (enabled) {
                        actions.disableProtection();
                    } else {
                        actions.enableProtection();
                    }
                }
        );

        addButton(
                activity,
                body,
                "Повторная блокировка: " +
                        delayLabel(lockAfterMillis(activity)),
                () -> {
                    dialog.dismiss();
                    chooseDelay(activity, actions);
                }
        );

        description(
                activity,
                body,
                "При новом запуске защищённого приложения " +
                "проверка выполняется независимо от " +
                "выбранного времени."
        );

        heading(activity, body, "Уведомления");

        description(
                activity,
                body,
                notificationsEnabled(activity)
                        ? "Уведомления приложения разрешены. " +
                          "Отдельные категории можно проверить " +
                          "в настройках Android."
                        : "Уведомления приложения отключены " +
                          "в настройках Android."
        );

        addButton(
                activity,
                body,
                "Настроить уведомления",
                () -> {
                    dialog.dismiss();
                    openNotifications(activity);
                }
        );

        heading(activity, body, "Данные покупателя");

        description(
                activity,
                body,
                "Имя, телефон, город и адрес сохраняются " +
                "на этом телефоне. Изменение данных " +
                "не меняет уже оформленные заказы."
        );

        addButton(
                activity,
                body,
                "Изменить мои данные",
                () -> {
                    dialog.dismiss();
                    actions.editCustomerData();
                }
        );

        heading(activity, body, "О приложении");

        description(
                activity,
                body,
                "Лавка Вкуса\nВерсия: " +
                        version(activity) +
                        "\n\nОплата при получении."
        );

        description(
                activity,
                body,
                "Не удаляйте приложение для обновления: " +
                "при удалении можно потерять локальную " +
                "историю и ключи просмотра заказов."
        );

        activeDialog = dialog;
        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    background(activity)
            );
        }

        Button close = dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
        );

        if (close != null) {
            close.setTextColor(GOLD);
        }
    }

    private static void chooseDelay(
            Activity activity,
            Actions actions
    ) {
        if (activity.isFinishing() ||
                activity.isDestroyed()) {
            return;
        }

        String[] labels = {
                "Сразу после ухода в фон",
                "Через 30 секунд",
                "Через 1 минуту"
        };

        long[] values = {
                0L,
                30000L,
                60000L
        };

        long current = lockAfterMillis(activity);

        int selected =
                current == 0L ? 0 :
                current == 60000L ? 2 : 1;

        AlertDialog choice = new AlertDialog.Builder(
                activity,
                android.R.style.Theme_Material_Dialog_Alert
        )
                .setTitle("Когда блокировать приложение?")
                .setSingleChoiceItems(
                        labels,
                        selected,
                        (dialog, which) -> {
                            boolean saved =
                                    preferences(activity)
                                            .edit()
                                            .putLong(
                                                    DELAY,
                                                    values[which]
                                            )
                                            .commit();

                            dialog.dismiss();

                            Toast.makeText(
                                    activity,
                                    saved
                                            ? "Настройка сохранена"
                                            : "Не удалось сохранить настройку",
                                    Toast.LENGTH_SHORT
                            ).show();

                            show(activity, actions);
                        }
                )
                .setNegativeButton(
                        "Назад",
                        (dialog, which) ->
                                show(activity, actions)
                )
                .create();

        activeDialog = choice;
        choice.show();
    }

    private static String delayLabel(long value) {
        if (value == 0L) {
            return "сразу";
        }

        if (value == 60000L) {
            return "через 1 минуту";
        }

        return "через 30 секунд";
    }

    private static boolean notificationsEnabled(
            Activity activity
    ) {
        NotificationManager manager =
                (NotificationManager)
                        activity.getSystemService(
                                Context.NOTIFICATION_SERVICE
                        );

        return manager != null &&
                manager.areNotificationsEnabled();
    }

    private static void openNotifications(
            Activity activity
    ) {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
            );

            intent.putExtra(
                    Settings.EXTRA_APP_PACKAGE,
                    activity.getPackageName()
            );

            activity.startActivity(intent);

        } catch (RuntimeException error) {
            Toast.makeText(
                    activity,
                    "Откройте настройки Android → " +
                    "Приложения → Лавка Вкуса → Уведомления.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private static String version(Activity activity) {
        try {
            PackageInfo info =
                    activity.getPackageManager()
                            .getPackageInfo(
                                    activity.getPackageName(),
                                    0
                            );

            long code = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode()
                    : info.versionCode;

            String name = info.versionName == null
                    ? "не указана"
                    : info.versionName;

            return name + " (" + code + ")";

        } catch (Exception error) {
            return "не удалось определить";
        }
    }

    private static void heading(
            Activity activity,
            LinearLayout parent,
            String value
    ) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(GOLD);
        text.setTextSize(19);
        text.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        text.setPadding(
                0,
                dp(activity, 18),
                0,
                dp(activity, 8)
        );

        parent.addView(text);
    }

    private static void description(
            Activity activity,
            LinearLayout parent,
            String value
    ) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(MUTED);
        text.setTextSize(14);
        text.setLineSpacing(dp(activity, 3), 1f);

        text.setPadding(
                0,
                dp(activity, 4),
                0,
                dp(activity, 8)
        );

        parent.addView(text);
    }

    private static void addButton(
            Activity activity,
            LinearLayout parent,
            String title,
            Runnable action
    ) {
        Button button = new Button(activity);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(WHITE);
        button.setTextSize(14);
        button.setMinHeight(dp(activity, 48));
        button.setMinimumHeight(dp(activity, 48));
        button.setBackgroundTintList(null);
        button.setBackground(background(activity));

        button.setPadding(
                dp(activity, 12),
                dp(activity, 10),
                dp(activity, 12),
                dp(activity, 10)
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);

        params.setMargins(
                0,
                dp(activity, 5),
                0,
                dp(activity, 5)
        );

        parent.addView(button, params);

        button.setOnClickListener(
                view -> action.run()
        );
    }

    private static GradientDrawable background(
            Context context
    ) {
        GradientDrawable drawable =
                new GradientDrawable();

        drawable.setColor(CARD);
        drawable.setCornerRadius(dp(context, 16));
        drawable.setStroke(dp(context, 1), GOLD);

        return drawable;
    }

    private static int dp(
            Context context,
            int value
    ) {
        return Math.round(
                value *
                context.getResources()
                        .getDisplayMetrics().density
        );
    }
}

// КОНЕЦ ФАЙЛА
