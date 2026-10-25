package ru.makeevka.fishshop;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.telephony.PhoneNumberFormattingTextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public final class ShopComfort {

    private static final int BLACK = Color.rgb(14, 15, 17);
    private static final int GOLD = Color.rgb(212, 175, 55);

    private ShopComfort() {
    }

    public static Button cartButton(
            Activity activity,
            Runnable action
    ) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(BLACK);
        button.setTypeface(
                Typeface.create(
                        "sans-serif-medium", Typeface.NORMAL
                )
        );

        button.setMinHeight(dp(activity, 48));
        button.setMinimumHeight(dp(activity, 48));
        button.setPadding(
                dp(activity, 14), dp(activity, 10),
                dp(activity, 14), dp(activity, 10)
        );

        GradientDrawable background = new GradientDrawable();
        background.setColor(GOLD);
        background.setCornerRadius(dp(activity, 15));

        button.setBackgroundTintList(null);
        button.setBackground(background);
        button.setStateListAnimator(null);
        button.setVisibility(View.GONE);
        button.setOnClickListener(view -> action.run());

        return button;
    }

    public static void updateCart(
            Button button,
            boolean visible,
            boolean currentPrices,
            int positions,
            String sum,
            boolean pending
    ) {
        if (button == null) return;

        if (!visible) {
            button.setVisibility(View.GONE);
            return;
        }

        String title;

        if (pending) {
            title = "Проверить прежний заказ  →";
        } else if (currentPrices) {
            title = "В корзину · " + sum + "  →";
        } else {
            title = "Корзина · позиций: " + positions + "  →";
        }

        button.setText(title);
        button.setContentDescription(title);
        button.setVisibility(View.VISIBLE);
    }

    public static void attachPhone(EditText input) {
        if (input == null) return;

        input.setHint("+7 949 123-45-67");

        // Используем системное форматирование:
        // оно поддерживает вставку номера и изменение курсора.
        input.addTextChangedListener(
                new PhoneNumberFormattingTextWatcher("RU")
        );

        // Форматируем также ранее сохранённое значение.
        String existing = input.getText().toString();
        if (!existing.isEmpty()) {
            input.setText(existing);
            input.setSelection(input.length());
        }
    }

    public static boolean markError(
            TextView field,
            String explanation
    ) {
        if (field == null ||
                field.getParent() == null ||
                field.getVisibility() != View.VISIBLE) {
            return false;
        }

        field.setError(explanation);
        field.setFocusableInTouchMode(true);
        field.requestFocus();

        field.post(() -> {
            if (!field.isAttachedToWindow()) return;

            int margin = Math.round(
                    24 *
                    field.getResources()
                            .getDisplayMetrics().density
            );

            Rect rectangle = new Rect(
                    0,
                    -margin,
                    field.getWidth(),
                    field.getHeight() + margin
            );

            field.requestRectangleOnScreen(rectangle, false);
            field.announceForAccessibility(explanation);
        });

        return true;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(
                value *
                activity.getResources()
                        .getDisplayMetrics().density
        );
    }
}
// КОНЕЦ ФАЙЛА
