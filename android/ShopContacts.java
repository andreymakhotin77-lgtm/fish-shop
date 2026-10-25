package ru.makeevka.fishshop;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class ShopContacts {

    private static final int BLACK = Color.rgb(14, 15, 17);
    private static final int CARD = Color.rgb(25, 27, 30);
    private static final int GOLD = Color.rgb(212, 175, 55);
    private static final int WHITE = Color.rgb(247, 244, 236);
    private static final int MUTED = Color.rgb(166, 167, 163);

    private static final String PHONE = "+79491234567";
    private static final String TELEGRAM =
            "https://t.me/lavka_vkusa";

    private static final String ADDRESS =
            "г. Макеевка, ул. Плеханова, 1в";

    private ShopContacts() {
    }

    public static void addTo(
            Activity activity,
            LinearLayout parent
    ) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(
                dp(activity, 16),
                dp(activity, 16),
                dp(activity, 16),
                dp(activity, 16)
        );

        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(activity, 20));
        background.setStroke(dp(activity, 1), GOLD);
        card.setBackground(background);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(
                0, dp(activity, 12),
                0, dp(activity, 12)
        );
        parent.addView(card, params);

        TextView heading = text(
                activity, "Связаться с магазином", 21, GOLD
        );
        heading.setTypeface(
                Typeface.create(
                        "sans-serif-medium", Typeface.NORMAL
                )
        );
        card.addView(heading);

        card.addView(text(
                activity,
                "Лавка Вкуса\n" + ADDRESS,
                15, WHITE
        ));

        card.addView(text(
                activity,
                "Время работы: 08:00–18:00\n" +
                "Телефон: +7 949 123 45 67\n" +
                "Telegram: @lavka_vkusa",
                14, MUTED
        ));

        addButton(activity, card, "Позвонить", () ->
                open(
                        activity,
                        new Intent(
                                Intent.ACTION_DIAL,
                                Uri.parse("tel:" + PHONE)
                        ),
                        "Не удалось открыть набор номера."
                )
        );

        addButton(
                activity, card,
                "Написать в Telegram",
                () -> open(
                        activity,
                        new Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(TELEGRAM)
                        ),
                        "Не удалось открыть Telegram или браузер."
                )
        );

        addButton(
                activity, card,
                "Открыть адрес на карте",
                () -> openMap(activity)
        );
    }

    private static void openMap(Activity activity) {
        Uri location = Uri.parse(
                "geo:0,0?q=" + Uri.encode(ADDRESS)
        );

        try {
            activity.startActivity(
                    new Intent(Intent.ACTION_VIEW, location)
            );
        } catch (ActivityNotFoundException error) {
            Uri webMap = Uri.parse(
                    "https://yandex.ru/maps/?text=" +
                    Uri.encode(ADDRESS)
            );
            open(
                    activity,
                    new Intent(Intent.ACTION_VIEW, webMap),
                    "Не удалось открыть карту. Адрес: " + ADDRESS
            );
        } catch (SecurityException error) {
            toast(
                    activity,
                    "Открытие карты запрещено. Адрес: " + ADDRESS
            );
        }
    }

    private static void open(
            Activity activity,
            Intent intent,
            String errorMessage
    ) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            toast(activity, errorMessage);
        }
    }

    private static TextView text(
            Activity activity,
            String value,
            int size,
            int color
    ) {
        TextView result = new TextView(activity);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setPadding(
                0, dp(activity, 5),
                0, dp(activity, 5)
        );
        result.setLineSpacing(dp(activity, 3), 1);
        return result;
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
        button.setTextSize(14);
        button.setTextColor(BLACK);
        button.setMinHeight(dp(activity, 48));
        button.setMinimumHeight(dp(activity, 48));
        button.setPadding(
                dp(activity, 12), dp(activity, 10),
                dp(activity, 12), dp(activity, 10)
        );

        GradientDrawable background = new GradientDrawable();
        background.setColor(GOLD);
        background.setCornerRadius(dp(activity, 14));

        button.setBackgroundTintList(null);
        button.setBackground(background);
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> action.run());
        button.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(
                0, dp(activity, 6),
                0, dp(activity, 4)
        );
        parent.addView(button, params);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(
                value *
                activity.getResources()
                        .getDisplayMetrics().density
        );
    }

    private static void toast(
            Activity activity,
            String message
    ) {
        Toast.makeText(
                activity, message, Toast.LENGTH_LONG
        ).show();
    }
}
// КОНЕЦ ФАЙЛА
