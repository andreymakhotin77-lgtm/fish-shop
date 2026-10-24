package ru.makeevka.fishshop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

public class StoreActivity extends Activity {

    private final int BLACK = Color.rgb(18, 18, 18);
    private final int CARD = Color.rgb(32, 32, 32);
    private final int GOLD = Color.rgb(212, 175, 55);
    private final int WHITE = Color.rgb(245, 240, 230);
    private final int MUTED = Color.rgb(183, 178, 166);

    private final String[] tabs = {
            "Главная", "Каталог", "Избранное", "Корзина", "Профиль"
    };

    private final List<Product> products = new ArrayList<>();
    private final Map<String, Integer> cart = new HashMap<>();
    private Set<String> favorites = new HashSet<>();

    private ShopApi api;
    private SharedPreferences prefs;

    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout navigation;

    private EditText nameInput;
    private EditText phoneInput;
    private EditText addressInput;
    private EditText commentInput;

    private String customerName = "";
    private String customerPhone = "";
    private String customerAddress = "";
    private String customerComment = "";

    private String category = "";
    private String search = "";
    private String pendingOrder;

    private int currentTab = 0;
    private boolean delivery = true;
    private boolean consent = false;
    private boolean freshCatalog = false;
    private boolean loading = false;
    private boolean sending = false;
    private boolean destroyed = false;

    private static class Product {
        String id, name, category, description, unit;
        int price;
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        prefs = getPreferences(MODE_PRIVATE);
        api = new ShopApi();

        customerName = prefs.getString("customer_name", "");
        customerPhone = prefs.getString("customer_phone", "");
        customerAddress = prefs.getString("customer_address", "");
        delivery = prefs.getBoolean("delivery", true);
        pendingOrder = prefs.getString("pending_order", null);

        favorites = new HashSet<>(
                prefs.getStringSet("favorites_v2", new HashSet<>())
        );

        try {
            JSONObject saved = new JSONObject(
                    prefs.getString("cart_v2", "{}")
            );

            Iterator<String> keys = saved.keys();

            while (keys.hasNext()) {
                String id = keys.next();
                int quantity = saved.optInt(id, 0);

                if (quantity > 0 && quantity <= 50) {
                    cart.put(id, quantity);
                }
            }
        } catch (Exception ignored) {
        }

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(24));
        scroll.addView(content);

        root.addView(scroll, new LinearLayout.LayoutParams(
                -1, 0, 1
        ));

        navigation = new LinearLayout(this);
        navigation.setPadding(dp(3), dp(7), dp(3), dp(7));
        navigation.setBackgroundColor(CARD);
        root.addView(navigation);

        setContentView(root);

        render();
        loadCatalog();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable background(int color, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(16));

        if (border) {
            drawable.setStroke(dp(1), GOLD);
        }

        return drawable;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(BLACK);
        button.setTextSize(14);
        button.setBackgroundTintList(ColorStateList.valueOf(GOLD));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(15), dp(12), dp(15), dp(12));
        panel.setBackground(background(CARD, false));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, dp(8));

        content.addView(panel, params);
        return panel;
    }

    private EditText input(
            LinearLayout parent, String hint, String value, int type
    ) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setInputType(type);
        field.setTextColor(WHITE);
        field.setHintTextColor(MUTED);
        field.setTextSize(16);
        field.setBackgroundTintList(ColorStateList.valueOf(GOLD));
        field.setPadding(dp(4), dp(12), dp(4), dp(12));
        parent.addView(field);
        return field;
    }

    private void message(String title, String value) {
        if (destroyed) return;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(value)
                .setPositiveButton("Понятно", null)
                .create();

        dialog.setOnShowListener(v -> {
            dialog.getWindow().setBackgroundDrawable(
                    background(CARD, true)
            );
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(GOLD);

            TextView body = dialog.findViewById(android.R.id.message);
            if (body != null) body.setTextColor(WHITE);
        });

        dialog.show();
    }

    private void saveProfile() {
        if (nameInput != null) {
            customerName = nameInput.getText().toString().trim();
        }
        if (phoneInput != null) {
            customerPhone = phoneInput.getText().toString().trim();
        }
        if (addressInput != null) {
            customerAddress = addressInput.getText().toString().trim();
        }
        if (commentInput != null) {
            customerComment = commentInput.getText().toString().trim();
        }

        prefs.edit()
                .putString("customer_name", customerName)
                .putString("customer_phone", customerPhone)
                .putString("customer_address", customerAddress)
                .putBoolean("delivery", delivery)
                .apply();
    }

    private void saveCart() {
        JSONObject data = new JSONObject();

        try {
            for (Map.Entry<String, Integer> item : cart.entrySet()) {
                data.put(item.getKey(), item.getValue());
            }
        } catch (Exception ignored) {
        }

        prefs.edit().putString("cart_v2", data.toString()).apply();
    }

    private void openTab(int tab) {
        saveProfile();
        currentTab = tab;
        render();
    }

    private void render() {
        if (destroyed) return;

        nameInput = null;
        phoneInput = null;
        addressInput = null;
        commentInput = null;

        content.removeAllViews();
        navigation.removeAllViews();

        TextView title = text("ЛВ  ·  Лавка Вкуса", 25, GOLD);
        title.setTypeface(null, Typeface.BOLD);
        content.addView(title);
        content.addView(text("Рыба · Морепродукты · Живые раки", 14, MUTED));

        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            Button item = button(tabs[i], () -> openTab(tab));
            item.setTextSize(10);
            item.setPadding(0, 0, 0, 0);
            item.setMinWidth(0);
            item.setMinimumWidth(0);
            item.setTextColor(i == currentTab ? BLACK : GOLD);
            item.setBackgroundTintList(ColorStateList.valueOf(
                    i == currentTab ? GOLD : CARD
            ));

            navigation.addView(item,
                    new LinearLayout.LayoutParams(0, dp(54), 1));
        }

        if (loading) {
            content.addView(text(
                    "Обновляем каталог… После простоя серверу " +
                    "может потребоваться около минуты.",
                    14, MUTED
            ));
        } else if (!freshCatalog) {
            content.addView(text(
                    "Каталог пока не загружен. Для оформления " +
                    "заказа нужен интернет.",
                    14, MUTED
            ));
            content.addView(button("Загрузить каталог", this::loadCatalog));
        }

        if (currentTab == 0) showHome();
        if (currentTab == 1) showCatalog(false);
        if (currentTab == 2) showCatalog(true);
        if (currentTab == 3) showCart();
        if (currentTab == 4) showProfile();
    }

    private void showHome() {
        LinearLayout banner = panel();
        banner.addView(text("Свежий выбор для вашего стола", 23, GOLD));
        banner.addView(text(
                "Бесплатная доставка от 2 000 ₽ по Макеевке, " +
                "Донецку и Харцызску.",
                16, WHITE
        ));
        banner.addView(text(
                "Другие города и заказы до 2 000 ₽ — " +
                "доставка по согласованию. Оплата при получении.",
                13, MUTED
        ));
        banner.addView(button("Перейти в каталог", () -> openTab(1)));

        LinearLayout pickup = panel();
        pickup.addView(text("Самовывоз", 19, GOLD));
        pickup.addView(text(
                "г. Макеевка, ул. Плеханова, 1в\nМагазин «Лавка Вкуса»",
                16, WHITE
        ));

        content.addView(text("Категории", 21, WHITE));

        Set<String> categories = new LinkedHashSet<>();
        for (Product p : products) categories.add(p.category);

        for (String name : categories) {
            content.addView(button(name, () -> {
                category = name;
                search = "";
                openTab(1);
            }));
        }

        content.addView(text("Наш ассортимент", 21, WHITE));
        for (int i = 0; i < Math.min(3, products.size()); i++) {
            productCard(products.get(i));
        }
    }

    private void showCatalog(boolean onlyFavorites) {
        content.addView(text(
                onlyFavorites ? "Избранное" : "Каталог", 23, WHITE
        ));

        if (!onlyFavorites) {
            EditText query = input(
                    content,
                    "Поиск по названию",
                    search,
                    InputType.TYPE_CLASS_TEXT
            );

            content.addView(button("Найти", () -> {
                search = query.getText().toString().trim();
                render();
            }));

            if (!category.isEmpty() || !search.isEmpty()) {
                content.addView(text(
                        category.isEmpty() ? "Все категории" : category,
                        14, GOLD
                ));

                content.addView(button("Сбросить фильтр", () -> {
                    category = "";
                    search = "";
                    render();
                }));
            }
        }

        int count = 0;
        String query = search.toLowerCase(Locale.ROOT);

        for (Product p : products) {
            if (onlyFavorites && !favorites.contains(p.id)) continue;

            if (!onlyFavorites) {
                if (!category.isEmpty() && !category.equals(p.category)) {
                    continue;
                }

                if (!p.name.toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
            }

            productCard(p);
            count++;
        }

        if (count == 0) {
            content.addView(text(
                    onlyFavorites
                            ? "Добавляйте любимые товары кнопкой «В избранное»."
                            : "Подходящих товаров пока нет.",
                    16, MUTED
            ));
        }
    }

    private Product findProduct(String id) {
        for (Product p : products) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private int quantity(String id) {
        Integer value = cart.get(id);
        return value == null ? 0 : value;
    }

    private long total() {
        long sum = 0;
        for (Product p : products) {
            sum += (long) p.price * quantity(p.id);
        }
        return sum;
    }

    private void change(Product product, int delta) {
        if (pendingOrder != null || sending) {
            message(
                    "Есть неподтверждённый заказ",
                    "Сначала проверьте его отправку в разделе «Корзина»."
            );
            return;
        }

        saveProfile();

        int value = Math.max(
                0, Math.min(50, quantity(product.id) + delta)
        );

        if (value == 0) cart.remove(product.id);
        else cart.put(product.id, value);

        saveCart();
        render();
    }

        private void productCard(Product product) {
        LinearLayout card = panel();

        ProductArtwork artwork = new ProductArtwork(
                this,
                product.name,
                product.category
        );

        card.addView(
                artwork,
                new LinearLayout.LayoutParams(-1, dp(170))
        );

        card.addView(text(product.category, 13, MUTED));

        card.addView(text(product.name, 20, WHITE));
        card.addView(text(
                product.price + " ₽ / " + product.unit, 21, GOLD
        ));

        if (!product.description.isEmpty()) {
            card.addView(text(product.description, 14, MUTED));
        }

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        controls.addView(button("−", () -> change(product, -1)),
                new LinearLayout.LayoutParams(dp(62), dp(50)));

        TextView count = text(
                quantity(product.id) + " " + product.unit, 17, WHITE
        );
        count.setGravity(Gravity.CENTER);

        controls.addView(count,
                new LinearLayout.LayoutParams(0, -2, 1));

        controls.addView(button("+", () -> change(product, 1)),
                new LinearLayout.LayoutParams(dp(62), dp(50)));

        card.addView(controls);

        card.addView(button(
                favorites.contains(product.id)
                        ? "♥ В избранном"
                        : "♡ В избранное",
                () -> {
                    if (!favorites.add(product.id)) {
                        favorites.remove(product.id);
                    }

                    prefs.edit()
                            .putStringSet(
                                    "favorites_v2",
                                    new HashSet<>(favorites)
                            )
                            .apply();

                    saveProfile();
                    render();
                }
        ));
    }

    private void customerFields(LinearLayout parent, boolean order) {
        nameInput = input(
                parent, "Ваше имя", customerName,
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_CAP_WORDS
        );

        phoneInput = input(
                parent, "Телефон: +7…", customerPhone,
                InputType.TYPE_CLASS_PHONE
        );

        if (!order) {
            addressInput = input(
                    parent, "Адрес доставки", customerAddress,
                    InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            );
            return;
        }

        Switch mode = new Switch(this);
        mode.setText("Доставка — выключите для самовывоза");
        mode.setTextColor(WHITE);
        mode.setTextSize(14);
        mode.setChecked(delivery);
        mode.setButtonTintList(ColorStateList.valueOf(GOLD));
        parent.addView(mode);

        addressInput = input(
                parent, "Город, улица, дом, квартира", customerAddress,
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        addressInput.setVisibility(delivery ? View.VISIBLE : View.GONE);

        mode.setOnCheckedChangeListener((view, checked) -> {
            delivery = checked;
            addressInput.setVisibility(
                    checked ? View.VISIBLE : View.GONE
            );
            saveProfile();
        });

        commentInput = input(
                parent, "Комментарий к заказу", customerComment,
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );

        CheckBox agreement = new CheckBox(this);
        agreement.setText(
                "Согласен на передачу имени, телефона и адреса " +
                "магазину для обработки этого заказа."
        );
        agreement.setTextColor(MUTED);
        agreement.setTextSize(13);
        agreement.setButtonTintList(ColorStateList.valueOf(GOLD));
        agreement.setChecked(consent);
        agreement.setOnCheckedChangeListener(
                (view, checked) -> consent = checked
        );
        parent.addView(agreement);
    }

    private void showCart() {
        content.addView(text("Корзина", 23, WHITE));

        if (pendingOrder != null) {
            LinearLayout pending = panel();
            pending.addView(text("Есть неподтверждённый заказ", 19, GOLD));
            pending.addView(text(
                    "Проверка отправит тот же идентификатор заказа. " +
                    "Сервер не создаст второй заказ с тем же идентификатором.",
                    14, MUTED
            ));

            Button retry = button(
                    sending ? "Проверяем…" : "Проверить прежний заказ",
                    this::confirmPending
            );
            retry.setEnabled(!sending);
            pending.addView(retry);
        }

        for (String id : cart.keySet()) {
            if (findProduct(id) == null) {
                content.addView(text(
                        "В корзине есть недоступный товар: " + id,
                        14, GOLD
                ));
            }
        }

        for (Product p : products) {
            if (quantity(p.id) > 0) productCard(p);
        }

        if (cart.isEmpty()) {
            content.addView(text("Ваша корзина пока пуста.", 17, MUTED));
            content.addView(button("Выбрать товары", () -> openTab(1)));
            return;
        }

        LinearLayout summary = panel();
        summary.addView(text("Товары: " + total() + " ₽", 23, GOLD));
        summary.addView(text(
                "От 2 000 ₽ — бесплатная доставка по Макеевке, " +
                "Донецку и Харцызску. Иначе стоимость согласуется. " +
                "Самовывоз: ул. Плеханова, 1в.",
                14, MUTED
        ));
        summary.addView(text("Оплата при получении", 16, WHITE));

        if (pendingOrder != null) return;

        customerFields(summary, true);

        Button send = button(
                sending ? "Отправляем…" : "Оформить заказ",
                this::prepareOrder
        );
        send.setEnabled(freshCatalog && !sending);
        summary.addView(send);

        summary.addView(button("Очистить корзину", () -> {
            if (sending || pendingOrder != null) return;
            saveProfile();
            cart.clear();
            saveCart();
            render();
        }));
    }

    private void showProfile() {
        content.addView(text("Профиль покупателя", 23, WHITE));

        LinearLayout profile = panel();
        profile.addView(text(
                "Данные сохраняются только на этом устройстве. " +
                "Это не регистрация и не личный кабинет на сервере.",
                14, MUTED
        ));

        customerFields(profile, false);

        profile.addView(button("Сохранить данные", () -> {
            saveProfile();
            message("Готово", "Данные сохранены на этом устройстве.");
        }));

        LinearLayout history = panel();
        history.addView(text("Последний заказ с этого устройства", 19, GOLD));
        history.addView(text(
                prefs.getString(
                        "last_order_v2",
                        "Подтверждённых заказов пока нет."
                ),
                15, WHITE
        ));
        history.addView(text(
                "Отслеживание статуса покупателем пока не подключено.",
                13, MUTED
        ));
    }

    private void loadCatalog() {
        if (loading || destroyed) return;

        saveProfile();
        loading = true;
        render();

        api.loadProducts(new ShopApi.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;

                try {
                    JSONArray array = data.getJSONArray("products");
                    List<Product> loaded = new ArrayList<>();

                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.getJSONObject(i);
                        Product p = new Product();

                        p.id = item.getString("id");
                        p.name = item.getString("name");
                        p.category = item.optString("category", "Каталог");
                        p.description = item.optString("description", "");
                        p.unit = item.optString("unit", "кг");
                        p.price = item.getInt("priceRub");

                        if (p.price <= 0 || p.id.isEmpty()) {
                            throw new Exception("Некорректный товар");
                        }

                        loaded.add(p);
                    }

                    saveProfile();
                    products.clear();
                    products.addAll(loaded);
                    freshCatalog = true;
                    loading = false;
                    render();

                } catch (Exception error) {
                    saveProfile();
                    loading = false;
                    freshCatalog = false;
                    render();
                    message("Ошибка", "Не удалось прочитать каталог.");
                }
            }

            @Override
            public void onError(ShopApi.ApiError error) {
                if (destroyed) return;
                saveProfile();
                loading = false;
                freshCatalog = false;
                render();
                message("Каталог", error.getMessage());
            }
        });
    }

    private void prepareOrder() {
        if (sending || pendingOrder != null) return;

        saveProfile();

        String phone = customerPhone.replaceAll("[^0-9]", "");

        if (!freshCatalog || cart.isEmpty()) {
            message("Проверьте заказ", "Загрузите каталог и выберите товары.");
            return;
        }

        if (customerName.length() < 2 || customerName.length() > 80) {
            message("Проверьте имя", "Введите имя от 2 до 80 символов.");
            return;
        }

        if (!phone.matches("[78][0-9]{10}")) {
            message("Проверьте телефон", "Пример: +7 949 123-45-67.");
            return;
        }

        if (delivery && (
                customerAddress.length() < 5 ||
                customerAddress.length() > 250
        )) {
            message("Проверьте адрес", "Укажите полный адрес до 250 символов.");
            return;
        }

        if (customerComment.length() > 300) {
            message("Комментарий", "Допускается не более 300 символов.");
            return;
        }

        if (!consent) {
            message("Согласие", "Подтвердите передачу данных для заказа.");
            return;
        }

        try {
            JSONArray items = new JSONArray();

            for (Map.Entry<String, Integer> entry : cart.entrySet()) {
                if (findProduct(entry.getKey()) == null) {
                    message(
                            "Товар недоступен",
                            "Очистите корзину и выберите доступные товары."
                    );
                    return;
                }

                JSONObject item = new JSONObject();
                item.put("productId", entry.getKey());
                item.put("quantity", entry.getValue());
                items.put(item);
            }

            JSONObject order = new JSONObject();
            order.put("requestId", UUID.randomUUID().toString());
            order.put("name", customerName);
            order.put("phone", phone);
            order.put("fulfillment", delivery ? "delivery" : "pickup");
            order.put("address", delivery ? customerAddress : "");
            order.put("comment", customerComment);
            order.put("items", items);

            confirmNewOrder(order);

        } catch (Exception error) {
            message("Ошибка", "Не удалось подготовить заказ.");
        }
    }

    private void confirmNewOrder(JSONObject order) {
        new AlertDialog.Builder(this)
                .setTitle("Подтвердите заказ")
                .setMessage(
                        "Товары: " + total() + " ₽\n" +
                        (delivery ? "Доставка" : "Самовывоз") +
                        "\nОплата при получении.\n\n" +
                        "Итоговую стоимость товаров проверит сервер. " +
                        "Стоимость доставки при необходимости " +
                        "согласует магазин."
                )
                .setNegativeButton("Назад", null)
                .setPositiveButton("Отправить", (dialog, which) -> {
                    if (sending || pendingOrder != null) return;

                    String payload = order.toString();

                    boolean saved = prefs.edit()
                            .putString("pending_order", payload)
                            .commit();

                    if (!saved) {
                        message(
                                "Ошибка",
                                "Не удалось сохранить заказ на устройстве. " +
                                "Отправка не выполнена."
                        );
                        return;
                    }

                    pendingOrder = payload;
                    sendPending();
                })
                .show();
    }

    private void confirmPending() {
        if (sending || pendingOrder == null) return;

        try {
            JSONObject order = new JSONObject(pendingOrder);

            new AlertDialog.Builder(this)
                    .setTitle("Проверить прежний заказ?")
                    .setMessage(
                            "Будет проверен ранее сохранённый заказ:\n" +
                            order.optString("name") + "\n" +
                            order.optString("phone") + "\n" +
                            (order.optString("fulfillment").equals("pickup")
                                    ? "Самовывоз"
                                    : order.optString("address")) +
                            "\n\nНовый заказ не создаётся: " +
                            "используется прежний идентификатор."
                    )
                    .setNegativeButton("Назад", null)
                    .setPositiveButton(
                            "Проверить", (dialog, which) -> sendPending()
                    )
                    .show();

        } catch (Exception error) {
            message(
                    "Ошибка сохранённого заказа",
                    "Не удалось прочитать заказ. Свяжитесь с магазином " +
                    "перед повторным оформлением."
            );
        }
    }

    private void sendPending() {
        if (sending || pendingOrder == null) return;

        final JSONObject order;

        try {
            order = new JSONObject(pendingOrder);
        } catch (Exception error) {
            message("Ошибка", "Не удалось прочитать сохранённый заказ.");
            return;
        }

        saveProfile();
        sending = true;
        render();

        api.sendOrder(order, new ShopApi.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;

                String id = data.optString("id", "");

                if (id.isEmpty() || !data.has("totalRub")) {
                    sending = false;
                    saveProfile();
                    render();
                    message(
                            "Нет подтверждения",
                            "Ответ сервера неполный. Заказ сохранён " +
                            "на устройстве для повторной проверки."
                    );
                    return;
                }

                                long orderNumber = data.optLong("orderNumber", 0);

                String receipt =
                        (orderNumber > 0
                                ? "Заказ № " + orderNumber
                                : "Заказ сохранён. Номер уточняется.") +
                        "\nТовары: "

                boolean saved = prefs.edit()
                        .remove("pending_order")
                        .putString("last_order_v2", receipt)
                        .putString("cart_v2", "{}")
                        .commit();

                sending = false;

                if (!saved) {
                    saveProfile();
                    render();
                    message(
                            "Заказ сохранён магазином",
                            receipt + "\n\nНе удалось обновить данные " +
                            "на телефоне. Сохраните номер заказа. " +
                            "Не оформляйте его заново."
                    );
                    return;
                }

                saveProfile();
                pendingOrder = null;
                cart.clear();
                consent = false;
                customerComment = "";
                currentTab = 4;
                render();

                message(
                        "Заказ сохранён магазином",
                        receipt + "\n\nОжидайте подтверждения продавца. " +
                        "Оплата при получении."
                );
            }

            @Override
            public void onError(ShopApi.ApiError error) {
                if (destroyed) return;

                saveProfile();
                sending = false;

                if (error.shouldClearPendingOrder()) {
                    boolean removed = prefs.edit()
                            .remove("pending_order")
                            .commit();

                    if (removed) pendingOrder = null;
                }

                render();

                message(
                        "Результат отправки",
                        error.getMessage() +
                        (pendingOrder != null
                                ? "\n\nЗаказ сохранён на устройстве. " +
                                  "Для проверки используйте кнопку " +
                                  "«Проверить прежний заказ», " +
                                  "не оформляйте новый."
                                : "\n\nИсправьте данные перед повторной отправкой.")
                );
            }
        });
    }

    @Override
    protected void onPause() {
        saveProfile();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (api != null) api.close();
        super.onDestroy();
    }
}
// КОНЕЦ ФАЙЛА
