package ru.makeevka.fishshop;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

/*
 * Системная проверка владельца телефона.
 *
 * Android 9+:
 * биометрия, доступная приложениям, либо код телефона.
 *
 * Android 8:
 * PIN, пароль или графический ключ телефона.
 *
 * Класс не получает отпечатки, изображение лица
 * или сам PIN-код.
 */
public final class DeviceLogin {

    public static final int REQUEST_DEVICE_LOGIN = 8614;

    public interface Callback {
        void onAuthenticated();

        void onFailed(String message);
    }

    private final Activity activity;

    private Callback callback;
    private CancellationSignal cancellation;

    private boolean running;
    private boolean waitingForCredential;
    private boolean closed;

    private int generation;

    public DeviceLogin(Activity activity) {
        this.activity = activity;
    }

    public boolean isDeviceSecure() {
        KeyguardManager manager =
                (KeyguardManager) activity.getSystemService(
                        Context.KEYGUARD_SERVICE
                );

        return manager != null &&
                manager.isDeviceSecure();
    }

    public boolean isRunning() {
        return running;
    }

    public void authenticate(Callback result) {
        if (!begin(result)) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 28) {
            showBiometricPrompt(generation);
        } else {
            openCredentialScreen(generation);
        }
    }

    public void authenticateWithPhoneCode(
            Callback result
    ) {
        if (!begin(result)) {
            return;
        }

        openCredentialScreen(generation);
    }

    private boolean begin(Callback result) {
        if (result == null) {
            throw new IllegalArgumentException(
                    "Не задан обработчик проверки входа."
            );
        }

        if (closed ||
                activity.isFinishing() ||
                activity.isDestroyed()) {
            result.onFailed(
                    "Экран закрыт. Откройте приложение заново."
            );
            return false;
        }

        if (running) {
            return false;
        }

        if (!isDeviceSecure()) {
            result.onFailed(
                    "Сначала включите блокировку экрана " +
                    "в настройках телефона: PIN-код, пароль " +
                    "или графический ключ. Затем можно " +
                    "добавить отпечаток или распознавание лица."
            );
            return false;
        }

        callback = result;
        running = true;
        waitingForCredential = false;
        generation++;

        return true;
    }

    /*
     * Вызывается только на Android 9+.
     */
    @android.annotation.TargetApi(28)
    private void showBiometricPrompt(
            final int attempt
    ) {
        try {
            BiometricPrompt.Builder builder =
                    new BiometricPrompt.Builder(activity)
                            .setTitle("Вход в Лавку Вкуса")
                            .setSubtitle(
                                    "Подтвердите, что это вы"
                            );

            if (Build.VERSION.SDK_INT >= 30) {
                configureModernPrompt(builder);

            } else if (Build.VERSION.SDK_INT >= 29) {
                configureAndroid10Prompt(builder);

            } else {
                /*
                 * На Android 9 запасной вход открывается
                 * отдельной кнопкой системного окна.
                 */
                builder.setNegativeButton(
                        "Код телефона",
                        activity.getMainExecutor(),
                        (dialog, which) -> {
                            if (!current(attempt)) {
                                return;
                            }

                            openCredentialScreen(attempt);
                        }
                );
            }

            cancellation = new CancellationSignal();

            BiometricPrompt prompt = builder.build();

            prompt.authenticate(
                    cancellation,
                    activity.getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {

                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result
                        ) {
                            if (!current(attempt)) {
                                return;
                            }

                            succeed(attempt);
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            /*
                             * Отпечаток или лицо не распознаны.
                             * Системное окно остаётся открытым
                             * для следующей попытки.
                             */
                        }

                        @Override
                        public void onAuthenticationError(
                                int errorCode,
                                CharSequence errorString
                        ) {
                            if (!current(attempt) ||
                                    waitingForCredential) {
                                return;
                            }

                            /*
                             * На Android 9 нажатие отрицательной
                             * кнопки обрабатывается её слушателем.
                             */
                            if (Build.VERSION.SDK_INT == 28 &&
                                    errorCode == 13) {
                                return;
                            }

                            /*
                             * Нет сенсора, биометрия не настроена
                             * либо временно заблокирована:
                             * предлагаем системный код телефона.
                             */
                            if (shouldUsePhoneCode(errorCode)) {
                                openCredentialScreen(attempt);
                                return;
                            }

                            String message;

                            if (errorCode == 5 ||
                                    errorCode == 10 ||
                                    errorCode == 13) {
                                message =
                                        "Вход отменён. " +
                                        "Для открытия магазина " +
                                        "повторите проверку.";
                            } else {
                                message =
                                        errorString == null ||
                                        errorString.length() == 0
                                                ? "Не удалось выполнить проверку. " +
                                                  "Попробуйте код телефона."
                                                : errorString.toString();
                            }

                            fail(attempt, message);
                        }
                    }
            );

        } catch (RuntimeException error) {
            if (current(attempt)) {
                openCredentialScreen(attempt);
            }
        }
    }

    @android.annotation.TargetApi(30)
    private void configureModernPrompt(
            BiometricPrompt.Builder builder
    ) {
        builder.setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager
                        .Authenticators.BIOMETRIC_STRONG
                        |
                android.hardware.biometrics.BiometricManager
                        .Authenticators.DEVICE_CREDENTIAL
        );
    }

    @SuppressWarnings("deprecation")
    @android.annotation.TargetApi(29)
    private void configureAndroid10Prompt(
            BiometricPrompt.Builder builder
    ) {
        builder.setDeviceCredentialAllowed(true);
    }

    private boolean shouldUsePhoneCode(
            int errorCode
    ) {
        return errorCode == 1 ||
                errorCode == 7 ||
                errorCode == 9 ||
                errorCode == 11 ||
                errorCode == 12 ||
                errorCode == 14;
    }

    @SuppressWarnings("deprecation")
    private void openCredentialScreen(
            int attempt
    ) {
        if (!current(attempt) ||
                waitingForCredential) {
            return;
        }

        KeyguardManager manager =
                (KeyguardManager) activity.getSystemService(
                        Context.KEYGUARD_SERVICE
                );

        if (manager == null ||
                !manager.isDeviceSecure()) {
            fail(
                    attempt,
                    "На телефоне не настроена " +
                    "защищённая блокировка экрана."
            );
            return;
        }

        Intent intent =
                manager.createConfirmDeviceCredentialIntent(
                        "Вход в Лавку Вкуса",
                        "Введите PIN-код, пароль " +
                        "или графический ключ телефона"
                );

        if (intent == null) {
            fail(
                    attempt,
                    "Не удалось открыть проверку " +
                    "кода телефона."
            );
            return;
        }

        waitingForCredential = true;

        /*
         * Отменяем старое биометрическое окно,
         * если переходим к отдельной проверке кода.
         */
        if (cancellation != null) {
            cancellation.cancel();
            cancellation = null;
        }

        try {
            activity.startActivityForResult(
                    intent,
                    REQUEST_DEVICE_LOGIN
            );
        } catch (RuntimeException error) {
            waitingForCredential = false;

            fail(
                    attempt,
                    "Не удалось открыть системную проверку. " +
                    "Попробуйте ещё раз."
            );
        }
    }

    public boolean onActivityResult(
            int requestCode,
            int resultCode
    ) {
        if (requestCode != REQUEST_DEVICE_LOGIN) {
            return false;
        }

        if (closed ||
                !running ||
                !waitingForCredential) {
            return true;
        }

        final int attempt = generation;
        waitingForCredential = false;

        if (resultCode == Activity.RESULT_OK) {
            succeed(attempt);
        } else {
            fail(
                    attempt,
                    "Вход не подтверждён. " +
                    "Магазин остаётся закрытым."
            );
        }

        return true;
    }

    private boolean current(int attempt) {
        return !closed &&
                running &&
                attempt == generation;
    }

    private void succeed(int attempt) {
        if (!current(attempt)) {
            return;
        }

        Callback result = callback;

        clearAttempt();

        if (result != null) {
            result.onAuthenticated();
        }
    }

    private void fail(
            int attempt,
            String message
    ) {
        if (!current(attempt)) {
            return;
        }

        Callback result = callback;

        clearAttempt();

        if (result != null) {
            result.onFailed(message);
        }
    }

    private void clearAttempt() {
        running = false;
        waitingForCredential = false;
        callback = null;

        CancellationSignal previous =
                cancellation;

        cancellation = null;

        /*
         * Запоздалые callbacks старой проверки
         * больше не должны открывать приложение.
         */
        generation++;

        if (previous != null) {
            previous.cancel();
        }
    }

    public void cancel() {
        clearAttempt();
    }

    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        clearAttempt();
    }
}

// КОНЕЦ ФАЙЛА
