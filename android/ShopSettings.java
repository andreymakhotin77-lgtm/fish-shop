// LAVKA_POLISHED_SETTINGS_V1
// LAVKA_CLEAN_SETTINGS_TEXT_V1
package ru.makeevka.fishshop;

import android.app.Activity;
import android.app.Dialog;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/*
 * Полноэкранные настройки.
 *
 * Сохраняет прежний интерфейс вызова:
 * show(), dismiss(), protectionEnabled(),
 * lockAfterMillis(), Actions.
 *
 * Проверку владельца выполняет MainActivity.
 * Корзина, история и ключи заказов не изменяются.
 */
public final class ShopSettings {

    // LAVKA_FULLSCREEN_SETTINGS_V1
    private static Dialog activeDialog;

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

    private static final int LINE =
            Color.rgb(53, 53, 48);

    public interface Actions {
        void enableProtection();

        void disableProtection();

        void editCustomerData();
    }

    private ShopSettings() {
    }

    public static void dismiss() {
        Dialog previous = activeDialog;
        activeDialog = null;

        if (previous != null && previous.isShowing()) {
            previous.dismiss();
        }
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
        if (actions == null || !usable(activity)) {
            return;
        }

        dismiss();

        Dialog dialog = new Dialog(
                activity,
                android.R.style.Theme_Material_NoActionBar
        );

        dialog.requestWindowFeature(
                Window.FEATURE_NO_TITLE
        );

        dialog.setCanceledOnTouchOutside(false);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);
        root.setFitsSystemWindows(true);

        dialog.setContentView(
                root,
                new android.view.ViewGroup.LayoutParams(
                        -1,
                        -1
                )
        );

        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) {
                activeDialog = null;
            }
        });

        render(
                activity,
                actions,
                dialog,
                root,
                false
        );

        activeDialog = dialog;

        try {
            dialog.show();

            Window window = dialog.getWindow();

            if (window != null) {
                window.setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(
                                BLACK
                        )
                );

                window.getDecorView().setPadding(
                        0, 0, 0, 0
                );

                window.setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT
                );

                window.addFlags(
                        WindowManager.LayoutParams
                                .FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                );

                window.setStatusBarColor(BLACK);
                window.setNavigationBarColor(BLACK);
                window.getDecorView()
                        .setSystemUiVisibility(0);

                window.setSoftInputMode(
                        WindowManager.LayoutParams
                                .SOFT_INPUT_ADJUST_RESIZE
                );

                boolean secure =
                        (activity.getWindow()
                                .getAttributes().flags &
                                WindowManager.LayoutParams
                                        .FLAG_SECURE) != 0;

                if (secure) {
                    window.addFlags(
                            WindowManager.LayoutParams
                                    .FLAG_SECURE
                    );
                }
            }

        } catch (RuntimeException error) {
            dismiss();

            if (usable(activity)) {
                Toast.makeText(
                        activity,
                        "Не удалось открыть настройки. " +
                                "Попробуйте ещё раз.",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private static void render(
            Activity activity,
            Actions actions,
            Dialog dialog,
            LinearLayout root,
            boolean choosingDelay
    ) {
        if (!usable(activity)) {
            return;
        }

        root.removeAllViews();

        LinearLayout toolbar =
                new LinearLayout(activity);

        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(
                dp(activity, 12),
                dp(activity, 8),
                dp(activity, 16),
                dp(activity, 8)
        );

        Button back = new Button(activity);
        back.setText("‹");
        back.setTextSize(32);
        back.setTextColor(GOLD);
        back.setAllCaps(false);
        back.setMinWidth(0);
        back.setMinimumWidth(0);
        back.setPadding(0, 0, 0, 0);
        back.setBackgroundTintList(null);
        back.setBackground(
                surface(activity, CARD, LINE, 14)
        );
        back.setContentDescription("Назад");

        toolbar.addView(
                back,
                new LinearLayout.LayoutParams(
                        dp(activity, 48),
                        dp(activity, 48)
                )
        );

        LinearLayout titles =
                new LinearLayout(activity);

        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(
                dp(activity, 14), 0, 0, 0
        );

        TextView title = label(
                activity,
                choosingDelay
                        ? "Блокировка"
                        : "Настройки",
                23,
                WHITE
        );

        title.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        titles.addView(title);

        titles.addView(label(
                activity,
                "Лавка Вкуса",
                12,
                MUTED
        ));

        toolbar.addView(
                titles,
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1
                )
        );

        root.addView(
                toolbar,
                new LinearLayout.LayoutParams(-1, -2)
        );

        android.view.View line =
                new android.view.View(activity);

        line.setBackgroundColor(LINE);

        root.addView(
                line,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(activity, 1)
                )
        );

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout body =
                new LinearLayout(activity);

        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(
                dp(activity, 20),
                dp(activity, 8),
                dp(activity, 20),
                dp(activity, 32)
        );

        scroll.addView(
                body,
                new ScrollView.LayoutParams(-1, -2)
        );

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1
                )
        );

        Runnable goBack = () -> {
            if (choosingDelay) {
                render(
                        activity,
                        actions,
                        dialog,
                        root,
                        false
                );
            } else {
                dialog.dismiss();
            }
        };

        back.setOnClickListener(view -> goBack.run());

        dialog.setOnKeyListener(
                (ignored, keyCode, event) -> {
                    if (choosingDelay &&
                            keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.getAction() ==
                                KeyEvent.ACTION_UP) {
                            goBack.run();
                        }
                        return true;
                    }

                    return false;
                }
        );

        if (choosingDelay) {
            delayContent(
                    activity,
                    actions,
                    dialog,
                    root,
                    body
            );
        } else {
            mainContent(
                    activity,
                    actions,
                    dialog,
                    root,
                    body
            );
        }
    }


    private static void mainContent(
            Activity activity,
            Actions actions,
            Dialog dialog,
            LinearLayout root,
            LinearLayout body
    ) {
        boolean enabled = protectionEnabled(activity);

        heading(activity, body, "Безопасность");

        settingsRow(
                activity, body,
                "Защита входа",
                enabled ? "Включена" : "Отключена",
                () -> {
                    dialog.dismiss();
                    if (enabled) {
                        actions.disableProtection();
                    } else {
                        actions.enableProtection();
                    }
                }
        );

        settingsRow(
                activity, body,
                "Повторная блокировка",
                delayLabel(lockAfterMillis(activity)),
                () -> render(
                        activity, actions, dialog, root, true
                )
        );

        description(
                activity, body,
                "Биометрия и код телефона не передаются магазину. " +
                "При новом запуске включённая защита проверяется снова."
        );

        heading(activity, body, "Уведомления");

        settingsRow(
                activity, body,
                "Уведомления о заказах",
                notificationsEnabled(activity)
                        ? "Разрешены" : "Отключены",
                () -> {
                    dialog.dismiss();
                    openNotifications(activity);
                }
        );

        heading(activity, body, "Данные покупателя");

        settingsRow(
                activity, body,
                "Изменить мои данные",
                "",
                () -> {
                    dialog.dismiss();
                    actions.editCustomerData();
                }
        );

        heading(activity, body, "О приложении");

        description(
                activity, body,
                "Лавка Вкуса\nВерсия: " + version(activity)
        );

        TextView dataNotice = label(
                activity,
                "Имя, телефон и адрес для автозаполнения, " +
                "корзина, избранное и история хранятся " +
                "на этом телефоне.\n\n" +
                "При оформлении заказа его данные передаются " +
                "магазину и сохраняются на сервере. " +
                "Изменение профиля не меняет уже оформленные заказы.\n\n" +
                "При удалении приложения или очистке его данных " +
                "локальная история и ключи просмотра будут потеряны. " +
                "Автоматического восстановления истории " +
                "на другом телефоне сейчас нет.\n\n" +
                "Для обновления устанавливайте новый APK поверх " +
                "приложения, без удаления.",
                14, MUTED
        );

        dataNotice.setPadding(
                dp(activity, 12), dp(activity, 10),
                dp(activity, 12), dp(activity, 16)
        );
        dataNotice.setVisibility(android.view.View.GONE);

        LinearLayout dataLink = settingsRow(
                activity, body,
                "О данных",
                "Хранение и история заказов",
                () -> {
                    boolean opening = dataNotice.getVisibility()
                            != android.view.View.VISIBLE;
                    dataNotice.setVisibility(
                            opening
                                    ? android.view.View.VISIBLE
                                    : android.view.View.GONE
                    );
                }
        );
        dataLink.setContentDescription(
                "О данных. Показать или скрыть сведения " +
                "о хранении данных и истории заказов."
        );

        body.addView(
                dataNotice,
                new LinearLayout.LayoutParams(-1, -2)
        );
    }

    private static LinearLayout settingsRow(
            Activity activity,
            LinearLayout parent,
            String title,
            String value,
            Runnable action
    ) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(activity, 64));
        row.setPadding(
                dp(activity, 14), dp(activity, 12),
                dp(activity, 14), dp(activity, 12)
        );

        GradientDrawable normal =
                surface(activity, CARD, 0, 14);

        row.setBackground(
                new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(
                                Color.argb(45, 212, 175, 55)
                        ),
                        normal,
                        surface(activity, Color.WHITE, 0, 14)
                )
        );

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView name = label(activity, title, 16, WHITE);
        labels.addView(name);

        if (value != null && !value.isEmpty()) {
            TextView detail = label(activity, value, 13, MUTED);
            detail.setPadding(0, dp(activity, 3), 0, 0);
            labels.addView(detail);
        }

        labels.setImportantForAccessibility(
                android.view.View
                        .IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        );

        row.addView(
                labels,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        TextView arrow = label(activity, "›", 27, GOLD);
        arrow.setPadding(dp(activity, 12), 0, 0, 0);
        arrow.setImportantForAccessibility(
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        );
        row.addView(arrow);

        row.setFocusable(true);
        row.setContentDescription(
                title +
                (value == null || value.isEmpty()
                        ? "" : ". " + value)
        );
        row.setOnClickListener(view -> action.run());

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(
                0, dp(activity, 4), 0, dp(activity, 4)
        );
        parent.addView(row, params);

        return row;
    }

    private static void delayContent(
            Activity activity,
            Actions actions,
            Dialog dialog,
            LinearLayout root,
            LinearLayout body
    ) {
        heading(
                activity,
                body,
                "Когда блокировать приложение?"
        );

        description(
                activity,
                body,
                "Выберите время повторной проверки " +
                        "после ухода приложения в фон."
        );

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

        for (int i = 0; i < values.length; i++) {
            final long selectedValue = values[i];

            String title =
                    (current == selectedValue ? "✓  " : "") +
                            labels[i];

            addButton(
                    activity,
                    body,
                    title,
                    () -> {
                        boolean saved =
                                preferences(activity)
                                        .edit()
                                        .putLong(
                                                DELAY,
                                                selectedValue
                                        )
                                        .commit();

                        Toast.makeText(
                                activity,
                                saved
                                        ? "Настройка сохранена"
                                        : "Не удалось сохранить настройку",
                                Toast.LENGTH_SHORT
                        ).show();

                        if (saved) {
                            render(
                                    activity,
                                    actions,
                                    dialog,
                                    root,
                                    false
                            );
                        }
                    }
            );
        }

        description(
                activity,
                body,
                "Эта настройка не включает и не отключает " +
                        "защиту входа. При новом запуске " +
                        "включённая защита проверяется снова."
        );
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

    private static boolean usable(Activity activity) {
        return activity != null &&
                !activity.isFinishing() &&
                !activity.isDestroyed();
    }

    private static TextView label(
            Activity activity,
            String value,
            int size,
            int color
    ) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        text.setLineSpacing(dp(activity, 3), 1f);

        return text;
    }

    private static void heading(
            Activity activity,
            LinearLayout parent,
            String value
    ) {
        TextView text = label(
                activity,
                value,
                19,
                GOLD
        );

        text.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        text.setPadding(
                0,
                dp(activity, 20),
                0,
                dp(activity, 8)
        );

        parent.addView(
                text,
                new LinearLayout.LayoutParams(-1, -2)
        );
    }

    private static void description(
            Activity activity,
            LinearLayout parent,
            String value
    ) {
        TextView text = label(
                activity,
                value,
                14,
                MUTED
        );

        text.setPadding(
                0,
                dp(activity, 4),
                0,
                dp(activity, 10)
        );

        parent.addView(
                text,
                new LinearLayout.LayoutParams(-1, -2)
        );
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
        button.setTextSize(15);
        button.setMinHeight(dp(activity, 52));
        button.setMinimumHeight(dp(activity, 52));
        button.setBackgroundTintList(null);

        button.setBackground(
                surface(activity, CARD, LINE, 14)
        );

        button.setPadding(
                dp(activity, 14),
                dp(activity, 12),
                dp(activity, 14),
                dp(activity, 12)
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

    private static GradientDrawable surface(
            Context context,
            int color,
            int stroke,
            int radius
    ) {
        GradientDrawable drawable =
                new GradientDrawable();

        drawable.setColor(color);
        drawable.setCornerRadius(
                dp(context, radius)
        );

        drawable.setStroke(
                dp(context, 1),
                stroke
        );

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
