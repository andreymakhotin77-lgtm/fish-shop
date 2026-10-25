package ru.makeevka.fishshop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.*;

public class StoreActivity extends Activity {

    private final int BLACK = Color.rgb(15, 15, 16);
    private final int CARD = Color.rgb(29, 29, 31);
    private final int GOLD = Color.rgb(212, 175, 55);
    private final int WHITE = Color.rgb(248, 244, 233);
    private final int MUTED = Color.rgb(173, 170, 160);
    private final int LINE = Color.rgb(59, 54, 38);

    private static final String PICKUP =
            "г. Макеевка, ул. Плеханова, 1в\n" +
            "Магазин «Лавка Вкуса»";

    private static final String DELIVERY =
            "Бесплатная доставка от 2 000 ₽ по Макеевке, " +
            "Донецку и Харцызску. Другие города и заказы " +
            "до 2 000 ₽ — по согласованию.";

    private static final String HISTORY_KEY = "orders_history_v1";

    private static final String ORDER_ID_PATTERN =
            "LV-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
            "[0-9a-f]{4}-[0-9a-f]{12}";

    private final String[] tabs = {
            "Главная", "Каталог", "Избранное", "Корзина", "Профиль"
    };

    private final String[] tabIcons = {
            "⌂", "☰", "♡", "▣", "◉"
    };

    private final List<Product> products = new ArrayList<>();

    /*
     * Количество хранится в половинах единицы:
     * 1 = 0,5 кг; 2 = 1 кг или 1 шт/уп.
     */
    private final Map<String, Integer> cartHalves =
            new HashMap<>();

    private Set<String> favorites = new HashSet<>();

    private JSONArray orderHistory = new JSONArray();

    private final Set<String> refreshingOrders =
            new HashSet<>();

    private final NumberFormat moneyFormat =
            NumberFormat.getNumberInstance(new Locale("ru", "RU"));

    private final NumberFormat quantityFormat =
            NumberFormat.getNumberInstance(new Locale("ru", "RU"));

    private ShopApi api;
    private SharedPreferences prefs;

    private LinearLayout content;
    private LinearLayout navigation;
    private ScrollView scroll;

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
    private boolean showingHistory = false;
    private boolean historyReadable = true;

    private static class Product {
        String id;
        String name;
        String category;
        String description;
        String unit;
        String imageUrl;
        int price;
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        moneyFormat.setMinimumFractionDigits(2);
        moneyFormat.setMaximumFractionDigits(2);

        quantityFormat.setMinimumFractionDigits(0);
        quantityFormat.setMaximumFractionDigits(1);

        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);

        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        prefs = getPreferences(MODE_PRIVATE);
        api = new ShopApi();

        restoreData();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(24));

        scroll.addView(content);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(-1, 0, 1)
        );

        navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setBackgroundColor(CARD);
        navigation.setPadding(dp(3), dp(6), dp(3), dp(6));

        root.addView(
                navigation,
                new LinearLayout.LayoutParams(-1, -2)
        );

        setContentView(root);

        render(false);
        loadCatalog();
    }

    private void restoreData() {
        customerName = prefs.getString("customer_name", "");
        customerPhone = prefs.getString("customer_phone", "");
        customerAddress = prefs.getString("customer_address", "");
        delivery = prefs.getBoolean("delivery", true);
        pendingOrder = prefs.getString("pending_order", null);

        favorites = new HashSet<>(
                prefs.getStringSet(
                        "favorites_v2",
                        new HashSet<String>()
                )
        );

        try {
            JSONObject saved = new JSONObject(
                    prefs.getString("cart_v2", "{}")
            );

            Iterator<String> keys = saved.keys();

            while (keys.hasNext()) {
                String id = keys.next();
                double value = saved.optDouble(id, 0);
                double halves = value * 2;

                if (
                        Double.isFinite(value)
                                && value >= 0.5
                                && value <= 50
                                && halves == Math.rint(halves)
                ) {
                    cartHalves.put(id, (int) halves);
                }
            }
        } catch (Exception ignored) {
        }

        try {
            JSONArray saved = new JSONArray(
                    prefs.getString(HISTORY_KEY, "[]")
            );

            Set<String> ids = new HashSet<>();

            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.getJSONObject(i);
                String id = item.getString("id");

                if (
                        !id.matches(ORDER_ID_PATTERN)
                                || !ids.add(id)
                ) {
                    throw new Exception("Некорректная история");
                }
            }

            orderHistory = saved;

        } catch (Exception error) {
            /*
             * Повреждённую историю не перезаписываем.
             * Исходная строка остаётся в preferences.
             */
            historyReadable = false;
            orderHistory = new JSONArray();
        }
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private String money(long kopecks) {
        return moneyFormat.format(
                BigDecimal.valueOf(kopecks, 2)
        ) + " ₽";
    }

    private long responseKopecks(JSONObject data) throws Exception {
        BigDecimal rubles = new BigDecimal(
                data.get("totalRub").toString()
        );

        long kopecks = rubles.movePointRight(2).longValueExact();

        if (kopecks <= 0 || kopecks > 1000000000L) {
            throw new Exception("Некорректная сумма");
        }

        return kopecks;
    }

    private String orderMoney(JSONObject order) {
        try {
            return money(responseKopecks(order));
        } catch (Exception error) {
            return "Сумма уточняется";
        }
    }

    private boolean isKilogram(Product product) {
        return "кг".equalsIgnoreCase(product.unit.trim());
    }

    private String quantityText(Product product, int halves) {
        return quantityFormat.format(halves / 2.0)
                + " " + product.unit;
    }

    private String stepText(Product product) {
        return isKilogram(product)
                ? "Шаг выбора — 500 г"
                : "Шаг выбора — 1 " + product.unit;
    }

    private int halves(String id) {
        Integer value = cartHalves.get(id);
        return value == null ? 0 : value;
    }

    private long lineKopecks(Product product) {
        return (long) product.price * 50L * halves(product.id);
    }

    private long totalKopecks() {
        long sum = 0;

        for (Product product : products) {
            sum += lineKopecks(product);
        }

        return sum;
    }

    private GradientDrawable background(int color, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(18));

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
        view.setPadding(0, dp(4), 0, dp(4));
        view.setLineSpacing(dp(2), 1);
        return view;
    }

    private TextView bold(String value, int size, int color) {
        TextView view = text(value, size, color);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private Button button(String title, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setAllCaps(false);
        view.setTextSize(14);
        view.setTextColor(BLACK);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setBackgroundTintList(ColorStateList.valueOf(GOLD));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private Button secondary(String title, Runnable action) {
        Button view = button(title, action);
        view.setTextColor(GOLD);
        view.setBackgroundTintList(ColorStateList.valueOf(LINE));
        return view;
    }

    private LinearLayout box(boolean border) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setBackground(background(CARD, border));
        return view;
    }

    private LinearLayout panel() {
        LinearLayout view = box(false);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);

        params.setMargins(0, dp(8), 0, dp(8));
        content.addView(view, params);

        return view;
    }

    private void section(String value) {
        TextView title = bold(value, 22, WHITE);
        title.setPadding(0, dp(18), 0, dp(8));
        content.addView(title);
    }

    private EditText input(
            LinearLayout parent,
            String hint,
            String value,
            int type,
            int maxLength
    ) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setInputType(type);
        field.setTextColor(WHITE);
        field.setHintTextColor(MUTED);
        field.setTextSize(16);

        field.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(maxLength)
        });

        field.setBackgroundTintList(ColorStateList.valueOf(GOLD));
        field.setPadding(dp(4), dp(12), dp(4), dp(12));

        parent.addView(
                field,
                new LinearLayout.LayoutParams(-1, -2)
        );

        return field;
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(
                this,
                android.R.style.Theme_Material_Dialog_Alert
        );
    }

    private void decorateDialog(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    background(CARD, true)
            );
        }

        TextView body = dialog.findViewById(android.R.id.message);

        if (body != null) {
            body.setTextColor(WHITE);
        }

        int[] buttons = {
                AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL
        };

        for (int which : buttons) {
            Button view = dialog.getButton(which);

            if (view != null) {
                view.setTextColor(GOLD);
            }
        }
    }

    private void message(String title, String value) {
        if (destroyed || isFinishing()) return;

        AlertDialog dialog = dialogBuilder()
                .setTitle(title)
                .setMessage(value)
                .setPositiveButton("Понятно", null)
                .create();

        dialog.show();
        decorateDialog(dialog);
    }

    private void saveProfile() {
        if (prefs == null) return;

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
        JSONObject saved = new JSONObject();

        try {
            for (Map.Entry<String, Integer> entry : cartHalves.entrySet()) {
                saved.put(entry.getKey(), entry.getValue() / 2.0);
            }
        } catch (Exception ignored) {
        }

        prefs.edit()
                .putString("cart_v2", saved.toString())
                .apply();
    }

    private void openTab(int tab) {
        saveProfile();
        currentTab = tab;
        showingHistory = false;
        render(false);
    }

    private void openHistory() {
        saveProfile();
        currentTab = 4;
        showingHistory = true;
        render(false);
    }

    private void redraw() {
        saveProfile();
        render(true);
    }

    private void render(boolean keepPosition) {
        if (destroyed) return;

        final int oldY = keepPosition ? scroll.getScrollY() : 0;

        nameInput = null;
        phoneInput = null;
        addressInput = null;
        commentInput = null;

        content.removeAllViews();
        navigation.removeAllViews();

        drawHeader();
        drawNavigation();

        if (loading) {
            content.addView(text(
                    "Обновляем каталог… Первый запуск после " +
                    "простоя сервера может занять около минуты.",
                    13, MUTED
            ));
        } else if (!freshCatalog) {
            LinearLayout notice = panel();

            notice.addView(text(
                    "Каталог не обновлён. Для нового заказа " +
                    "нужны интернет и актуальные цены.",
                    14, MUTED
            ));

            notice.addView(button(
                    "Загрузить каталог", this::loadCatalog
            ));
        }

        switch (currentTab) {
            case 0:
                showHome();
                break;
            case 1:
                showCatalog(false);
                break;
            case 2:
                showCatalog(true);
                break;
            case 3:
                showCart();
                break;
            default:
                if (showingHistory) {
                    showHistory();
                } else {
                    showProfile();
                }
                break;
        }

        scroll.post(() -> {
            if (!destroyed) {
                scroll.scrollTo(0, oldY);
            }
        });
    }

    private void drawHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = bold("ЛВ", 24, GOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(background(CARD, true));
        logo.setContentDescription("Логотип Лавка Вкуса");

        header.addView(
                logo,
                new LinearLayout.LayoutParams(dp(58), dp(58))
        );

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(bold("Лавка Вкуса", 25, WHITE));
        titles.addView(text(
                "Рыба · Морепродукты · Раки", 12, MUTED
        ));

        header.addView(
                titles,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        content.addView(header);
        content.addView(text(
                "Макеевка  •  Доставка и самовывоз", 13, GOLD
        ));
    }

    private void drawNavigation() {
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            String label = tabs[i];

            if (i == 3 && !cartHalves.isEmpty()) {
                label += " (" + cartHalves.size() + ")";
            }

            TextView item = text(
                    tabIcons[i] + "\n" + label,
                    10,
                    i == currentTab ? BLACK : MUTED
            );

            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(1), dp(5), dp(1), dp(5));

            if (i == currentTab) {
                item.setBackground(background(GOLD, false));
                item.setTypeface(null, Typeface.BOLD);
            }

            item.setContentDescription(label);
            item.setOnClickListener(v -> openTab(tab));

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, dp(58), 1);

            params.setMargins(dp(2), 0, dp(2), 0);
            navigation.addView(item, params);
        }
    }

    private void showHome() {
        LinearLayout banner = panel();

        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Color.rgb(68, 54, 23),
                        Color.rgb(30, 29, 25),
                        CARD
                }
        );

        gradient.setCornerRadius(dp(18));
        gradient.setStroke(dp(1), LINE);
        banner.setBackground(gradient);

        banner.addView(text(
                "ВКУС, КОТОРЫЙ ОБЪЕДИНЯЕТ", 11, GOLD
        ));

        banner.addView(bold(
                "К вашему столу —\nс заботой и вкусом", 28, WHITE
        ));

        banner.addView(text(
                "Рыба, морепродукты и живые раки.\n" +
                "Выбирайте в приложении — " +
                "оплачивайте при получении.",
                15, WHITE
        ));

        banner.addView(button(
                "Выбрать товары", () -> openTab(1)
        ));

        LinearLayout shipping = panel();
        shipping.addView(bold(
                "Доставка от 2 000 ₽ — бесплатно", 18, GOLD
        ));
        shipping.addView(text(DELIVERY, 14, MUTED));

        if (orderHistory.length() > 0) {
            LinearLayout orders = panel();
            orders.addView(bold("Ваши заказы", 20, GOLD));
            orders.addView(text(
                    "Состав и текущий статус — в истории заказов.",
                    14, MUTED
            ));
            orders.addView(button("Мои заказы", this::openHistory));
        }

        section("Категории");
        categoryChips();

        section("Наш ассортимент");

        List<Product> preview = new ArrayList<>();

        for (int i = 0; i < Math.min(6, products.size()); i++) {
            preview.add(products.get(i));
        }

        productGrid(preview);

        if (products.isEmpty() && freshCatalog) {
            content.addView(text(
                    "Скоро здесь появятся товары.", 16, MUTED
            ));
        }

        content.addView(secondary(
                "Весь каталог", () -> openTab(1)
        ));

        LinearLayout pickup = panel();
        pickup.addView(bold("Ждём вас в магазине", 20, GOLD));
        pickup.addView(text(PICKUP, 15, WHITE));

        content.addView(secondary(
                "Обновить ассортимент", this::loadCatalog
        ));
    }

    private void categoryChips() {
        HorizontalScrollView horizontal =
                new HorizontalScrollView(this);

        horizontal.setHorizontalScrollBarEnabled(false);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        addCategoryChip(row, "Все товары", "");

        Set<String> categories = new LinkedHashSet<>();

        for (Product product : products) {
            categories.add(product.category);
        }

        for (String value : categories) {
            addCategoryChip(row, value, value);
        }

        horizontal.addView(row);
        content.addView(horizontal);
    }

    private void addCategoryChip(
            LinearLayout row, String title, String value
    ) {
        Button chip = secondary(title, () -> {
            category = value;
            search = "";
            openTab(1);
        });

        if (category.equals(value)) {
            chip.setTextColor(BLACK);
            chip.setBackgroundTintList(ColorStateList.valueOf(GOLD));
        }

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-2, dp(50));

        params.setMargins(0, 0, dp(8), 0);
        row.addView(chip, params);
    }

    private void showCatalog(boolean onlyFavorites) {
        section(onlyFavorites ? "Избранное" : "Каталог");

        if (!onlyFavorites) {
            EditText query = input(
                    content, "Поиск товара", search,
                    InputType.TYPE_CLASS_TEXT, 100
            );

            content.addView(button("Найти", () -> {
                search = query.getText().toString().trim();
                redraw();
            }));

            categoryChips();

            if (!search.isEmpty() || !category.isEmpty()) {
                content.addView(secondary(
                        "Сбросить поиск и категорию",
                        () -> {
                            search = "";
                            category = "";
                            redraw();
                        }
                ));
            }
        }

        List<Product> found = new ArrayList<>();
        String query = search.toLowerCase(Locale.ROOT);

        for (Product product : products) {
            if (onlyFavorites) {
                if (!favorites.contains(product.id)) continue;
            } else {
                if (
                        !category.isEmpty()
                                && !category.equals(product.category)
                ) {
                    continue;
                }

                String searchable =
                        product.name + " " +
                        product.category + " " +
                        product.description;

                if (!searchable.toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
            }

            found.add(product);
        }

        content.addView(text(
                "Товаров: " + found.size(), 13, MUTED
        ));

        productGrid(found);

        if (found.isEmpty()) {
            LinearLayout empty = panel();
            empty.addView(text(
                    onlyFavorites
                            ? "Здесь будут ваши любимые товары. " +
                              "Нажимайте ♡ на карточках."
                            : "Ничего не найдено. Попробуйте " +
                              "другой запрос или категорию.",
                    16, MUTED
            ));
        }

        if (!onlyFavorites) {
            content.addView(secondary(
                    "Обновить каталог", this::loadCatalog
            ));
        }
    }

    private void productGrid(List<Product> list) {
        int columns =
                getResources().getConfiguration().screenWidthDp < 340
                        ? 1 : 2;

        for (int start = 0; start < list.size(); start += columns) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);

            for (int column = 0; column < columns; column++) {
                int index = start + column;

                LinearLayout.LayoutParams params =
                        new LinearLayout.LayoutParams(0, -1, 1);

                params.setMargins(
                        column == 0 ? 0 : dp(5),
                        dp(6),
                        column == columns - 1 ? 0 : dp(5),
                        dp(6)
                );

                if (index < list.size()) {
                    row.addView(compactCard(list.get(index)), params);
                } else {
                    row.addView(new View(this), params);
                }
            }

            content.addView(
                    row,
                    new LinearLayout.LayoutParams(-1, -2)
            );
        }
    }

    private LinearLayout compactCard(Product product) {
        LinearLayout card = box(false);
        card.setPadding(dp(9), dp(9), dp(9), dp(9));

        ProductPhoto picture = new ProductPhoto(
                this, product.name, product.category, product.imageUrl
        );

        picture.setOnClickListener(v -> showProduct(product));
        picture.setContentDescription(
                "Открыть товар: " + product.name
        );

        card.addView(
                picture,
                new LinearLayout.LayoutParams(-1, dp(132))
        );

        card.addView(text(product.category, 11, MUTED));

        TextView name = bold(product.name, 15, WHITE);
        name.setMinLines(2);
        name.setOnClickListener(v -> showProduct(product));
        card.addView(name);

        card.addView(bold(
                money((long) product.price * 100)
                        + " / " + product.unit,
                17, GOLD
        ));

        card.addView(text(
                isKilogram(product)
                        ? "500 г — " + money((long) product.price * 50)
                        : stepText(product),
                12, MUTED
        ));

        Button favorite = secondary(
                favorites.contains(product.id)
                        ? "♥ Сохранено" : "♡ Сохранить",
                () -> toggleFavorite(product)
        );

        favorite.setTextSize(12);
        card.addView(favorite);
        addQuantityControls(card, product);

        return card;
    }

    private void showProduct(Product product) {
        if (destroyed) return;

        ScrollView body = new ScrollView(this);
        LinearLayout detail = box(false);

        detail.addView(
                new ProductPhoto(
                        this, product.name,
                        product.category, product.imageUrl
                ),
                new LinearLayout.LayoutParams(-1, dp(230))
        );

        detail.addView(text(product.category, 13, MUTED));
        detail.addView(bold(product.name, 23, WHITE));

        detail.addView(bold(
                money((long) product.price * 100)
                        + " / " + product.unit,
                24, GOLD
        ));

        if (isKilogram(product)) {
            detail.addView(text(
                    "500 г — " + money((long) product.price * 50),
                    17, GOLD
            ));
        }

        detail.addView(text(
                product.description.isEmpty()
                        ? "Подробности о товаре можно уточнить " +
                          "при подтверждении заказа."
                        : product.description,
                16, WHITE
        ));

        detail.addView(text(
                stepText(product) + ".\nОплата при получении.",
                14, MUTED
        ));

        body.addView(detail);

        AlertDialog dialog = dialogBuilder()
                .setView(body)
                .setNegativeButton("Закрыть", null)
                .setPositiveButton(
                        isKilogram(product)
                                ? "Добавить 500 г"
                                : "Добавить 1 " + product.unit,
                        null
                )
                .create();

        dialog.show();
        decorateDialog(dialog);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (changeQuantity(product, 1)) {
                        dialog.dismiss();
                        Toast.makeText(
                                this, "Товар добавлен в корзину",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                });
    }

    private void toggleFavorite(Product product) {
        if (!favorites.add(product.id)) {
            favorites.remove(product.id);
        }

        prefs.edit()
                .putStringSet("favorites_v2", new HashSet<>(favorites))
                .apply();

        redraw();
    }

    private void addQuantityControls(
            LinearLayout parent, Product product
    ) {
        int value = halves(product.id);

        if (value == 0) {
            parent.addView(button(
                    isKilogram(product) ? "+ 500 г" : "В корзину",
                    () -> changeQuantity(product, 1)
            ));
            return;
        }

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        Button minus = secondary(
                "−", () -> changeQuantity(product, -1)
        );

        Button plus = button(
                "+", () -> changeQuantity(product, 1)
        );

        minus.setContentDescription(
                "Уменьшить количество: " + product.name
        );
        plus.setContentDescription(
                "Увеличить количество: " + product.name
        );

        for (Button view : new Button[]{minus, plus}) {
            view.setMinWidth(0);
            view.setMinimumWidth(0);
            view.setPadding(0, 0, 0, 0);
        }

        controls.addView(
                minus,
                new LinearLayout.LayoutParams(dp(44), dp(48))
        );

        TextView count = bold(
                quantityText(product, value), 13, WHITE
        );
        count.setGravity(Gravity.CENTER);

        controls.addView(
                count,
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        controls.addView(
                plus,
                new LinearLayout.LayoutParams(dp(44), dp(48))
        );

        parent.addView(controls);
    }

    private Product findProduct(String id) {
        for (Product product : products) {
            if (product.id.equals(id)) return product;
        }
        return null;
    }

    private boolean changeQuantity(Product product, int direction) {
        if (pendingOrder != null || sending) {
            message(
                    "Есть неподтверждённый заказ",
                    "Сначала проверьте его отправку " +
                    "в разделе «Корзина»."
            );
            return false;
        }

        int current = halves(product.id);
        int step = isKilogram(product) ? 1 : 2;
        int next;

        if (step == 2 && current % 2 != 0) {
            next = direction > 0 ? current + 1 : current - 1;
        } else {
            next = current + direction * step;
        }

        if (next > 100) {
            message(
                    "Ограничение количества",
                    "В одном заказе можно выбрать не более " +
                    "50 " + product.unit + " одного товара."
            );
            return false;
        }

        next = Math.max(0, next);

        if (next == 0) {
            cartHalves.remove(product.id);
        } else {
            cartHalves.put(product.id, next);
        }

        saveCart();
        redraw();
        return true;
    }

    private void showCart() {
        section("Корзина");

        if (pendingOrder != null) {
            LinearLayout pending = panel();
            pending.addView(bold(
                    "Проверим прежний заказ", 20, GOLD
            ));

            pending.addView(text(
                    "Результат предыдущей отправки не подтверждён. " +
                    "Проверка использует прежний идентификатор " +
                    "и не создаёт второй заказ с ним.",
                    14, WHITE
            ));

            Button retry = button(
                    sending ? "Проверяем…" : "Проверить прежний заказ",
                    this::confirmPending
            );

            retry.setEnabled(!sending);
            pending.addView(retry);
        }

        if (cartHalves.isEmpty()) {
            LinearLayout empty = panel();
            empty.addView(bold("Пока здесь пусто", 22, WHITE));
            empty.addView(text(
                    "Самое время выбрать что-нибудь вкусное.",
                    15, MUTED
            ));
            empty.addView(button(
                    "Открыть каталог", () -> openTab(1)
            ));
            return;
        }

        for (String id : new ArrayList<>(cartHalves.keySet())) {
            Product product = findProduct(id);

            if (product == null) {
                LinearLayout unavailable = panel();
                unavailable.addView(text(
                        "Товар временно недоступен в каталоге.",
                        15, GOLD
                ));

                if (pendingOrder == null && !sending) {
                    unavailable.addView(secondary(
                            "Убрать недоступный товар",
                            () -> {
                                cartHalves.remove(id);
                                saveCart();
                                redraw();
                            }
                    ));
                }
                continue;
            }

            LinearLayout item = panel();

            item.addView(
                    new ProductPhoto(
                            this, product.name,
                            product.category, product.imageUrl
                    ),
                    new LinearLayout.LayoutParams(-1, dp(150))
            );

            item.addView(bold(product.name, 20, WHITE));
            item.addView(text(
                    money((long) product.price * 100)
                            + " / " + product.unit,
                    15, MUTED
            ));
            item.addView(text(stepText(product), 13, MUTED));

            if (!isKilogram(product) && halves(product.id) % 2 != 0) {
                item.addView(text(
                        "Единица продажи изменилась. " +
                        "Исправьте количество кнопкой «+» или «−».",
                        14, GOLD
                ));
            }

            addQuantityControls(item, product);

            item.addView(bold(
                    "Сумма: " + money(lineKopecks(product)),
                    18, GOLD
            ));
        }

        LinearLayout summary = panel();
        summary.addView(bold(
                "Товары: " + money(totalKopecks()), 25, GOLD
        ));
        summary.addView(text(DELIVERY, 14, MUTED));
        summary.addView(text("Самовывоз: " + PICKUP, 14, MUTED));
        summary.addView(bold("Оплата при получении", 17, WHITE));

        if (pendingOrder != null) return;

        summary.addView(bold("Данные для заказа", 20, WHITE));
        customerFields(summary, true);

        Button send = button(
                sending ? "Отправляем…" : "Оформить заказ",
                this::prepareOrder
        );

        send.setEnabled(freshCatalog && !loading && !sending);
        summary.addView(send);
        summary.addView(secondary(
                "Очистить корзину", this::confirmClearCart
        ));
    }

    private void confirmClearCart() {
        if (sending || pendingOrder != null) return;

        AlertDialog dialog = dialogBuilder()
                .setTitle("Очистить корзину?")
                .setMessage("Все выбранные товары будут убраны.")
                .setNegativeButton("Оставить", null)
                .setPositiveButton("Очистить", (d, which) -> {
                    if (sending || pendingOrder != null) return;

                    saveProfile();
                    cartHalves.clear();
                    saveCart();
                    render(false);
                })
                .create();

        dialog.show();
        decorateDialog(dialog);
    }

    private void customerFields(LinearLayout parent, boolean order) {
        nameInput = input(
                parent, "Ваше имя", customerName,
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_FLAG_CAP_WORDS,
                80
        );

        phoneInput = input(
                parent, "Телефон: +7…", customerPhone,
                InputType.TYPE_CLASS_PHONE, 32
        );

        if (!order) {
            addressInput = input(
                    parent, "Адрес доставки", customerAddress,
                    InputType.TYPE_CLASS_TEXT |
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    250
            );
            return;
        }

        Switch mode = new Switch(this);
        mode.setText("Доставка");
        mode.setTextColor(WHITE);
        mode.setTextSize(16);
        mode.setPadding(0, dp(14), 0, dp(14));
        mode.setChecked(delivery);
        mode.setThumbTintList(ColorStateList.valueOf(GOLD));
        parent.addView(mode);

        TextView modeHint = text(
                delivery
                        ? "Привезём по указанному адресу."
                        : "Самовывоз:\n" + PICKUP,
                13, MUTED
        );

        parent.addView(modeHint);

        addressInput = input(
                parent, "Город, улица, дом, квартира", customerAddress,
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                250
        );

        final EditText addressField = addressInput;
        addressField.setVisibility(delivery ? View.VISIBLE : View.GONE);

        mode.setOnCheckedChangeListener((view, checked) -> {
            delivery = checked;
            addressField.setVisibility(
                    checked ? View.VISIBLE : View.GONE
            );
            modeHint.setText(
                    checked
                            ? "Привезём по указанному адресу."
                            : "Самовывоз:\n" + PICKUP
            );
            saveProfile();
        });

        commentInput = input(
                parent, "Комментарий к заказу", customerComment,
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                300
        );

        CheckBox agreement = new CheckBox(this);
        agreement.setText(
                "Согласен на передачу имени, телефона и адреса " +
                "магазину для обработки этого заказа."
        );
        agreement.setTextColor(MUTED);
        agreement.setTextSize(13);
        agreement.setPadding(0, dp(10), 0, dp(10));
        agreement.setButtonTintList(ColorStateList.valueOf(GOLD));
        agreement.setChecked(consent);
        agreement.setOnCheckedChangeListener(
                (view, checked) -> consent = checked
        );

        parent.addView(agreement);
    }

    private void showProfile() {
        section("Мой профиль");

        LinearLayout profile = panel();
        profile.addView(bold("Данные покупателя", 21, WHITE));
        profile.addView(text(
                "Сохраняются на этом телефоне. " +
                "Регистрация на сервере не используется.",
                13, MUTED
        ));

        customerFields(profile, false);

        profile.addView(button("Сохранить данные", () -> {
            saveProfile();
            message(
                    "Данные сохранены",
                    "При следующем заказе поля " +
                    "будут заполнены автоматически."
            );
        }));

        LinearLayout orders = panel();
        orders.addView(bold("Мои заказы", 21, GOLD));

        orders.addView(text(
                historyReadable
                        ? "Заказов на этом телефоне: " +
                          orderHistory.length()
                        : "Не удалось прочитать локальную историю.",
                15, WHITE
        ));

        orders.addView(button(
                "Открыть историю заказов", this::openHistory
        ));

        orders.addView(text(
                "История формируется с этой версии приложения. " +
                "После удаления приложения она не восстановится. " +
                "Для актуального статуса нажмите «Обновить статус».",
                13, MUTED
        ));

        String last = prefs.getString("last_order_v2", "");

        if (!last.isEmpty()) {
            orders.addView(bold(
                    "Последний подтверждённый заказ", 17, WHITE
            ));
            orders.addView(text(last, 15, WHITE));
        }

        if (pendingOrder != null) {
            orders.addView(button(
                    "Проверить неподтверждённый заказ",
                    () -> openTab(3)
            ));
        }

        LinearLayout shop = panel();
        shop.addView(bold("Наш магазин", 20, GOLD));
        shop.addView(text(PICKUP, 16, WHITE));
        shop.addView(text(DELIVERY, 14, MUTED));
        shop.addView(text(
                "Оплата при получении. Подтверждение " +
                "и детали заказа согласует продавец.",
                14, MUTED
        ));
    }

    private String statusName(String status) {
        switch (status) {
            case "new": return "Новый";
            case "confirmed": return "Подтверждён";
            case "preparing": return "Собирается";
            case "delivering": return "В доставке";
            case "ready": return "Готов к самовывозу";
            case "completed": return "Выполнен";
            case "cancelled": return "Отменён";
            default: return "Статус уточняется";
        }
    }

    private boolean knownStatus(String value) {
        return Arrays.asList(
                "new", "confirmed", "preparing", "delivering",
                "ready", "completed", "cancelled"
        ).contains(value);
    }

    private String orderTitle(JSONObject order) {
        long number = order.optLong("orderNumber", 0);
        return number > 0
                ? "Заказ № " + number
                : "Заказ сохранён";
    }

    private String displayDate(String value) {
        try {
            Date date = Date.from(Instant.parse(value));
            return DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT,
                    new Locale("ru", "RU")
            ).format(date);
        } catch (Exception error) {
            return "Дата уточняется";
        }
    }

    private String checkedDate(long millis) {
        return DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT,
                new Locale("ru", "RU")
        ).format(new Date(millis));
    }

    private JSONObject historyOrder(String id) {
        for (int i = 0; i < orderHistory.length(); i++) {
            JSONObject item = orderHistory.optJSONObject(i);

            if (item != null && id.equals(item.optString("id"))) {
                return item;
            }
        }
        return null;
    }

    /*
     * Новая запись попадает в начало.
     * При обновлении статуса порядок не меняется.
     */
    private JSONArray historyWith(
            JSONObject replacement, boolean putFirst
    ) throws Exception {
        if (!historyReadable) {
            throw new Exception("История не прочитана");
        }

        String id = replacement.getString("id");
        JSONArray result = new JSONArray();
        boolean replaced = false;

        if (putFirst) {
            result.put(replacement);
            replaced = true;
        }

        for (int i = 0; i < orderHistory.length(); i++) {
            JSONObject item = orderHistory.getJSONObject(i);

            if (id.equals(item.getString("id"))) {
                if (!putFirst) {
                    result.put(replacement);
                    replaced = true;
                }
            } else {
                result.put(item);
            }
        }

        if (!replaced) {
            result.put(replacement);
        }

        return result;
    }

    private boolean canTrack(JSONObject order) {
        return order.optBoolean("trackingAvailable", false)
                && order.optString("id", "").matches(ORDER_ID_PATTERN)
                && order.optString("orderAccessToken", "")
                        .matches("[0-9a-f]{64}");
    }

    private void showHistory() {
        content.addView(secondary(
                "← Назад в профиль", () -> openTab(4)
        ));

        section("Мои заказы");

        LinearLayout notice = panel();
        notice.addView(text(
                "Здесь хранятся заказы с этого телефона. " +
                "Статус обновляется кнопкой у заказа. " +
                "Просмотр статуса не создаёт новый заказ.",
                14, MUTED
        ));

        if (!historyReadable) {
            notice.addView(text(
                    "Не удалось прочитать сохранённую историю. " +
                    "Она не была удалена или перезаписана. " +
                    "Не удаляйте приложение. Свяжитесь с магазином " +
                    "для проверки текущего заказа.",
                    15, GOLD
            ));
            return;
        }

        if (pendingOrder != null) {
            notice.addView(button(
                    "Проверить неподтверждённую отправку",
                    () -> openTab(3)
            ));
        }

        if (orderHistory.length() == 0) {
            LinearLayout empty = panel();
            empty.addView(bold("Пока нет заказов", 22, WHITE));
            empty.addView(text(
                    "Первый заказ после этого обновления " +
                    "появится здесь после подтверждения сервера. " +
                    "Прежние заказы автоматически не переносятся.",
                    15, MUTED
            ));
            empty.addView(button(
                    "Открыть каталог", () -> openTab(1)
            ));
            return;
        }

        for (int i = 0; i < orderHistory.length(); i++) {
            JSONObject order = orderHistory.optJSONObject(i);
            if (order == null) continue;

            final String id = order.optString("id");
            LinearLayout card = panel();

            card.addView(bold(orderTitle(order), 23, GOLD));

            card.addView(text(
                    (order.optBoolean("serverSnapshot", false)
                            ? "Оформлен: "
                            : "Получено подтверждение на телефоне: ")
                            + displayDate(order.optString("createdAt")),
                    13, MUTED
            ));

            card.addView(bold(
                    "Статус: " + statusName(order.optString("status")),
                    19, WHITE
            ));

            long checkedAt = order.optLong("checkedAt", 0);

            if (checkedAt > 0) {
                card.addView(text(
                        "Последнее подтверждение статуса: " +
                        checkedDate(checkedAt),
                        12, MUTED
                ));
            }

            card.addView(text(
                    "pickup".equals(order.optString("fulfillment"))
                            ? "Получение: самовывоз"
                            : "Получение: доставка",
                    14, MUTED
            ));

            if ("pickup".equals(order.optString("fulfillment"))) {
                card.addView(text(PICKUP, 14, MUTED));
            }

            JSONArray items = order.optJSONArray("items");

            if (items != null) {
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item == null) continue;

                    double quantity = item.optDouble("quantity", 0);
                    String line = item.optString("name", "Товар");

                    if (Double.isFinite(quantity) && quantity > 0) {
                        line += " — " + quantityFormat.format(quantity)
                                + " " + item.optString("unit", "");
                    }

                    if (
                            order.optBoolean("serverSnapshot", false)
                                    && item.has("totalRub")
                    ) {
                        try {
                            line += " — " + money(responseKopecks(item));
                        } catch (Exception ignored) {
                        }
                    }

                    card.addView(text(line, 15, WHITE));
                }
            }

            card.addView(bold(
                    "Товары: " + orderMoney(order), 22, GOLD
            ));

            if (!order.optBoolean("serverSnapshot", false)) {
                card.addView(text(
                        "Сумма подтверждена сервером. " +
                        "Для сохранённого магазином состава " +
                        "нажмите «Обновить статус».",
                        12, MUTED
                ));
            }

            card.addView(text(
                    "Оплата при получении. Стоимость платной " +
                    "доставки согласуется отдельно.",
                    13, MUTED
            ));

            if (canTrack(order)) {
                boolean busy = refreshingOrders.contains(id);

                Button refresh = button(
                        busy ? "Обновляем…" : "Обновить статус",
                        () -> refreshOrder(id)
                );

                refresh.setEnabled(!busy);
                card.addView(refresh);

            } else {
                card.addView(text(
                        "Для этого заказа нет ключа просмотра. " +
                        "Уточните статус у магазина. " +
                        "Повторно оформлять заказ не нужно.",
                        13, GOLD
                ));
            }
        }
    }

    private void refreshOrder(String id) {
        if (destroyed || refreshingOrders.contains(id)) return;

        JSONObject local = historyOrder(id);

        if (local == null || !canTrack(local)) {
            message(
                    "Статус заказа",
                    "На телефоне нет ключа просмотра этого заказа."
            );
            return;
        }

        final String accessToken = local.optString("orderAccessToken");

        refreshingOrders.add(id);
        redraw();

        api.loadOrder(id, accessToken, new ShopApi.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;

                refreshingOrders.remove(id);

                try {
                    JSONObject remote = data.getJSONObject("order");

                    if (!id.equals(remote.getString("id"))) {
                        throw new Exception("Другой заказ");
                    }

                    responseKopecks(remote);

                    String status = remote.getString("status");
                    if (!knownStatus(status)) {
                        throw new Exception("Неизвестный статус");
                    }

                    JSONArray items = remote.getJSONArray("items");

                    if (items.length() < 1 || items.length() > 100) {
                        throw new Exception("Некорректный состав");
                    }

                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        double quantity = item.getDouble("quantity");

                        if (
                                !Double.isFinite(quantity)
                                        || quantity < 0.5
                                        || quantity > 50
                                        || !NumberFormatExceptionSafeHalf(quantity)
                        ) {
                            throw new Exception("Некорректный вес");
                        }

                        if (item.getString("name").trim().isEmpty()) {
                            throw new Exception("Нет названия");
                        }

                        responseKopecks(item);
                    }

                    JSONObject current = historyOrder(id);
                    if (current == null) {
                        throw new Exception("Нет локальной записи");
                    }

                    JSONObject updated = new JSONObject(
                            current.toString()
                    );

                    updated.put("orderNumber", remote.getLong("orderNumber"));
                    updated.put("createdAt", remote.getString("createdAt"));
                    updated.put("updatedAt", remote.optString("updatedAt"));
                    updated.put("fulfillment", remote.getString("fulfillment"));
                    updated.put("items", items);
                    updated.put("totalRub", remote.get("totalRub"));
                    updated.put("status", status);
                    updated.put("checkedAt", System.currentTimeMillis());
                    updated.put("serverSnapshot", true);

                    JSONArray next = historyWith(updated, false);

                    if (!prefs.edit()
                            .putString(HISTORY_KEY, next.toString())
                            .commit()) {
                        throw new Exception("Не удалось сохранить историю");
                    }

                    orderHistory = next;
                    redraw();

                    Toast.makeText(
                            StoreActivity.this,
                            "Статус: " + statusName(status),
                            Toast.LENGTH_SHORT
                    ).show();

                } catch (Exception error) {
                    redraw();
                    message(
                            "История заказов",
                            "Не удалось прочитать или сохранить обновление. " +
                            "Прежние сведения оставлены без изменений. " +
                            "Повторите проверку позже."
                    );
                }
            }

            @Override
            public void onError(ShopApi.ApiError error) {
                if (destroyed) return;

                refreshingOrders.remove(id);
                redraw();

                message(
                        "Не удалось обновить статус",
                        error.getMessage() +
                        "\n\nПрежняя запись сохранена. " +
                        "Проверка статуса не создаёт новый заказ."
                );
            }
        });
    }

    private boolean NumberFormatExceptionSafeHalf(double value) {
        return value * 2 == Math.rint(value * 2);
    }

    private String stringValue(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return "";
        return object.optString(key, "").trim();
    }

    private String productImage(JSONObject item) {
        String[] keys = {
                "imageUrl", "image_url", "photoUrl", "photo_url", "image"
        };

        for (String key : keys) {
            String value = stringValue(item, key);
            if (value.startsWith("https://")) return value;
        }

        return "";
    }

    private void loadCatalog() {
        if (loading || destroyed) return;

        saveProfile();
        loading = true;
        render(true);

        api.loadProducts(new ShopApi.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;

                try {
                    JSONArray array = data.getJSONArray("products");
                    List<Product> loaded = new ArrayList<>();
                    Set<String> ids = new HashSet<>();

                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.getJSONObject(i);
                        if (!item.optBoolean("available", true)) continue;

                        Product product = new Product();
                        product.id = stringValue(item, "id");
                        product.name = stringValue(item, "name");
                        product.category = stringValue(item, "category");
                        product.description = stringValue(item, "description");
                        product.unit = stringValue(item, "unit");
                        product.imageUrl = productImage(item);
                        product.price = item.getInt("priceRub");

                        if (product.category.isEmpty()) {
                            product.category = "Каталог";
                        }

                        if (product.unit.isEmpty()) product.unit = "кг";

                        if (
                                product.id.isEmpty()
                                        || product.name.isEmpty()
                                        || product.price <= 0
                                        || product.price > 1000000
                                        || !ids.add(product.id)
                        ) {
                            throw new Exception("Некорректный товар");
                        }

                        loaded.add(product);
                    }

                    saveProfile();
                    products.clear();
                    products.addAll(loaded);
                    freshCatalog = true;
                    loading = false;
                    render(true);

                } catch (Exception error) {
                    saveProfile();
                    freshCatalog = false;
                    loading = false;
                    render(true);

                    message(
                            "Ошибка каталога",
                            "Не удалось прочитать товары. " +
                            "Попробуйте обновить каталог."
                    );
                }
            }

            @Override
            public void onError(ShopApi.ApiError error) {
                if (destroyed) return;

                saveProfile();
                freshCatalog = false;
                loading = false;
                render(true);
                message("Каталог", error.getMessage());
            }
        });
    }

    private String createOrderAccessToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);

        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[64];

        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 15];
        }

        return new String(result);
    }

    private void prepareOrder() {
        if (sending || pendingOrder != null) return;

        saveProfile();

        if (!historyReadable) {
            message(
                    "История не прочитана",
                    "Чтобы не перезаписать сохранённые сведения, " +
                    "новая отправка остановлена. " +
                    "Не удаляйте приложение. Свяжитесь с магазином."
            );
            return;
        }

        String phone = customerPhone.replaceAll("[^0-9]", "");

        if (!freshCatalog || loading || cartHalves.isEmpty()) {
            message(
                    "Проверьте заказ",
                    "Дождитесь обновления каталога и выберите товары."
            );
            return;
        }

        if (cartHalves.size() > 100) {
            message(
                    "Слишком много позиций",
                    "В одном заказе допускается до 100 разных товаров."
            );
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

        if (
                delivery &&
                (customerAddress.length() < 5 ||
                 customerAddress.length() > 250)
        ) {
            message(
                    "Проверьте адрес",
                    "Введите полный адрес от 5 до 250 символов."
            );
            return;
        }

        if (customerComment.length() > 300) {
            message("Комментарий", "Допускается не более 300 символов.");
            return;
        }

        if (!consent) {
            message(
                    "Согласие",
                    "Подтвердите передачу данных магазину " +
                    "для обработки заказа."
            );
            return;
        }

        if (totalKopecks() <= 0 || totalKopecks() > 1000000000L) {
            message(
                    "Проверьте сумму",
                    "Сумма товаров должна быть больше нуля " +
                    "и не превышать 10 000 000 ₽."
            );
            return;
        }

        try {
            JSONArray items = new JSONArray();

            for (Map.Entry<String, Integer> entry : cartHalves.entrySet()) {
                Product product = findProduct(entry.getKey());

                if (product == null) {
                    message(
                            "Есть недоступный товар",
                            "Уберите недоступный товар из корзины " +
                            "перед оформлением."
                    );
                    return;
                }

                int value = entry.getValue();

                if (
                        value < 1 || value > 100 ||
                        (!isKilogram(product) && value % 2 != 0)
                ) {
                    message(
                            "Проверьте количество",
                            "Исправьте количество товара «" +
                            product.name + "». Для штук " +
                            "и упаковок нужны целые числа."
                    );
                    return;
                }

                JSONObject item = new JSONObject();
                item.put("productId", entry.getKey());
                item.put("quantity", value / 2.0);
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

            /*
             * Ключ создаётся один раз до отправки.
             * Он сохраняется вместе с pending_order.
             * При повторной проверке используется тот же ключ.
             */
            order.put("orderAccessToken", createOrderAccessToken());

            confirmNewOrder(order);

        } catch (Exception error) {
            message("Ошибка", "Не удалось подготовить заказ.");
        }
    }

    private void confirmNewOrder(JSONObject order) {
        AlertDialog dialog = dialogBuilder()
                .setTitle("Подтвердите заказ")
                .setMessage(
                        "Товары: " + money(totalKopecks()) + "\n\n" +
                        (delivery
                                ? "Доставка:\n" + order.optString("address")
                                : "Самовывоз:\n" + PICKUP) +
                        "\n\nОплата при получении.\n" +
                        "Сервер проверит актуальные цены. " +
                        "Если доставка платная, её стоимость " +
                        "согласует магазин."
                )
                .setNegativeButton("Назад", null)
                .setPositiveButton("Отправить", (d, which) -> {
                    if (sending || pendingOrder != null) return;

                    String payload = order.toString();

                    if (!prefs.edit()
                            .putString("pending_order", payload)
                            .commit()) {
                        message(
                                "Ошибка",
                                "Не удалось сохранить заказ на телефоне. " +
                                "Отправка не выполнена."
                        );
                        return;
                    }

                    pendingOrder = payload;
                    sendPending();
                })
                .create();

        dialog.show();
        decorateDialog(dialog);
    }

    private void confirmPending() {
        if (sending || pendingOrder == null) return;

        try {
            JSONObject order = new JSONObject(pendingOrder);

            AlertDialog dialog = dialogBuilder()
                    .setTitle("Проверить прежний заказ?")
                    .setMessage(
                            order.optString("name") + "\n" +
                            order.optString("phone") + "\n" +
                            ("pickup".equals(order.optString("fulfillment"))
                                    ? "Самовывоз"
                                    : order.optString("address")) +
                            "\n\nБудет использован прежний идентификатор. " +
                            "Если заказ уже сохранён, второй с этим " +
                            "идентификатором не создастся."
                    )
                    .setNegativeButton("Назад", null)
                    .setPositiveButton(
                            "Проверить", (d, which) -> sendPending()
                    )
                    .create();

            dialog.show();
            decorateDialog(dialog);

        } catch (Exception error) {
            message(
                    "Не удалось прочитать заказ",
                    "Свяжитесь с магазином перед повторным оформлением. " +
                    "Не удаляйте приложение."
            );
        }
    }

    /*
     * Первая запись истории строится по подтверждению сервера.
     * Сумма — серверная.
     * Названия и единицы до первого lookup берутся из каталога;
     * цены строк здесь намеренно не сохраняются.
     */
    private JSONObject initialHistoryEntry(
            JSONObject submitted,
            JSONObject response,
            long confirmedKopecks
    ) throws Exception {
        String id = response.getString("id");

        JSONObject previous = historyOrder(id);
        if (previous != null) {
            return new JSONObject(previous.toString());
        }

        JSONObject entry = new JSONObject();

        String token = submitted.optString("orderAccessToken", "");
        boolean trackable =
                token.matches("[0-9a-f]{64}") &&
                response.optBoolean("orderTrackingAvailable", false);

        entry.put("id", id);
        entry.put("orderNumber", response.optLong("orderNumber", 0));
        entry.put(
                "totalRub",
                BigDecimal.valueOf(confirmedKopecks, 2).toPlainString()
        );

        String status = response.optString("status", "new");
        entry.put("status", knownStatus(status) ? status : "new");
        entry.put("createdAt", Instant.now().toString());
        entry.put("checkedAt", System.currentTimeMillis());
        entry.put("fulfillment", submitted.optString("fulfillment"));
        entry.put("trackingAvailable", trackable);
        entry.put("serverSnapshot", false);

        if (trackable) {
            entry.put("orderAccessToken", token);
        }

        JSONArray summary = new JSONArray();
        JSONArray requested = submitted.getJSONArray("items");

        for (int i = 0; i < requested.length(); i++) {
            JSONObject item = requested.getJSONObject(i);
            String productId = item.getString("productId");
            Product product = findProduct(productId);

            JSONObject saved = new JSONObject();
            saved.put("productId", productId);
            saved.put("quantity", item.get("quantity"));
            saved.put(
                    "name",
                    product == null
                            ? "Товар " + productId
                            : product.name
            );
            saved.put("unit", product == null ? "" : product.unit);
            summary.put(saved);
        }

        entry.put("items", summary);
        return entry;
    }

    private void sendPending() {
        if (sending || pendingOrder == null) return;

        final JSONObject order;

        try {
            order = new JSONObject(pendingOrder);
        } catch (Exception error) {
            message(
                    "Ошибка",
                    "Не удалось прочитать сохранённый заказ."
            );
            return;
        }

        saveProfile();
        sending = true;
        render(true);

        api.sendOrder(order, new ShopApi.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;

                final String id = data.optString("id", "");
                final long confirmedKopecks;

                try {
                    if (!id.matches(ORDER_ID_PATTERN)) {
                        throw new Exception("Некорректный идентификатор");
                    }

                    confirmedKopecks = responseKopecks(data);

                } catch (Exception error) {
                    sending = false;
                    redraw();
                    message(
                            "Нет подтверждения",
                            "Ответ сервера неполный или содержит " +
                            "неверную сумму. Заказ сохранён на телефоне. " +
                            "Используйте проверку прежнего заказа."
                    );
                    return;
                }

                long orderNumber = data.optLong("orderNumber", 0);

                String receipt =
                        (orderNumber > 0
                                ? "Заказ № " + orderNumber
                                : "Заказ сохранён. Номер уточняется.") +
                        "\nТовары: " + money(confirmedKopecks);

                final JSONArray nextHistory;

                try {
                    JSONObject entry = initialHistoryEntry(
                            order, data, confirmedKopecks
                    );
                    nextHistory = historyWith(entry, true);

                } catch (Exception error) {
                    sending = false;
                    redraw();
                    message(
                            "Заказ сохранён магазином",
                            receipt +
                            "\n\nНе удалось подготовить локальную историю. " +
                            "Неподтверждённая запись оставлена на телефоне. " +
                            "Не оформляйте заказ заново и " +
                            "не удаляйте приложение."
                    );
                    return;
                }

                /*
                 * Одной записью сохраняем историю и убираем pending.
                 * Если запись не удалась, остаётся прежний requestId.
                 */
                boolean saved = prefs.edit()
                        .putString(HISTORY_KEY, nextHistory.toString())
                        .remove("pending_order")
                        .putString("last_order_v2", receipt)
                        .putString("cart_v2", "{}")
                        .commit();

                sending = false;

                if (!saved) {
                    redraw();
                    message(
                            "Заказ сохранён магазином",
                            receipt +
                            "\n\nНе удалось обновить данные телефона. " +
                            "Сохраните номер заказа. " +
                            "Не оформляйте его заново."
                    );
                    return;
                }

                saveProfile();

                orderHistory = nextHistory;
                pendingOrder = null;
                cartHalves.clear();
                consent = false;
                customerComment = "";
                currentTab = 4;
                showingHistory = true;

                render(false);

                message(
                        "Спасибо за заказ!",
                        receipt +
                        "\n\nЗаказ сохранён магазином. " +
                        "Ожидайте подтверждения продавца. " +
                        "Оплата при получении.\n\n" +
                        "Для актуального состава и статуса " +
                        "нажмите «Обновить статус» в истории."
                );
            }

            @Override
            public void onError(ShopApi.ApiError error) {
                if (destroyed) return;

                saveProfile();
                sending = false;

                if (
                        error.shouldClearPendingOrder()
                                && error.statusCode != 409
                ) {
                    if (prefs.edit()
                            .remove("pending_order")
                            .commit()) {
                        pendingOrder = null;
                    }
                }

                render(true);

                String advice;

                if (error.statusCode == 409) {
                    advice =
                            "\n\nОбнаружен конфликт идентификатора " +
                            "или ключа заказа. Свяжитесь с магазином. " +
                            "Не оформляйте этот заказ заново.";
                } else if (pendingOrder != null) {
                    advice =
                            "\n\nЗаказ сохранён на телефоне. " +
                            "Используйте «Проверить прежний заказ», " +
                            "а не создавайте новый.";
                } else {
                    advice =
                            "\n\nПроверьте данные и актуальность " +
                            "каталога перед повторной отправкой.";
                }

                message(
                        "Результат отправки",
                        error.getMessage() + advice
                );
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (currentTab == 4 && showingHistory) {
            openTab(4);
        } else if (currentTab != 0) {
            openTab(0);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        saveProfile();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;

        if (api != null) {
            api.close();
        }

        super.onDestroy();
    }
}
// КОНЕЦ ФАЙЛА
