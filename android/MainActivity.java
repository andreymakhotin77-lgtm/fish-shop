package ru.makeevka.fishshop;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String API =
        "https://fish-shop-tttj.onrender.com";

    private LinearLayout root;
    private TextView totalLabel;
    private EditText nameInput, phoneInput, addressInput;
    private Switch delivery;
    private CheckBox consent;
    private Button submit;

    private boolean sending = false;
    private String pendingOrder;

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

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        pendingOrder = getPreferences(MODE_PRIVATE)
            .getString("pending_order", null);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        root.setBackgroundColor(Color.rgb(244, 248, 250));
        scroll.addView(root);
        setContentView(scroll);

        text("Лавка Вкуса", 28);
        text("Макеевка • доставка и самовывоз", 17);
        text(
            "Тестовая версия с отправкой заказов продавцу. " +
            "Количество выбирается в целых килограммах.",
            14
        );

        for (int i = 0; i < names.length; i++) {
            final int index = i;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(Color.WHITE);

            LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, dp(12), 0, 0);
            root.addView(card, params);

            TextView title = new TextView(this);
            title.setText(names[i]);
            title.setTextSize(21);
            card.addView(title);

            TextView price = new TextView(this);
            price.setText(prices[i] + " ₽/кг");
            price.setTextSize(17);
            card.addView(price);

            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            Button minus = new Button(this);
            minus.setText("−");
            row.addView(minus);

            TextView count = new TextView(this);
            count.setText("0 кг");
            count.setTextSize(19);
            count.setPadding(dp(12), 0, dp(12), 0);
            row.addView(count);
            counters[i] = count;

            Button plus = new Button(this);
            plus.setText("+");
            row.addView(plus);

            minus.setOnClickListener(v -> change(index, -1));
            plus.setOnClickListener(v -> change(index, 1));

            card.addView(row);
        }

        totalLabel = text("Товары: 0 ₽", 23);
        text("Данные для заказа", 22);

        nameInput = input(
            "Ваше имя",
            InputType.TYPE_CLASS_TEXT
        );

        phoneInput = input(
            "Телефон: +7…",
            InputType.TYPE_CLASS_PHONE
        );

        delivery = new Switch(this);
        delivery.setText("Доставка по Макеевке");
        delivery.setChecked(true);
        root.addView(delivery);

        addressInput = input(
            "Улица, дом, квартира",
            InputType.TYPE_CLASS_TEXT
        );

        delivery.setOnCheckedChangeListener((button, checked) ->
            addressInput.setVisibility(
                checked ? View.VISIBLE : View.GONE
            )
        );

        text(
            "Оплата при получении. Стоимость доставки не включена. " +
            "Продавец подтвердит наличие, вес и условия получения.",
            14
        );

        consent = new CheckBox(this);
        consent.setText(
            "Согласен передать имя, телефон и адрес продавцу " +
            "через сервер магазина и Telegram для обработки заказа."
        );
        root.addView(consent);

        submit = new Button(this);
        submit.setText(
            pendingOrder == null
                ? "Отправить заказ"
                : "Повторить прежний заказ"
        );
        submit.setOnClickListener(v -> prepareOrder());
        root.addView(submit);

        if (pendingOrder != null) {
            text(
                "Есть заказ без подтверждения. Кнопка повторит " +
                "именно его, а не данные новой формы.",
                14
            );
        }
    }

    private int dp(int value) {
        return Math.round(
            value * getResources().getDisplayMetrics().density
        );
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(25, 45, 55));
        view.setPadding(0, dp(8), 0, dp(8));
        root.addView(view);
        return view;
    }

    private EditText input(String hint, int type) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setInputType(type);
        root.addView(view);
        return view;
    }

    private int total() {
        int result = 0;
        for (int i = 0; i < prices.length; i++) {
            result += prices[i] * quantities[i];
        }
        return result;
    }

    private void change(int index, int delta) {
        if (sending || pendingOrder != null) return;

        quantities[index] = Math.max(
            0, Math.min(50, quantities[index] + delta)
        );
        counters[index].setText(quantities[index] + " кг");
        totalLabel.setText("Товары: " + total() + " ₽");
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
            message("Укажите адрес доставки.");
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
                    item.put("quantity", quantities[i]);
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

    private void sendOrder(String payload) {
        sending = true;
        submit.setEnabled(false);
        submit.setText("Отправка…");
        Toast.makeText(
            this,
            "Первый запрос после простоя может занять около минуты.",
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

                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

                try (java.io.OutputStream output =
                         connection.getOutputStream()) {
                    output.write(bytes);
                }

                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
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
                    if (
                        status == 400 || status == 409 ||
                        status == 413 || status == 415
                    ) {
                        getPreferences(MODE_PRIVATE).edit()
                            .remove("pending_order").commit();

                        runOnUiThread(() -> pendingOrder = null);
                    }

                    throw new Exception(
                        response.optString(
                            "error", "Ошибка сервера: " + status
                        )
                    );
                }

                String id = response.getString("id");
                int amount = response.getInt("totalRub");

                getPreferences(MODE_PRIVATE).edit()
                    .remove("pending_order").commit();

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    pendingOrder = null;
                    sending = false;
                    submit.setEnabled(true);
                    submit.setText("Отправить заказ");

                    for (int i = 0; i < quantities.length; i++) {
                        quantities[i] = 0;
                        counters[i].setText("0 кг");
                    }

                    totalLabel.setText("Товары: 0 ₽");

                    message(
                        "Заказ передан продавцу!\n\n" +
                        "Номер: " + id + "\n" +
                        "Товары: " + amount + " ₽\n\n" +
                        "Ожидайте подтверждения по телефону."
                    );
                });
            } catch (Exception error) {
                String details = error.getMessage();

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    sending = false;
                    submit.setEnabled(true);
                    submit.setText(
                        pendingOrder == null
                            ? "Отправить заказ"
                            : "Повторить прежний заказ"
                    );

                    message(
                        "Нет подтверждения отправки.\n\n" +
                        (details == null ? "Ошибка соединения." : details) +
                        "\n\nЕсли заказ уже появился у продавца, " +
                        "не отправляйте его повторно."
                    );
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void message(String value) {
        new AlertDialog.Builder(this)
            .setMessage(value)
            .setPositiveButton("Понятно", null)
            .show();
    }
}
