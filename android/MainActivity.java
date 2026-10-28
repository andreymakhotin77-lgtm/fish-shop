package ru.makeevka.fishshop;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends StoreActivity {

    private static final String LOCK_PREFS =
            "lavka_device_lock_v1";

    private static final String ENABLED_KEY =
            "enabled";

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

    private SharedPreferences lockPrefs;
    private DeviceLogin deviceLogin;
    private OrderPushSubscriptions orderPush;

    // LAVKA_CAT_WELCOME_V1
    private CatWelcome catWelcome;

    private FrameLayout gate;
    private TextView gateTitle;
    private TextView gateHint;
    private TextView gateMessage;

    private Button enterButton;
    private Button codeButton;
    private Button settingsButton;
    private Button skipButton;
    private Button disableButton;

    private AlertDialog disableDialog;

    private boolean protectionEnabled;
    private boolean unlocked;
    private boolean resumed;
    private boolean closing;
    private boolean autoPrompt;
    private boolean pushNeedsInitialization = true;

    private long backgroundAt = -1L;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SECURE
        );

        super.onCreate(savedInstanceState);

        lockPrefs = getSharedPreferences(
                LOCK_PREFS,
                MODE_PRIVATE
        );

        protectionEnabled = lockPrefs.getBoolean(
                ENABLED_KEY,
                false
        );

        deviceLogin = new DeviceLogin(this);

        unlocked = false;
        autoPrompt = protectionEnabled;

        PushNotifications.createChannel(this);

        createGate();
        showGate();
    }

    // ==================== НАСТРОЙКИ ====================

    public void openShopSettings() {
        if (!unlocked || !resumed || closing) {
            return;
        }

        ShopSettings.show(
                this,
                new ShopSettings.Actions() {

                    @Override
                    public void enableProtection() {
                        if (!unlocked || closing) {
                            return;
                        }

                        protectionEnabled =
                                lockPrefs.getBoolean(
                                        ENABLED_KEY,
                                        false
                                );

                        if (protectionEnabled) {
                            return;
                        }

                        unlocked = false;
                        showGate();
                        authenticate(false, false);
                    }

                    @Override
                    public void disableProtection() {
                        if (!unlocked || closing) {
                            return;
                        }

                        confirmDisable();
                    }

                    @Override
                    public void editCustomerData() {
                        if (!unlocked || closing) {
                            return;
                        }

                        /*
                         * Настройки открываются из профиля.
                         * Существующая форма данных остаётся
                         * на том же экране под кнопкой настроек.
                         */
                        Toast.makeText(
                                MainActivity.this,
                                "Измените данные в разделе " +
                                "«Данные покупателя» ниже " +
                                "и нажмите «Сохранить данные».",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    // ==================== ЭКРАН ВХОДА ====================

    private void createGate() {
        FrameLayout host = findViewById(
                android.R.id.content
        );

        gate = new FrameLayout(this);
        gate.setBackgroundColor(BLACK);
        gate.setClickable(true);
        gate.setFocusable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding(
                dp(28), dp(32),
                dp(28), dp(32)
        );

        TextView logo = label("ЛВ", 46, GOLD);
        logo.setTypeface(
                Typeface.create("serif", Typeface.BOLD)
        );

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(CARD);
        circle.setStroke(dp(1), GOLD);
        logo.setBackground(circle);

        LinearLayout.LayoutParams logoParams =
                new LinearLayout.LayoutParams(
                        dp(120), dp(120)
                );

        logoParams.setMargins(0, 0, 0, dp(22));
        body.addView(logo, logoParams);

        gateTitle = label("Лавка Вкуса", 25, WHITE);
        body.addView(gateTitle);

        gateHint = label("", 14, MUTED);
        body.addView(gateHint);

        gateMessage = label("", 13, GOLD);
        gateMessage.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_POLITE
        );
        body.addView(gateMessage);

        enterButton = actionButton(
                "",
                () -> authenticate(false, false),
                false
        );
        body.addView(enterButton);

        codeButton = actionButton(
                "Войти по коду телефона",
                () -> authenticate(true, false),
                true
        );
        body.addView(codeButton);

        settingsButton = actionButton(
                "Настройки блокировки телефона",
                this::openSecuritySettings,
                true
        );
        body.addView(settingsButton);

        skipButton = actionButton(
                "Продолжить без защиты",
                this::continueWithoutProtection,
                true
        );
        body.addView(skipButton);

        disableButton = actionButton(
                "Отключить защиту входа",
                this::confirmDisable,
                true
        );
        body.addView(disableButton);

        body.addView(label(
                "Отпечатки, изображение лица и код " +
                "телефона не передаются магазину.",
                12,
                MUTED
        ));

        scroll.addView(
                body,
                new ScrollView.LayoutParams(-1, -1)
        );

        gate.addView(
                scroll,
                new FrameLayout.LayoutParams(-1, -1)
        );

        host.addView(
                gate,
                new FrameLayout.LayoutParams(-1, -1)
        );
    }

    private void showGate() {
        if (gate == null || closing) {
            return;
        }

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SECURE
        );

        gate.setVisibility(View.VISIBLE);
        gate.bringToFront();

        gateTitle.setText(
                protectionEnabled
                        ? "Вход в Лавку Вкуса"
                        : "Защитить вход?"
        );

        gateHint.setText(
                protectionEnabled
                        ? "Подтвердите вход отпечатком, " +
                          "поддерживаемой биометрией " +
                          "или кодом телефона."
                        : "Можно защитить приложение " +
                          "биометрией или кодом блокировки " +
                          "вашего телефона."
        );

        enterButton.setText(
                protectionEnabled
                        ? "Разблокировать"
                        : "Включить защиту"
        );

        codeButton.setVisibility(
                protectionEnabled ? View.VISIBLE : View.GONE
        );

        skipButton.setVisibility(
                protectionEnabled ? View.GONE : View.VISIBLE
        );

        disableButton.setVisibility(
                protectionEnabled ? View.VISIBLE : View.GONE
        );

        syncButtons();
    }

    private void syncButtons() {
        if (deviceLogin == null) {
            return;
        }

        boolean available = !deviceLogin.isRunning();

        enterButton.setEnabled(available);
        codeButton.setEnabled(available);
        settingsButton.setEnabled(available);
        skipButton.setEnabled(available);
        disableButton.setEnabled(available);
    }

    // ==================== ПРОВЕРКА ВХОДА ====================

    private void authenticate(
            boolean phoneCodeOnly,
            boolean disableAfterSuccess
    ) {
        if (closing ||
                !resumed ||
                deviceLogin == null ||
                deviceLogin.isRunning()) {
            return;
        }

        autoPrompt = false;
        gateMessage.setText("");

        if (!deviceLogin.isDeviceSecure()) {
            gateMessage.setText(
                    "Сначала настройте PIN-код, пароль " +
                    "или графический ключ телефона. " +
                    "Затем можно включить защиту приложения."
            );
            return;
        }

        DeviceLogin.Callback result =
                new DeviceLogin.Callback() {

                    @Override
                    public void onAuthenticated() {
                        if (closing) {
                            return;
                        }

                        if (disableAfterSuccess) {
                            if (!saveProtection(false)) {
                                showStorageError();
                                return;
                            }

                            protectionEnabled = false;

                        } else if (!protectionEnabled) {
                            if (!saveProtection(true)) {
                                showStorageError();
                                return;
                            }

                            protectionEnabled = true;
                        }

                        unlockStore();
                    }

                    @Override
                    public void onFailed(String message) {
                        if (closing) {
                            return;
                        }

                        gateMessage.setText(message);
                        syncButtons();
                    }
                };

        if (phoneCodeOnly) {
            deviceLogin.authenticateWithPhoneCode(result);
        } else {
            deviceLogin.authenticate(result);
        }

        syncButtons();
    }

    private boolean saveProtection(boolean enabled) {
        return lockPrefs.edit()
                .putBoolean(ENABLED_KEY, enabled)
                .commit();
    }

    private void showStorageError() {
        gateMessage.setText(
                "Не удалось сохранить настройку защиты. " +
                "Освободите место на телефоне " +
                "и повторите проверку."
        );

        syncButtons();
    }

    private void unlockStore() {
        unlocked = true;
        backgroundAt = -1L;
        autoPrompt = false;

        gateMessage.setText("");
        gate.setVisibility(View.GONE);

        if (!protectionEnabled) {
            getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_SECURE
            );
        }

        if (CatWelcome.shouldShow(this)) {
            catWelcome = new CatWelcome(
                    this,
                    () -> {
                        catWelcome = null;
                        startBuyerServices();
                    }
            );
            catWelcome.start();
            return;
        }

        startBuyerServices();
    }

    private void continueWithoutProtection() {
        if (protectionEnabled ||
                deviceLogin == null ||
                deviceLogin.isRunning()) {
            return;
        }

        unlockStore();
    }

    private void confirmDisable() {
        if (!protectionEnabled ||
                deviceLogin == null ||
                deviceLogin.isRunning() ||
                closing ||
                !resumed) {
            return;
        }

        if (disableDialog != null &&
                disableDialog.isShowing()) {
            return;
        }

        disableDialog = new AlertDialog.Builder(this)
                .setTitle("Отключить защиту?")
                .setMessage(
                        "Сначала подтвердите, что вы " +
                        "владелец телефона. После отключения " +
                        "вход в магазин не будет защищён."
                )
                .setNegativeButton("Оставить", null)
                .setPositiveButton(
                        "Подтвердить",
                        (dialog, which) -> {
                            if (!resumed || closing) {
                                return;
                            }

                            unlocked = false;
                            showGate();
                            authenticate(false, true);
                        }
                )
                .create();

        disableDialog.show();
    }

    private void openSecuritySettings() {
        if (deviceLogin == null ||
                deviceLogin.isRunning()) {
            return;
        }

        try {
            startActivity(
                    new Intent(
                            Settings.ACTION_SECURITY_SETTINGS
                    )
            );
        } catch (RuntimeException error) {
            gateMessage.setText(
                    "Откройте настройки телефона " +
                    "и настройте блокировку экрана."
            );
        }
    }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;

        if (deviceLogin == null || closing) {
            return;
        }

        protectionEnabled = lockPrefs.getBoolean(
                ENABLED_KEY,
                false
        );

        if (protectionEnabled &&
                unlocked &&
                backgroundAt >= 0L) {

            long elapsed =
                    SystemClock.elapsedRealtime() -
                    backgroundAt;

            long delay =
                    ShopSettings.lockAfterMillis(this);

            if (elapsed >= delay || elapsed < 0L) {
                unlocked = false;
                autoPrompt = true;
            }
        }

        backgroundAt = -1L;

        if (unlocked) {
            gate.setVisibility(View.GONE);
            startBuyerServices();
            return;
        }

        showGate();

        if (autoPrompt && !deviceLogin.isRunning()) {
            autoPrompt = false;

            gate.post(() -> {
                if (resumed &&
                        !closing &&
                        !unlocked &&
                        protectionEnabled) {
                    authenticate(false, false);
                }
            });
        }
    }

    @Override
    protected void onPause() {
        resumed = false;

        if (catWelcome != null) {
            catWelcome.finish();
            catWelcome = null;
        }

        super.onPause();
    }

    @Override
    protected void onStop() {
        /*
         * Закрываем окна настроек при уходе в фон,
         * чтобы они не оставались поверх экрана входа.
         */
        ShopSettings.dismiss();

        if (disableDialog != null) {
            disableDialog.dismiss();
            disableDialog = null;
        }

        if (deviceLogin != null &&
                protectionEnabled &&
                unlocked) {

            if (backgroundAt < 0L) {
                backgroundAt = SystemClock.elapsedRealtime();
            }

            showGate();
        }

        super.onStop();
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(
                requestCode, resultCode, data
        );

        if (deviceLogin != null) {
            deviceLogin.onActivityResult(
                    requestCode,
                    resultCode
            );
        }
    }

    @Override
    public void onBackPressed() {
        if (!unlocked) {
            moveTaskToBack(true);
            return;
        }

        super.onBackPressed();
    }

    // ==================== УВЕДОМЛЕНИЯ ====================

    private void startBuyerServices() {
        if (catWelcome != null &&
                catWelcome.isBlocking()) {
            return;
        }

        if (!resumed || !unlocked || closing) {
            return;
        }

        if (orderPush == null) {
            orderPush = new OrderPushSubscriptions(this);
            orderPush.start();
        }

        if (pushNeedsInitialization) {
            pushNeedsInitialization = false;
            PushNotifications.initialize(this);
        }

        orderPush.refresh();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode !=
                PushNotifications.PERMISSION_REQUEST) {
            return;
        }

        if (grantResults.length > 0 &&
                grantResults[0] ==
                        PackageManager.PERMISSION_GRANTED) {

            pushNeedsInitialization = true;
            startBuyerServices();

        } else {
            Toast.makeText(
                    this,
                    "Уведомления отключены. " +
                    "Их можно разрешить в настройках Android. " +
                    "Магазин продолжит работать.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onDestroy() {
        closing = true;
        resumed = false;

        if (catWelcome != null) {
            catWelcome.dispose();
            catWelcome = null;
        }

        ShopSettings.dismiss();

        if (disableDialog != null) {
            disableDialog.dismiss();
            disableDialog = null;
        }

        if (deviceLogin != null) {
            deviceLogin.close();
            deviceLogin = null;
        }

        if (orderPush != null) {
            orderPush.close();
            orderPush = null;
        }

        super.onDestroy();
    }

    // ==================== ОФОРМЛЕНИЕ ====================

    private int dp(int value) {
        return Math.round(
                value *
                getResources().getDisplayMetrics().density
        );
    }

    private TextView label(
            String text,
            int size,
            int color
    ) {
        TextView result = new TextView(this);
        result.setText(text);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setGravity(Gravity.CENTER);
        result.setPadding(0, dp(8), 0, dp(8));
        return result;
    }

    private Button actionButton(
            String text,
            Runnable action,
            boolean secondary
    ) {
        Button result = new Button(this);
        result.setText(text);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setTextColor(secondary ? GOLD : BLACK);
        result.setMinHeight(dp(48));
        result.setMinimumHeight(dp(48));

        result.setPadding(
                dp(12), dp(10),
                dp(12), dp(10)
        );

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(secondary ? CARD : GOLD);
        background.setCornerRadius(dp(14));

        if (secondary) {
            background.setStroke(dp(1), GOLD);
        }

        result.setBackgroundTintList(null);
        result.setBackground(background);
        result.setStateListAnimator(null);
        result.setOnClickListener(view -> action.run());

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);

        params.setMargins(0, dp(8), 0, 0);
        result.setLayoutParams(params);

        return result;
    }
}

// КОНЕЦ ФАЙЛА
