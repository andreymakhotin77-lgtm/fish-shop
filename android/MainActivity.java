package ru.makeevka.fishshop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String API =
        "https://fish-shop-tttj.onrender.com";

    private static final int BLACK = Color.rgb(18, 18, 18);
    private static final int CARD = Color.rgb(32, 32, 32);
    private static final int GOLD = Color.rgb(212, 175, 55);
    private static final int LIGHT = Color.rgb(245, 240, 230);
    private static final int MUTED = Color.rgb(184, 176, 160);

    private final String[] ids = {
        "crayfish", "mackerel", "bream"
    };

    private final String[] names = {
        "Раки живые",
        "Скумбрия без головы",
        "Тушка леща с икрой"
    };

    private final int[] prices = {4500, 1400, 1700};
    private final int[] quantities = new int[3];
    private final TextView[] counters = new TextView[3];

    private LinearLayout root;
    private TextView totalLabel;
    private TextView pendingLabel;
    private EditText nameInput;
    private EditText phoneInput;
    private EditText addressInput;
    private Switch delivery;
    private CheckBox consent;
    private Button submit;

    private boolean sending = false;
    private String pendingOrder;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);

        pendingOrder = getPreferences(MODE_PRIVATE)
            .getString("pending_order", null);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BLACK);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(30));

        scroll.addView(root);
        setContentView(scroll);

        TextView brand = addText(root, "ЛАВКА ВКУСА", 29, GOLD);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setLetterSpacing(0.06f);

        addText(
            root,
            "Рыба • морепродукты • живые раки",
            15,
            MUTED
        );

        LinearLayout deliveryCard = card(root);

        addText(
            deliveryCard,
            "БЕСПЛАТНАЯ ДОСТАВКА ОТ 2 000 ₽",
            17,
            GOLD
        ).setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        addText(
            deliveryCard,
            "По Макеевке, Донецку и Харцызску. " +
            "Ближайшие города — по согласованию.",
            14,
            LIGHT
        );

        addText(root, "Наш ассортимент", 23, GOLD);

        addText(
            root,
            "Количество выбирается в целых килограммах.",
            14,
            MUTED
        );

        for (int i = 0; i < names.length; i++) {
            final int index = i;

            LinearLayout productCard = card(root);

            TextView productName = addText(
                productCard,
                names[i],
                21,
                LIGHT
            );
            productName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

            addText(
                productCard,
                money(prices[i]) + " ₽ / кг",
                20,
                GOLD
            );

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);

            Button minus = goldButton("−");
            row.addView(
                minus,
                new LinearLayout.LayoutParams(dp(64), dp(52))
            );

            TextView count = new TextView(this);
            count.setText("0 кг");
            count.setTextColor(LIGHT);
            count.setTextSize(20);
            count.setGravity(Gravity.CENTER);

            row.addView(
                count,
                new LinearLayout.LayoutParams(0, dp(52), 1f)
            );
            counters[i] = count;

            Button plus = goldButton("+");
            row.addView(
                plus,
                new LinearLayout.LayoutParams(dp(64), dp(52))
            );

            minus.setOnClickListener(v -> change(index, -1));
            plus.setOnClickListener(v -> change(index, 1));

            productCard.addView(row);
        }

        totalLabel = addText(root, "Товары: 0 ₽", 24, GOLD);
        totalLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        LinearLayout pickupCard = card(root);

        addText(pickupCard, "Самовывоз", 20, GOLD);

        addText(
            pickupCard,
            "г. Макеевка, ул. Плеханова, 1в\n" +
            "Магазин «Лавка Вкуса»",
            16,
            LIGHT
        );

        addText(root, "Оформление заказа", 23, GOLD);

        nameInput = input(
            "Ваше имя",
            InputType.TYPE_CLASS_TEXT
        );

        phoneInput = input(
            "Телефон: +7…",
            InputType.TYPE_CLASS_PHONE
        );

        delivery = new Switch(this);
        delivery.setText("Доставка");
        delivery.setTextColor(LIGHT);
        delivery.setTextSize(18);
        delivery.setPadding(0, dp(14), 0, dp(14));
        delivery.setChecked(true);
        delivery.setThumbTintList(ColorStateList.valueOf(GOLD));
        delivery.setTrackTintList(
            new ColorStateList(
                new int[][] {
                    new int[] {android.R.attr.state_checked},
                    new int[] {}
                },
                new int[] {
                    Color.rgb(138, 113, 41),
                    Color.rgb(75, 75, 75)
                }
            )
        );
        root.addView(delivery);

        addText(
            root,
            "Выключите доставку, если заберёте заказ в магазине.",
            14,
            MUTED
        );

        addressInput = input(
            "Город, улица, дом, квартира",
            InputType.TYPE_CLASS_TEXT
        );

        delivery.setOnCheckedChangeListener((button, checked) ->
            addressInput.setVisibility(
                checked ? View.VISIBLE : View.GONE
            )
        );

        addText(
            root,
            "Бесплатная доставка от 2 000 ₽ по Макеевке, " +
            "Донецку и Харцызску. Условия доставки в ближайшие " +
            "города и для заказов до 2 000 ₽ уточняются у продавца.\n\n" +
            "Оплата при получении. Наличие, вес, итоговая сумма " +
            "и время получения подтверждаются по телефону.",
            14,
            MUTED
        );

        consent = new CheckBox(this);
        consent.setText(
            "Согласен передать имя, телефон и адрес продавцу " +
            "через сервер магазина и Telegram для обработки заказа."
        );
        consent.setTextColor(LIGHT);
        consent.setTextSize(14);
        consent.setPadding(0, dp(10), 0, dp(10));
        consent.setButtonTintList(
            new ColorStateList(
                new int[][] {
                    new int[] {android.R.attr.state_checked},
                    new int[] {}
                },
                new int[] {GOLD, MUTED}
            )
        );
        root.addView(consent);

        pendingLabel = addText(
            root,
            "Есть прежний заказ без подтверждения. Кнопка повторит " +
            "именно его, а не данные новой формы. Если заказ уже " +
            "пришёл продавцу, повторять отправку не нужно.",
            14,
            GOLD
        );
        pendingLabel.setVisibility(
            pendingOrder == null ? View.GONE : View.VISIBLE
        );

        submit = goldButton("");
        submit.setTextSize(17);

        LinearLayout.LayoutParams submitParams =
            new LinearLayout.LayoutParams(-1, dp(60));
        submitParams.setMargins(0, dp(12), 0, dp(8));

        root.addView(submit, submitParams);
        submit.setOnClickListener(v -> prepareOrder());
        updateButton();

        addText(
            root,
            "Тестовая версия магазина. Отправка заказов подключена.",
            12,
            MUTED
        );
    }

    private int dp(int value) {
        return Math.round(
            value * getResources().getDisplayMetrics().density
        );
    }

    private String money(int value) {
        return String.format(
            new java.util.Locale("ru", "RU"),
            "%,d",
            value
        );
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(12), dp(16), dp(14));

        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(16));
        background.setStroke(1, Color.rgb(78, 66, 36));
        layout.setBackground(background);

        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(12), 0, dp(4));

        parent.addView(layout, params);
        return layout;
    }

    private TextView addText(
        LinearLayout parent,
        String value,
        int size,
        int color
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(7), 0, dp(7));
        parent.addView(view);
        return view;
    }

    private EditText input(String hint, int type) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setInputType(type);
        view.setTextColor(LIGHT);
        view.setHintTextColor(MUTED);
        view.setTextSize(17);
        view.setBackgroundTintList(ColorStateList.valueOf(GOLD));
        view.setPadding(dp(6), dp(14), dp(6), dp(14));
        root.addView(view);
        return view;
    }

    private Button goldButton(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(BLACK);
        button.setTextSize(20);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        GradientDrawable background = new GradientDrawable();
        background.setColor(GOLD);
        background.setCornerRadius(dp(12));

        button.setBackground(background);
        button.setBackgroundTintList(
            new ColorStateList(
                new int[][] {
                    new int[] {-android.R.attr.state_enabled},
                    new int[] {android.R.attr.state_pressed},
                    new int[] {}
                },
                new int[] {
                    Color.rgb(129, 116, 77),
                    Color.rgb(184, 148, 37),
                    GOLD
                }
            )
        );

        return button;
    }

    private int total() {
        int result = 0;

        for (int i = 0; i < prices.length; i++) {
            result += prices[i] * quantities[i];
        }

        return result;
    }

    private void updateButton() {
        submit.setEnabled(!sending);

        if (sending) {
            submit.setText("Отправка…");
        } else if (pendingOrder != null) {
            submit.setText("Повторить прежний заказ");
        } else {
            submit.setText(
                "Оформить заказ · " + money(total()) + " ₽"
            );
        }
    }

    private void change(int index, int delta) {
        if (sending || pendingOrder != null) return;

        quantities[index] = Math.max(
            0,
            Math.min(50, quantities[index] + delta)
        );

        counters[index].setText(quantities[index] + " кг");
        totalLabel.setText("Товары: " + money(total()) + " ₽");
        updateButton();
    }

    private void setSending(boolean value) {
        sending = value;
        nameInput.setEnabled(!value);
        phoneInput.setEnabled(!value);
        addressInput.setEnabled(!value);
        delivery.setEnabled(!value);
        consent.setEnabled(!value);
        updateButton();
    }

    private void prepareOrder() {
        if (sending) return;

        if (pendingOrder != null) {
            sendOrder(pendingOrder);
            return;
        }

        String name = nameInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim();
        String digits = phone.replaceAll("[^0-9]", "");
        String address = addressInput.getText().toString().trim();

        if (total() == 0) {
            message("Добавьте товар в корзину.");
            return;
        }

        if (name.length() < 2) {
            message("Укажите имя.");
            return;
        }

        if (!digits.matches("[78][0-9]{10}")) {
            message("Укажите телефон: +7 и ещё 10 цифр.");
            return;
        }

        if (delivery.isChecked() && address.length() < 5) {
            message("Укажите город и адрес доставки.");
            return;
        }

        if (!consent.isChecked()) {
            message("Нужно согласие на передачу данных заказа.");
            return;
        }

        try {
            JSONArray items = new JSONArray();

            for (int i = 0; i < ids.length; i++) {
                if (quantities[i] > 0) {
                    JSONObject item = new JSONObject();
                    item.put("productId", ids[i]);
                    item.put("quantity", quantities[i])
                                         items.put(item);
                }
            }

            JSONObject order = new JSONObject();
            order.put("requestId", UUID.randomUUID().toString());
            order.put("name", name);
            order.put("phone", phone);
            order.put(
                "fulfillment",
                delivery.isChecked() ? "delivery" : "pickup"
            );
            order.put(
                "address",
                delivery.isChecked() ? address : ""
            );
            order.put("comment", "");
            order.put("items", items);

            String payload = order.toString();

            boolean saved = getPreferences(MODE_PRIVATE)
                .edit()
                .putString("pending_order", payload)
                .commit();

            if (!saved) {
                message("Не удалось сохранить запрос на телефоне.");
                return;
            }

            pendingOrder = payload;
            sendOrder(payload);

        } catch (Exception error) {
            message("Не удалось подготовить заказ.");
        }
    }

    private static class ApiException extends Exception {
        final boolean clearPending;

        ApiException(String message, boolean clearPending) {
            super(message);
            this.clearPending = clearPending;
        }
    }

    private void sendOrder(String payload) {
        setSending(true);

        Toast.makeText(
            this,
            "После простоя сервера отправка может занять около минуты.",
            Toast.LENGTH_LONG
        ).show();

        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                connection = (HttpURLConnection)
                    new URL(API + "/api/orders").openConnection();

                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(120000);
                connection.setDoOutput(true);
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                );

                byte[] bytes =
                    payload.getBytes(StandardCharsets.UTF_8);

                try (OutputStream output =
                         connection.getOutputStream()) {
                    output.write(bytes);
                }

                int status = connection.getResponseCode();

                InputStream stream =
                    status >= 200 && status < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

                String responseText = "";

                if (stream != null) {
                    try (
                        InputStream input = stream;
                        ByteArrayOutputStream output =
                            new ByteArrayOutputStream()
                    ) {
                        byte[] buffer = new byte[4096];
                        int count;

                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }

                        responseText = output.toString("UTF-8");
                    }
                }

                JSONObject response = new JSONObject(responseText);

                if (status < 200 || status >= 300) {
                    boolean clear =
                        status == 400 ||
                        status == 409 ||
                        status == 413 ||
                        status == 415;

                    throw new ApiException(
                        response.optString(
                            "error",
                            "Ошибка сервера: " + status
                        ),
                        clear
                    );
                }

                String id = response.getString("id");
                int amount = response.getInt("totalRub");

                getPreferences(MODE_PRIVATE)
                    .edit()
                    .remove("pending_order")
                    .commit();

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    pendingOrder = null;
                    pendingLabel.setVisibility(View.GONE);

                    for (int i = 0; i < quantities.length; i++) {
                        quantities[i] = 0;
                        counters[i].setText("0 кг");
                    }

                    totalLabel.setText("Товары: 0 ₽");
                    setSending(false);

                    message(
                        "Заказ передан продавцу!\n\n" +
                        "Номер: " + id + "\n\n" +
                        "Товары: " + money(amount) + " ₽\n\n" +
                        "Ожидайте подтверждения наличия, " +
                        "итоговой суммы и получения по телефону."
                    );
                });

            } catch (Exception error) {
                boolean clear =
                    error instanceof ApiException &&
                    ((ApiException) error).clearPending;

                if (clear) {
                    getPreferences(MODE_PRIVATE)
                        .edit()
                        .remove("pending_order")
                        .commit();
                }

                String details = error.getMessage();

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    if (clear) {
                        pendingOrder = null;
                    }

                    pendingLabel.setVisibility(
                        pendingOrder == null
                            ? View.GONE
                            : View.VISIBLE
                    );

                    setSending(false);

                    message(
                        "Нет подтверждения отправки.\n\n" +
                        (details == null
                            ? "Ошибка соединения."
                            : details) +
                        "\n\nЕсли заказ уже появился у продавца, " +
                        "не отправляйте его повторно."
                    );
                });

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private void message(String value) {
        if (isFinishing() || isDestroyed()) return;

        AlertDialog dialog = new AlertDialog.Builder(
            this,
            android.R.style.Theme_Material_Dialog_Alert
        )
            .setTitle("Лавка Вкуса")
            .setMessage(value)
            .setPositiveButton("Понятно", null)
            .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(GOLD);

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(CARD)
                );
            }
        });

        dialog.show();
    }
}

