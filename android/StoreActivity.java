// LAVKA_CLEAN_STORE_TEXT_V1
package ru.makeevka.fishshop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

public class StoreActivity extends Activity {
    // LAVKA_PRODUCT_MOTION_V1
    private ProductMotion productMotion;
    private int productFlightGeneration;

    // LAVKA_PRODUCT_FLIGHT_FOCUS_FIX_V1
    private void playProductFlight(
            ProductMotion.Flight flight
    ) {
        if (flight == null) return;

        if (destroyed || navigation == null) {
            flight.release();
            return;
        }

        final int generation = productFlightGeneration;
        final long deadline =
                android.os.SystemClock.uptimeMillis() + 900L;

        navigation.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed ||
                        generation != productFlightGeneration ||
                        productMotion == null ||
                        navigation == null ||
                        isFinishing() ||
                        isDestroyed()) {
                    flight.release();
                    return;
                }

                if (navigation.getChildCount() > 3) {
                    View target = navigation.getChildAt(3);

                    boolean ready =
                            hasWindowFocus() &&
                            navigation.isAttachedToWindow() &&
                            target.isShown() &&
                            target.getWidth() > 0 &&
                            target.getHeight() > 0;

                    if (ready) {
                        productMotion.play(flight, target);
                        return;
                    }
                }

                if (android.os.SystemClock.uptimeMillis() >= deadline) {
                    flight.release();
                    return;
                }

                boolean queued =
                        navigation.postDelayed(this, 32L);

                if (!queued) {
                    flight.release();
                }
            }
        });
    }
    private Button comfortCartButton;
    private Button comfortCitySelector;
    private CheckBox comfortAgreement;


    private final int BLACK = Color.rgb(14, 15, 17);
    private final int CARD = Color.rgb(25, 27, 30);
    private final int FIELD = Color.rgb(34, 36, 39);
    private final int GOLD = Color.rgb(212, 175, 55);
    private final int WHITE = Color.rgb(247, 244, 236);
    private final int MUTED = Color.rgb(166, 167, 163);
    private final int LINE = Color.rgb(53, 53, 48);

    private static final String PICKUP =
            "г. Макеевка, ул. Плеханова, 1в\n" +
            "Магазин «Лавка Вкуса»";

    private static final String DELIVERY =
            "Бесплатная доставка от 2 000 ₽ по Макеевке, " +
            "Донецку и Харцызску. Другие города и заказы " +
            "до 2 000 ₽ — по согласованию.";

    private static final long FREE_THRESHOLD = 200000L;
    private static final long MAX_TOTAL = 1000000000L;

    private static final String CITY_PREF = "customer_delivery_city";
    private static final String OTHER_CITY_PREF = "customer_other_city";
    private static final String HISTORY_KEY = "orders_history_v1";

    private static final String ORDER_PATTERN =
            "LV-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
            "[0-9a-f]{4}-[0-9a-f]{12}";

    private static final String SLOT_PATTERN =
            "slot-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
            "[0-9a-f]{4}-[0-9a-f]{12}";

    private static final String[] CITIES = {
            "Макеевка", "Донецк", "Харцызск", "Другой город"
    };

    private static final String[] TABS = {
            "Главная", "Каталог", "Избранное", "Корзина", "Профиль"
    };

    private final List<Product> products = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();

    // 1 = 0,5 кг; 2 = 1 кг либо 1 шт/уп.
    private final Map<String, Integer> cart = new HashMap<>();
    private Set<String> favorites = new HashSet<>();
    private JSONArray history = new JSONArray();
    private final Set<String> refreshing = new HashSet<>();

    private final NumberFormat moneyFormat =
            NumberFormat.getNumberInstance(new Locale("ru", "RU"));

    private final NumberFormat quantityFormat =
            NumberFormat.getNumberInstance(new Locale("ru", "RU"));

    private SharedPreferences prefs;
    private ShopApi api;
    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout navigation;

    private EditText nameInput;
    private EditText phoneInput;
    private EditText addressInput;
    private EditText commentInput;
    private EditText otherCityInput;

    private String customerName = "";
    private String customerPhone = "";
    private String customerAddress = "";
    private String customerComment = "";
    private String city = "";
    private String otherCity = "";

    private String category = "";
    private String search = "";
    private String pending;
    private String repeatingId;
    private String selectedSlotId = "";
    private Slot selectedSlotSnapshot;

    private int tab = 0;
    private boolean delivery = true;
    private boolean consent = false;
    private boolean freshCatalog = false;
    private boolean loadingCatalog = false;
    private boolean loadingSlots = false;
    private boolean slotsLoaded = false;
    private boolean preparing = false;
    private boolean sending = false;
    private boolean destroyed = false;
    private boolean showingHistory = false;
    private boolean historyReadable = true;
    private boolean promotionsOnly = false;
    private String scheduleError = "";

    private static class Product {
        String id, name, category, description, unit, imageUrl;
        int price, oldPrice;

        boolean promotion() {
            return oldPrice > price && price > 0;
        }

        int discount() {
            return promotion()
                    ? (int) (((long) oldPrice - price) * 100 / oldPrice)
                    : 0;
        }
    }

    private static class Slot {
        String id, date, start, end, fulfillment;

        String label() {
            LocalDate d = LocalDate.parse(date);
            return String.format(
                    Locale.ROOT, "%02d.%02d.%04d, %s–%s",
                    d.getDayOfMonth(), d.getMonthValue(),
                    d.getYear(), start, end
            );
        }

        JSONObject json() throws Exception {
            JSONObject result = new JSONObject();
            result.put("id", id);
            result.put("date", date);
            result.put("startTime", start);
            result.put("endTime", end);
            result.put("fulfillment", fulfillment);
            result.put("timeZone", "Europe/Moscow");
            result.put("label", label());
            return result;
        }

        boolean same(Slot other) {
            return other != null &&
                    id.equals(other.id) &&
                    date.equals(other.date) &&
                    start.equals(other.start) &&
                    end.equals(other.end) &&
                    fulfillment.equals(other.fulfillment);
        }
    }

    private static class RepeatPlan {
        final Map<String, Integer> quantities = new LinkedHashMap<>();
        final List<String> included = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        long total;
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        moneyFormat.setMinimumFractionDigits(0);
        moneyFormat.setMaximumFractionDigits(2);
        quantityFormat.setMaximumFractionDigits(1);

        getWindow().setStatusBarColor(BLACK);
        getWindow().setNavigationBarColor(CARD);
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        // Не меняем хранилище: история и ключи остаются на месте.
        prefs = getPreferences(MODE_PRIVATE);
        api = new ShopApi();
        productMotion = new ProductMotion(this);
        restore();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(content);

        root.addView(
                scroll, new LinearLayout.LayoutParams(-1, 0, 1)
        );

        View separator = new View(this);
        separator.setBackgroundColor(LINE);
        root.addView(
                separator, new LinearLayout.LayoutParams(-1, dp(1))
        );

        comfortCartButton = ShopComfort.cartButton(
                this, () -> openTab(3)
        );

        LinearLayout.LayoutParams comfortParams =
                new LinearLayout.LayoutParams(-1, dp(52));
        comfortParams.setMargins(
                dp(16), dp(8), dp(16), dp(8)
        );
        root.addView(comfortCartButton, comfortParams);

        navigation = new LinearLayout(this);
        navigation.setBackgroundColor(CARD);
        navigation.setPadding(dp(4), dp(7), dp(4), dp(6));
        root.addView(
                navigation, new LinearLayout.LayoutParams(-1, -2)
        );

        setContentView(root);
        render(false);
        loadCatalog();
        loadSlots();
    }

    private void restore() {
        customerName = prefs.getString("customer_name", "");
        customerPhone = prefs.getString("customer_phone", "");
        customerAddress = prefs.getString("customer_address", "");
        otherCity = prefs.getString(OTHER_CITY_PREF, "");
        delivery = prefs.getBoolean("delivery", true);
        pending = prefs.getString("pending_order", null);

        String savedCity = prefs.getString(CITY_PREF, "");
        city = knownCity(savedCity) ? savedCity : "";

        favorites = new HashSet<>(
                prefs.getStringSet(
                        "favorites_v2", new HashSet<String>()
                )
        );

        try {
            JSONObject saved = new JSONObject(
                    prefs.getString("cart_v2", "{}")
            );
            Iterator<String> keys = saved.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                double q = saved.getDouble(id);
                if (Double.isFinite(q) && q >= 0.5 && q <= 50 &&
                        q * 2 == Math.rint(q * 2)) {
                    cart.put(id, (int) (q * 2));
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
                String id = saved.getJSONObject(i).getString("id");
                if (!id.matches(ORDER_PATTERN) || !ids.add(id)) {
                    throw new Exception("Некорректная история");
                }
            }
            history = saved;
        } catch (Exception error) {
            historyReadable = false;
            history = new JSONArray();
        }
    }

    // ==================== ОБЩИЕ ФУНКЦИИ ====================

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private String str(JSONObject object, String key) {
        return object.has(key) && !object.isNull(key)
                ? object.optString(key, "").trim() : "";
    }

    private String money(long kopecks) {
        return moneyFormat.format(
                BigDecimal.valueOf(kopecks, 2)
        ) + " ₽";
    }

    private long responseKopecks(JSONObject object) throws Exception {
        long result = new BigDecimal(
                object.get("totalRub").toString()
        ).movePointRight(2).longValueExact();
        if (result <= 0 || result > MAX_TOTAL) {
            throw new Exception("Некорректная сумма");
        }
        return result;
    }

    private String orderMoney(JSONObject object) {
        try {
            return money(responseKopecks(object));
        } catch (Exception error) {
            return "Сумма уточняется";
        }
    }

    private String unit(String value) {
        return value == null
                ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean kg(Product p) {
        return "кг".equals(unit(p.unit));
    }

    private boolean supportedUnit(String value) {
        return Arrays.asList("кг", "шт", "уп").contains(unit(value));
    }

    private int halves(String id) {
        Integer value = cart.get(id);
        return value == null ? 0 : value;
    }

    private String quantity(Product p, int halves) {
        return quantityFormat.format(halves / 2.0) + " " + p.unit;
    }

    private long lineTotal(Product p) {
        return (long) p.price * 50L * halves(p.id);
    }

    private long total() {
        long result = 0;
        for (Product p : products) result += lineTotal(p);
        return result;
    }

    private Product findProduct(String id) {
        for (Product p : products) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private boolean busyCart() {
        return sending || preparing || pending != null ||
                repeatingId != null;
    }

    private boolean knownCity(String value) {
        return Arrays.asList(CITIES).contains(value);
    }

    private boolean freeCity(String value) {
        return Arrays.asList(
                "Макеевка", "Донецк", "Харцызск"
        ).contains(value);
    }

    private String actualCity() {
        return "Другой город".equals(city)
                ? otherCity.trim().replaceAll("\\s+", " ") : city;
    }

    private String fulfillment() {
        return delivery ? "delivery" : "pickup";
    }

    private String deliveryHint(String selected, long sum) {
        if (selected == null || selected.isEmpty()) {
            return "Выберите город доставки.";
        }
        if (!freeCity(selected)) {
            return "Возможность и стоимость доставки согласует магазин.";
        }
        if (!freshCatalog || loadingCatalog) {
            return "Бесплатная доставка от 2 000 ₽. " +
                    "Обновите каталог для актуального расчёта.";
        }
        return sum >= FREE_THRESHOLD
                ? "Доставка по городу " + selected + " — бесплатно."
                : "До бесплатной доставки осталось " +
                  money(FREE_THRESHOLD - sum) +
                  ". Стоимость доставки меньшего заказа " +
                  "согласует магазин.";
    }

    // ==================== ОФОРМЛЕНИЕ ====================

    private GradientDrawable surface(
            int color, int stroke, int radius
    ) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        if (stroke != 0) result.setStroke(dp(1), stroke);
        return result;
    }

    private GradientDrawable background(int color, boolean border) {
        return surface(color, border ? GOLD : 0, 20);
    }

    private TextView text(String value, int size, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setIncludeFontPadding(false);
        result.setPadding(0, dp(5), 0, dp(5));
        result.setLineSpacing(dp(3), 1);
        return result;
    }

    private TextView bold(String value, int size, int color) {
        TextView result = text(value, size, color);
        result.setTypeface(
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
        );
        return result;
    }

    private Button button(String title, Runnable action) {
        Button result = new Button(this);
        result.setText(title);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setTypeface(
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
        );
        result.setTextColor(BLACK);
        result.setMinHeight(dp(48));
        result.setMinimumHeight(dp(48));
        result.setMinWidth(0);
        result.setMinimumWidth(0);
        result.setPadding(dp(12), dp(9), dp(12), dp(9));
        result.setBackgroundTintList(null);
        result.setBackground(surface(GOLD, 0, 14));
        result.setStateListAnimator(null);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(5), 0, dp(5));
        result.setLayoutParams(params);
        result.setOnClickListener(v -> action.run());
        return result;
    }

    private Button secondary(String title, Runnable action) {
        Button result = button(title, action);
        result.setTextColor(GOLD);
        result.setBackground(surface(FIELD, LINE, 14));
        return result;
    }

    private LinearLayout box(boolean border) {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setPadding(dp(16), dp(16), dp(16), dp(16));
        result.setBackground(
                surface(CARD, border ? GOLD : LINE, 20)
        );
        return result;
    }

    private LinearLayout panel() {
        LinearLayout result = box(false);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, dp(8));
        content.addView(result, params);
        return result;
    }

    private void section(String title) {
        TextView result = bold(title, 22, WHITE);
        result.setPadding(0, dp(22), 0, dp(11));
        content.addView(result);
    }

    private void sectionLink(
            String title, String link, Runnable action
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(20), 0, dp(9));

        row.addView(
                bold(title, 22, WHITE),
                new LinearLayout.LayoutParams(0, -2, 1)
        );

        TextView more = bold(link, 13, GOLD);
        more.setGravity(Gravity.CENTER);
        more.setPadding(dp(12), dp(12), 0, dp(12));
        more.setOnClickListener(v -> action.run());
        row.addView(more);
        content.addView(row);
    }

    private EditText input(
            LinearLayout parent, String hint,
            String value, int type, int max
    ) {
        EditText result = new EditText(this);
        result.setText(value);
        result.setHint(hint);
        result.setInputType(type);
        result.setTextColor(WHITE);
        result.setHintTextColor(MUTED);
        result.setTextSize(16);
        result.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(max)
        });
        result.setBackgroundTintList(null);
        result.setBackground(surface(FIELD, LINE, 13));
        result.setPadding(dp(13), dp(14), dp(13), dp(14));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(6), 0, dp(6));
        parent.addView(result, params);
        return result;
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(
                this, android.R.style.Theme_Material_Dialog_Alert
        );
    }

    private void decorate(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    surface(CARD, LINE, 22)
            );
        }
        TextView body = dialog.findViewById(android.R.id.message);
        if (body != null) body.setTextColor(WHITE);

        for (int which : new int[]{
                AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL
        }) {
            Button b = dialog.getButton(which);
            if (b != null) b.setTextColor(GOLD);
        }
    }

    private boolean comfortShowFieldError(
            String title, String explanation
    ) {
        TextView target;

        switch (title) {
            case "Имя":
                target = nameInput;
                break;

            case "Телефон":
                target = phoneInput;
                break;

            case "Город":
                target = comfortCitySelector;
                break;

            case "Название города":
                target = otherCityInput;
                break;

            case "Адрес":
                target = addressInput;
                break;

            case "Комментарий":
                target = commentInput;
                break;

            case "Согласие":
                target = comfortAgreement;
                break;

            default:
                return false;
        }

        return ShopComfort.markError(target, explanation);
    }


    private void message(String title, String body) {
        if (destroyed || isFinishing()) return;
        if (tab == 3 && comfortShowFieldError(title, body)) return;
        AlertDialog dialog = dialogBuilder()
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("Понятно", null)
                .create();
        dialog.show();
        decorate(dialog);
    }

    // Простые рисованные значки без дополнительных файлов.
    private class Mark extends View {
        private final int kind;
        private final int tint;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Mark(int kind, int tint) {
            super(StoreActivity.this);
            this.kind = kind;
            this.tint = tint;
            setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
            );
        }

        private void line(
                Canvas c, float x1, float y1, float x2, float y2
        ) {
            c.drawLine(x1, y1, x2, y2, paint);
        }

        @Override
        protected void onDraw(Canvas original) {
            super.onDraw(original);
            Canvas c = original;
            c.save();
            float size = Math.min(getWidth(), getHeight());
            c.translate((getWidth() - size) / 2f,
                    (getHeight() - size) / 2f);
            c.scale(size / 24f, size / 24f);
            paint.setColor(tint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            Path p = new Path();

            switch (kind) {
                case 0:
                    p.moveTo(3, 11);
                    p.lineTo(12, 3);
                    p.lineTo(21, 11);
                    p.moveTo(5, 10);
                    p.lineTo(5, 21);
                    p.lineTo(10, 21);
                    p.lineTo(10, 15);
                    p.lineTo(14, 15);
                    p.lineTo(14, 21);
                    p.lineTo(19, 21);
                    p.lineTo(19, 10);
                    c.drawPath(p, paint);
                    break;

                case 1:
                    c.drawRoundRect(3, 3, 10, 10, 2, 2, paint);
                    c.drawRoundRect(14, 3, 21, 10, 2, 2, paint);
                    c.drawRoundRect(3, 14, 10, 21, 2, 2, paint);
                    c.drawRoundRect(14, 14, 21, 21, 2, 2, paint);
                    break;

                case 2:
                    p.moveTo(12, 21);
                    p.cubicTo(10, 19, 3, 14, 3, 8);
                    p.cubicTo(3, 2, 10, 1, 12, 6);
                    p.cubicTo(14, 1, 21, 2, 21, 8);
                    p.cubicTo(21, 14, 14, 19, 12, 21);
                    c.drawPath(p, paint);
                    break;

                case 3:
                    p.moveTo(2, 4);
                    p.lineTo(5, 4);
                    p.lineTo(8, 16);
                    p.lineTo(19, 16);
                    p.lineTo(22, 7);
                    p.lineTo(6, 7);
                    c.drawPath(p, paint);
                    c.drawCircle(9, 21, 1, paint);
                    c.drawCircle(18, 21, 1, paint);
                    break;

                case 4:
                    c.drawCircle(12, 7, 4, paint);
                    c.drawArc(4, 13, 20, 27, 180, 180, false, paint);
                    break;

                case 5:
                    c.drawCircle(10, 10, 6, paint);
                    line(c, 15, 15, 21, 21);
                    break;

                case 6:
                    c.drawRoundRect(2, 6, 15, 17, 2, 2, paint);
                    p.moveTo(15, 9);
                    p.lineTo(19, 9);
                    p.lineTo(22, 13);
                    p.lineTo(22, 17);
                    p.lineTo(15, 17);
                    c.drawPath(p, paint);
                    c.drawCircle(7, 18, 2, paint);
                    c.drawCircle(18, 18, 2, paint);
                    break;

                case 8:
                    c.drawOval(8, 7, 16, 16, paint);
                    c.drawArc(3, 1, 10, 8, 30, 285, false, paint);
                    c.drawArc(14, 1, 21, 8, -135, 285, false, paint);
                    line(c, 9, 8, 6, 6);
                    line(c, 15, 8, 18, 6);
                    for (int i = 0; i < 3; i++) {
                        int y = 10 + i * 3;
                        line(c, 8, y, 4, y + 2);
                        line(c, 16, y, 20, y + 2);
                    }
                    line(c, 10, 16, 9, 21);
                    line(c, 9, 21, 15, 21);
                    line(c, 15, 21, 14, 16);
                    break;

                case 9:
                    c.drawOval(3, 7, 21, 20, paint);
                    c.drawArc(3, 3, 21, 14, 0, 180, false, paint);
                    c.drawCircle(8, 8, 1, paint);
                    c.drawCircle(12, 6, 1, paint);
                    c.drawCircle(16, 8, 1, paint);
                    break;

                default:
                    p.moveTo(5, 12);
                    p.cubicTo(10, 4, 17, 4, 22, 12);
                    p.cubicTo(17, 20, 10, 20, 5, 12);
                    p.lineTo(1, 7);
                    p.lineTo(1, 17);
                    p.close();
                    c.drawPath(p, paint);
                    c.drawCircle(17, 11, 0.8f, paint);
                    line(c, 12, 6, 10, 3);
                    break;
            }
            c.restore();
        }
    }

    // ==================== СОХРАНЕНИЕ ====================

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
        if (otherCityInput != null) {
            otherCity = otherCityInput.getText().toString().trim();
        }

        prefs.edit()
                .putString("customer_name", customerName)
                .putString("customer_phone", customerPhone)
                .putString("customer_address", customerAddress)
                .putString(CITY_PREF, city)
                .putString(OTHER_CITY_PREF, otherCity)
                .putBoolean("delivery", delivery)
                .apply();
    }

    private JSONObject cartJson(Map<String, Integer> values)
            throws Exception {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            result.put(entry.getKey(), entry.getValue() / 2.0);
        }
        return result;
    }

    private void saveCart() {
        try {
            prefs.edit().putString(
                    "cart_v2", cartJson(cart).toString()
            ).apply();
        } catch (Exception error) {
            message("Корзина", "Не удалось сохранить корзину.");
        }
    }

    private void redraw() {
        saveProfile();
        render(true);
    }

    private void openTab(int value) {
        saveProfile();
        tab = value;
        showingHistory = false;
        render(false);
    }

    private void openHistory() {
        saveProfile();
        tab = 4;
        showingHistory = true;
        render(false);
    }

    private void openCatalog(boolean sales) {
        saveProfile();
        category = "";
        search = "";
        promotionsOnly = sales;
        tab = 1;
        showingHistory = false;
        render(false);
    }

    private void openCategory(String value) {
        saveProfile();
        category = value;
        search = "";
        promotionsOnly = false;
        tab = 1;
        showingHistory = false;
        render(false);
    }

    // ==================== ОСНОВНОЙ ЭКРАН ====================

    private void render(boolean keepPosition) {
        if (destroyed) return;
        final int y = keepPosition ? scroll.getScrollY() : 0;

        nameInput = null;
        phoneInput = null;
        addressInput = null;
        commentInput = null;
        otherCityInput = null;
        comfortCitySelector = null;
        comfortAgreement = null;

        content.removeAllViews();
        navigation.removeAllViews();

        header();
        drawNavigation();
        ShopComfort.updateCart(
                comfortCartButton,
                tab != 3 && (!cart.isEmpty() || pending != null),
                freshCatalog && !loadingCatalog,
                cart.size(),
                money(total()),
                pending != null
        );

        if (loadingCatalog) {
            content.addView(text(
                    "Обновляем каталог… Первый запрос после " +
                    "простоя может занять около минуты.",
                    12, MUTED
            ));
        } else if (!freshCatalog) {
            LinearLayout notice = panel();
            notice.addView(bold("Проверим актуальные цены", 17, WHITE));
            notice.addView(text(
                    "Для нового заказа загрузите каталог из интернета.",
                    13, MUTED
            ));
            notice.addView(button(
                    "Загрузить каталог", this::loadCatalog
            ));
        }

        if (preparing) {
            content.addView(text(
                    "Проверяем данные заказа и выбранное время…",
                    13, GOLD
            ));
        }
        if (repeatingId != null) {
            content.addView(text(
                    "Подготавливаем повтор. Корзина изменится " +
                    "только после подтверждения.", 13, GOLD
            ));
        }

        switch (tab) {
            case 0: showHome(); break;
            case 1: showCatalog(false); break;
            case 2: showCatalog(true); break;
            case 3: showCart(); break;
            default:
                if (showingHistory) showHistory();
                else showProfile();
                break;
        }

        scroll.post(() -> {
            if (!destroyed) scroll.scrollTo(0, y);
        });
    }

    private void header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = bold("ЛВ", 22, GOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(surface(CARD, GOLD, 16));
        row.addView(
                logo, new LinearLayout.LayoutParams(dp(50), dp(50))
        );

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(bold("Лавка Вкуса", 23, WHITE));
        titles.addView(text(
                "Макеевка · Доставка и самовывоз", 11, MUTED
        ));
        row.addView(
                titles, new LinearLayout.LayoutParams(0, -2, 1)
        );


        // LAVKA_SETTINGS_GEAR_V1
        TextView settingsGear = text(
                "\u2699\uFE0E", 28, GOLD
        );

        settingsGear.setGravity(Gravity.CENTER);
        settingsGear.setPadding(0, 0, 0, 0);
        settingsGear.setBackground(
                surface(CARD, LINE, 14)
        );

        settingsGear.setContentDescription(
                "Настройки приложения"
        );

        settingsGear.setFocusable(true);

        settingsGear.setOnClickListener(view -> {
            if (this instanceof MainActivity) {
                ((MainActivity) this).openShopSettings();
            }
        });

        LinearLayout.LayoutParams gearParams =
                new LinearLayout.LayoutParams(
                        dp(48), dp(48)
                );

        gearParams.setMargins(dp(8), 0, 0, 0);
        row.addView(settingsGear, gearParams);

        content.addView(row);
    }

    private void drawNavigation() {
        for (int i = 0; i < TABS.length; i++) {
            final int destination = i;
            boolean selected = i == tab;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(4), 0, dp(3));
            if (selected) {
                item.setBackground(
                        surface(Color.rgb(48, 43, 26), 0, 15)
                );
            }

            FrameLayout iconBox = new FrameLayout(this);
            Mark icon = new Mark(i, selected ? GOLD : MUTED);
            FrameLayout.LayoutParams iconParams =
                    new FrameLayout.LayoutParams(dp(24), dp(24));
            iconParams.gravity = Gravity.CENTER;
            iconBox.addView(icon, iconParams);

            if (i == 3 && !cart.isEmpty()) {
                TextView badge = bold(
                        String.valueOf(cart.size()), 9, BLACK
                );
                badge.setGravity(Gravity.CENTER);
                badge.setPadding(0, 0, 0, 0);
                badge.setBackground(surface(GOLD, 0, 8));
                FrameLayout.LayoutParams countParams =
                        new FrameLayout.LayoutParams(dp(17), dp(17));
                countParams.gravity = Gravity.TOP | Gravity.RIGHT;
                iconBox.addView(badge, countParams);
            }

            item.addView(
                    iconBox,
                    new LinearLayout.LayoutParams(dp(42), dp(28))
            );

            TextView label = text(
                    TABS[i], 10, selected ? GOLD : MUTED
            );
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(3), 0, 0);
            item.addView(label);
            item.setContentDescription(
                    TABS[i] + (i == 3
                            ? ", позиций в корзине: " + cart.size()
                            : "")
            );
            item.setOnClickListener(v -> openTab(destination));

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, dp(60), 1);
            params.setMargins(dp(2), 0, dp(2), 0);
            navigation.addView(item, params);
        }
    }

    private void homeSearch() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setBackground(surface(FIELD, LINE, 15));
        row.addView(
                new Mark(5, MUTED),
                new LinearLayout.LayoutParams(dp(22), dp(22))
        );
        TextView hint = text("Найти рыбу, икру, морепродукты", 13, MUTED);
        hint.setPadding(dp(10), 0, 0, 0);
        row.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription("Открыть поиск товаров");
        row.setOnClickListener(v -> openCatalog(false));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(20), 0, dp(9));
        content.addView(row, params);
    }

    private void showHome() {
        homeSearch();

        LinearLayout hero = panel();
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Color.rgb(69, 57, 30),
                        Color.rgb(34, 32, 26),
                        CARD
                }
        );
        gradient.setCornerRadius(dp(22));
        hero.setBackground(gradient);

        TextView label = bold("ЛАВКА ВКУСА · ВЫБИРАЙТЕ С УДОВОЛЬСТВИЕМ",
                10, GOLD);
        label.setLetterSpacing(0.07f);
        hero.addView(label);
        hero.addView(bold("Вкусные покупки\nначинаются здесь", 29, WHITE));
        hero.addView(text(
                "Рыба, морепродукты и раки\n" +
                "для вашего стола. Оплата при получении.",
                14, WHITE
        ));
        hero.addView(button(
                "Открыть каталог  →", () -> openCatalog(false)
        ));

        LinearLayout deliveryCard = panel();
        LinearLayout deliveryRow = new LinearLayout(this);
        deliveryRow.setGravity(Gravity.CENTER_VERTICAL);
        deliveryRow.addView(
                new Mark(6, GOLD),
                new LinearLayout.LayoutParams(dp(36), dp(36))
        );

        LinearLayout deliveryText = new LinearLayout(this);
        deliveryText.setOrientation(LinearLayout.VERTICAL);
        deliveryText.setPadding(dp(12), 0, 0, 0);
        deliveryText.addView(bold("Доставка бесплатно от 2 000 ₽", 16, WHITE));
        
        deliveryRow.addView(
                deliveryText, new LinearLayout.LayoutParams(0, -2, 1)
        );
        deliveryCard.addView(deliveryRow);

        // LAVKA_DELIVERY_TRUCK_V1
        DeliveryTruckAnimation.install(deliveryCard);
        

        sectionLink("Категории", "Все  →", () -> openCatalog(false));
        categoryTiles();

        List<Product> sales = new ArrayList<>();
        for (Product p : products) {
            if (p.promotion()) sales.add(p);
        }

        if (!sales.isEmpty()) {
            sectionLink("Выгодные предложения", "Все  →",
                    () -> openCatalog(true));
            grid(new ArrayList<>(
                    sales.subList(0, Math.min(4, sales.size()))
            ));
        } else {
            LinearLayout promo = panel();
            promo.addView(bold("Специальные предложения", 19, GOLD));
            promo.addView(text(
                    "Когда в магазине появятся акции, " +
                    "они будут доступны здесь.", 13, MUTED
            ));
            promo.addView(secondary(
                    "Раздел акций", () -> openCatalog(true)
            ));
        }

        sectionLink("Выбирайте к столу", "Каталог  →",
                () -> openCatalog(false));
        grid(new ArrayList<>(
                products.subList(0, Math.min(6, products.size()))
        ));

        if (products.isEmpty() && freshCatalog) {
            emptyState(
                    1, "Ассортимент готовится",
                    "Загляните немного позже.",
                    "Обновить", this::loadCatalog
            );
        }

        if (history.length() > 0 || pending != null) {
            LinearLayout orders = panel();
            orders.addView(bold("Покупки под рукой", 19, WHITE));
            orders.addView(text(
                    "Проверьте статус или повторите любимый заказ.",
                    13, MUTED
            ));
            orders.addView(button("Мои заказы", this::openHistory));
        }

        LinearLayout pickup = panel();
        pickup.addView(text("САМОВЫВОЗ", 11, GOLD));
        pickup.addView(bold("Загляните в нашу лавку", 21, WHITE));
        pickup.addView(text(PICKUP, 14, MUTED));

        content.addView(secondary(
                "Обновить ассортимент", this::loadCatalog
        ));
    }

    private List<String> categories() {
        Set<String> values = new LinkedHashSet<>();
        for (Product p : products) values.add(p.category);
        return new ArrayList<>(values);
    }

    private int categoryIcon(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("икр")) return 9;
        if (lower.contains("рак") || lower.contains("морепр")) return 8;
        return 7;
    }

    private void categoryTiles() {
        List<String> values = categories();
        if (values.isEmpty()) {
            content.addView(text(
                    "Категории появятся после загрузки товаров.",
                    13, MUTED
            ));
            return;
        }

        HorizontalScrollView horizontal =
                new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);

        for (String value : values) {
            LinearLayout tile = box(false);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(10), dp(13), dp(10), dp(10));
            tile.addView(
                    new Mark(categoryIcon(value), GOLD),
                    new LinearLayout.LayoutParams(dp(39), dp(39))
            );

            TextView title = bold(value, 12, WHITE);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(3);
            title.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(
                    title, new LinearLayout.LayoutParams(-1, 0, 1)
            );

            tile.setContentDescription("Категория: " + value);
            tile.setOnClickListener(v -> openCategory(value));
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(dp(130), dp(124));
            params.setMargins(0, 0, dp(9), 0);
            row.addView(tile, params);
        }

        horizontal.addView(row);
        content.addView(horizontal);
    }

    private void categoryChips() {
        HorizontalScrollView horizontal =
                new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);

        LinearLayout row = new LinearLayout(this);
        addCategory(row, "Все товары", "");
        for (String value : categories()) addCategory(row, value, value);

        horizontal.addView(row);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(7), 0, dp(7));
        content.addView(horizontal, params);
    }

    private void addCategory(
            LinearLayout row, String title, String value
    ) {
        Button chip = secondary(title, () -> openCategory(value));
        chip.setTextSize(12);
        if (category.equals(value) && !promotionsOnly) {
            chip.setTextColor(BLACK);
            chip.setBackground(surface(GOLD, 0, 14));
        }
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-2, dp(44));
        params.setMargins(0, 0, dp(8), 0);
        row.addView(chip, params);
    }

    private void showCatalog(boolean onlyFavorites) {
        section(onlyFavorites
                ? "Избранное" : promotionsOnly ? "Акции" : "Каталог");

        if (!onlyFavorites) {
            LinearLayout searchRow = new LinearLayout(this);
            searchRow.setGravity(Gravity.CENTER_VERTICAL);
            EditText query = input(
                    searchRow, "Название или описание", search,
                    InputType.TYPE_CLASS_TEXT, 100
            );
            LinearLayout.LayoutParams queryParams =
                    new LinearLayout.LayoutParams(0, -2, 1);
            queryParams.setMargins(0, dp(6), dp(8), dp(6));
            query.setLayoutParams(queryParams);
            query.setSingleLine(true);

            Button find = button("Найти", () -> {
                search = query.getText().toString().trim();
                redraw();
            });
            searchRow.addView(
                    find, new LinearLayout.LayoutParams(dp(76), dp(48))
            );
            content.addView(searchRow);

            categoryChips();

            content.addView(secondary(
                    promotionsOnly ? "Показать все товары" : "Только акции",
                    () -> {
                        search = query.getText().toString().trim();
                        promotionsOnly = !promotionsOnly;
                        redraw();
                    }
            ));

            if (!search.isEmpty() || !category.isEmpty() || promotionsOnly) {
                content.addView(secondary("Сбросить фильтры", () -> {
                    search = "";
                    category = "";
                    promotionsOnly = false;
                    redraw();
                }));
            }
        }

        List<Product> found = new ArrayList<>();
        String query = search.toLowerCase(Locale.ROOT);

        for (Product p : products) {
            if (onlyFavorites) {
                if (!favorites.contains(p.id)) continue;
            } else {
                if (promotionsOnly && !p.promotion()) continue;
                if (!category.isEmpty() && !category.equals(p.category)) {
                    continue;
                }
                String searchable =
                        p.name + " " + p.category + " " + p.description;
                if (!searchable.toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
            }
            found.add(p);
        }

        content.addView(text("Найдено товаров: " + found.size(), 12, MUTED));
        grid(found);

        if (found.isEmpty()) {
            emptyState(
                    onlyFavorites ? 2 : 5,
                    onlyFavorites ? "Сохраните любимое" : "Ничего не найдено",
                    onlyFavorites
                            ? "Нажимайте на сердечко в карточках товаров."
                            : "Попробуйте другое название или сбросьте фильтры.",
                    "Открыть весь каталог", () -> openCatalog(false)
            );
        }

        if (!onlyFavorites) {
            content.addView(secondary(
                    "Обновить каталог", this::loadCatalog
            ));
        }
    }

    private void emptyState(
            int icon, String title, String description,
            String actionTitle, Runnable action
    ) {
        LinearLayout card = panel();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(
                new Mark(icon, GOLD),
                new LinearLayout.LayoutParams(dp(52), dp(52))
        );
        TextView heading = bold(title, 22, WHITE);
        heading.setGravity(Gravity.CENTER);
        card.addView(heading);
        TextView detail = text(description, 14, MUTED);
        detail.setGravity(Gravity.CENTER);
        card.addView(detail);
        card.addView(button(actionTitle, action));
    }

    // ==================== КАРТОЧКИ ТОВАРОВ ====================

    private void badge(LinearLayout parent, Product p) {
        if (!p.promotion()) return;
        TextView value = bold(
                p.discount() > 0
                        ? "−" + p.discount() + "%"
                        : "АКЦИЯ",
                11, BLACK
        );
        value.setBackground(surface(GOLD, 0, 8));
        value.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, dp(7), 0, dp(2));
        parent.addView(value, params);
    }

    private void prices(LinearLayout parent, Product p, int size) {
        if (p.promotion()) {
            TextView old = text(
                    money((long) p.oldPrice * 100) + " / " + p.unit,
                    Math.max(11, size - 5), MUTED
            );
            old.setPaintFlags(
                    old.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
            );
            parent.addView(old);
        }
        parent.addView(bold(
                money((long) p.price * 100) + " / " + p.unit,
                size, GOLD
        ));
    }

    private void grid(List<Product> values) {
        int columns = getResources().getConfiguration().screenWidthDp
                < 350 ? 1 : 2;

        for (int start = 0; start < values.size(); start += columns) {
            LinearLayout row = new LinearLayout(this);
            row.setBaselineAligned(false);

            for (int column = 0; column < columns; column++) {
                int index = start + column;
                LinearLayout.LayoutParams params =
                        new LinearLayout.LayoutParams(0, -1, 1);
                params.setMargins(
                        column == 0 ? 0 : dp(5), dp(6),
                        column == columns - 1 ? 0 : dp(5), dp(6)
                );
                row.addView(
                        index < values.size()
                                ? productCard(values.get(index))
                                : new View(this),
                        params
                );
            }
            content.addView(
                    row, new LinearLayout.LayoutParams(-1, -2)
            );
        }
    }

    private void toggleFavorite(Product p) {
        if (!favorites.add(p.id)) favorites.remove(p.id);
        prefs.edit().putStringSet(
                "favorites_v2", new HashSet<>(favorites)
        ).apply();
        redraw();
    }

    private LinearLayout productCard(Product p) {
        LinearLayout card = box(false);
        card.setPadding(dp(9), dp(9), dp(9), dp(10));

        FrameLayout picture = new FrameLayout(this);
        ProductPhoto photo =
                new ProductPhoto(this, p.name, p.category, p.imageUrl);
        photo.setOnClickListener(v -> showProduct(p));
        photo.setContentDescription("Открыть товар: " + p.name);
        picture.addView(
                photo, new FrameLayout.LayoutParams(-1, -1)
        );

        TextView favorite = bold(
                favorites.contains(p.id) ? "♥" : "♡", 24,
                favorites.contains(p.id) ? GOLD : WHITE
        );
        favorite.setGravity(Gravity.CENTER);
        favorite.setPadding(0, 0, 0, 0);
        favorite.setBackground(surface(BLACK, LINE, 14));
        favorite.setContentDescription(
                favorites.contains(p.id)
                        ? "Убрать из избранного"
                        : "Добавить в избранное"
        );
        favorite.setOnClickListener(v -> toggleFavorite(p));
        FrameLayout.LayoutParams favoriteParams =
                new FrameLayout.LayoutParams(dp(44), dp(44));
        favoriteParams.gravity = Gravity.TOP | Gravity.RIGHT;
        favoriteParams.setMargins(0, dp(4), dp(4), 0);
        picture.addView(favorite, favoriteParams);
        card.addView(
                picture, new LinearLayout.LayoutParams(-1, dp(150))
        );

        badge(card, p);

        TextView group = text(p.category, 10, MUTED);
        group.setSingleLine(true);
        group.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(group);

        TextView name = bold(p.name, 15, WHITE);
        name.setMinLines(2);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setOnClickListener(v -> showProduct(p));
        card.addView(name);

        LinearLayout spacer = new LinearLayout(this);
        card.addView(
                spacer, new LinearLayout.LayoutParams(-1, 0, 1)
        );

        prices(card, p, 18);
        card.addView(text(
                kg(p)
                        ? "500 г — " + money((long) p.price * 50)
                        : "За 1 " + p.unit,
                11, MUTED
        ));
        quantityControls(card, p);
        productMotion.bind(p.id, card, photo);
        return card;
    }

    private void showProduct(Product p) {
        if (destroyed || isFinishing()) return;

        final int step = kg(p) ? 1 : 2;
        final int[] selected = {step};

        ScrollView body = new ScrollView(this);
        body.setFillViewport(true);

        LinearLayout detail = box(false);

        ProductPhoto photo = new ProductPhoto(
                this, p.name, p.category, p.imageUrl
        );
        photo.setContentDescription(
                "Фото товара: " + p.name
        );
        detail.addView(
                photo,
                new LinearLayout.LayoutParams(-1, dp(250))
        );

        badge(detail, p);
        detail.addView(text(p.category, 12, MUTED));
        detail.addView(bold(p.name, 24, WHITE));
        prices(detail, p, 25);

        if (kg(p)) {
            detail.addView(text(
                    "500 г — " + money((long) p.price * 50),
                    15, GOLD
            ));
        }

        detail.addView(bold("Описание", 18, WHITE));
        detail.addView(text(
                p.description.isEmpty()
                        ? "Описание пока не добавлено. " +
                          "Подробности можно уточнить у магазина."
                        : p.description,
                15, WHITE
        ));

        detail.addView(bold(
                "Количество для добавления", 18, WHITE
        ));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        Button minus = secondary("−", () -> {});
        Button plus = button("+", () -> {});

        minus.setContentDescription(
                "Уменьшить количество товара " + p.name
        );
        plus.setContentDescription(
                "Увеличить количество товара " + p.name
        );

        for (Button control : new Button[]{minus, plus}) {
            control.setPadding(0, 0, 0, 0);
        }

        TextView count = bold("", 19, WHITE);
        count.setGravity(Gravity.CENTER);

        controls.addView(
                minus,
                new LinearLayout.LayoutParams(dp(52), dp(48))
        );
        controls.addView(
                count,
                new LinearLayout.LayoutParams(0, -2, 1)
        );
        controls.addView(
                plus,
                new LinearLayout.LayoutParams(dp(52), dp(48))
        );
        detail.addView(controls);

        TextView selectedTotal = bold("", 23, GOLD);
        detail.addView(selectedTotal);

        TextView alreadyInCart = text("", 13, MUTED);
        detail.addView(alreadyInCart);

        if (kg(p)) {
            detail.addView(text(
                    "Выбор с шагом 500 г. " +
                    "Указанное количество будет добавлено " +
                    "к уже находящемуся в корзине.",
                    12, MUTED
            ));
        } else {
            detail.addView(text(
                    "Указанное количество будет добавлено " +
                    "к уже находящемуся в корзине.",
                    12, MUTED
            ));
        }

        detail.addView(text(
                "Оплата при получении.",
                13, MUTED
        ));

        body.addView(detail);

        AlertDialog dialog = dialogBuilder()
                .setView(body)
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Добавить в корзину", null)
                .create();

        dialog.show();
        decorate(dialog);

        Button add = dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
        );

        Runnable update = () -> {
            int current = halves(p.id);

            count.setText(quantity(p, selected[0]));

            long amount =
                    (long) p.price * 50L * selected[0];
            selectedTotal.setText(
                    "За выбранное: " + money(amount)
            );

            alreadyInCart.setText(
                    current > 0
                            ? "Уже в корзине: " +
                              quantity(p, current)
                            : "Этого товара пока нет в корзине."
            );

            boolean available =
                    !busyCart() &&
                    freshCatalog &&
                    !loadingCatalog &&
                    findProduct(p.id) == p;

            boolean validCurrent =
                    kg(p) || current % 2 == 0;

            minus.setEnabled(
                    available && selected[0] > step
            );

            plus.setEnabled(
                    available &&
                    validCurrent &&
                    current + selected[0] + step <= 100
            );

            add.setEnabled(
                    available &&
                    validCurrent &&
                    current + selected[0] <= 100
            );

            if (!available) {
                alreadyInCart.setText(
                        "Добавление сейчас недоступно. " +
                        "Дождитесь завершения проверки заказа " +
                        "или обновления каталога и откройте " +
                        "карточку заново."
                );
            } else if (!validCurrent) {
                alreadyInCart.setText(
                        "Единица продажи изменилась. " +
                        "Исправьте количество в корзине " +
                        "кнопкой «+» или «−»."
                );
            } else if (current + selected[0] > 100) {
                alreadyInCart.setText(
                        "Лимит в корзине — 50 " +
                        p.unit + " этого товара."
                );
            }
        };

        minus.setOnClickListener(view -> {
            if (selected[0] > step) {
                selected[0] -= step;
            }
            update.run();
        });

        plus.setOnClickListener(view -> {
            if (halves(p.id) + selected[0] + step <= 100) {
                selected[0] += step;
            }
            update.run();
        });

        add.setOnClickListener(view -> {
            if (busyCart()) {
                message(
                        "Корзина",
                        "Сначала завершите проверку " +
                        "неподтверждённого заказа."
                );
                return;
            }

            if (!freshCatalog ||
                    loadingCatalog ||
                    findProduct(p.id) != p) {
                dialog.dismiss();
                message(
                        "Обновите товар",
                        "Каталог изменился или ещё загружается. " +
                        "Откройте карточку заново после обновления."
                );
                return;
            }

            int current = halves(p.id);

            if (!kg(p) && current % 2 != 0) {
                message(
                        "Количество",
                        "Единица продажи изменилась. " +
                        "Исправьте количество в корзине " +
                        "кнопкой «+» или «−»."
                );
                return;
            }

            int next = current + selected[0];

            if (next > 100) {
                message(
                        "Количество",
                        "В корзине можно выбрать до 50 " +
                        p.unit + " этого товара."
                );
                return;
            }

            ProductMotion.Flight motionFlight =
                    productMotion.prepare(photo);

            cart.put(p.id, next);
            saveCart();
            dialog.dismiss();
            redraw();
            playProductFlight(motionFlight);

            Toast.makeText(
                    StoreActivity.this,
                    "Добавлено: " + quantity(p, selected[0]),
                    Toast.LENGTH_SHORT
            ).show();
        });

        update.run();
    }

    private void quantityControls(LinearLayout parent, Product p) {
        int value = halves(p.id);
        if (value == 0) {
            Button add = button(
                    kg(p) ? "+ Добавить 500 г" : "+ В корзину",
                    () -> changeQuantity(p, 1)
            );
            add.setTextSize(12);
            add.setEnabled(!busyCart());
            parent.addView(add);
            return;
        }

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button minus = secondary("−", () -> changeQuantity(p, -1));
        Button plus = button("+", () -> changeQuantity(p, 1));
        minus.setContentDescription("Уменьшить: " + p.name);
        plus.setContentDescription("Увеличить: " + p.name);

        for (Button b : new Button[]{minus, plus}) {
            b.setPadding(0, 0, 0, 0);
            b.setEnabled(!busyCart());
        }

        row.addView(
                minus, new LinearLayout.LayoutParams(dp(44), dp(46))
        );
        TextView count = bold(quantity(p, value), 12, WHITE);
        count.setGravity(Gravity.CENTER);
        row.addView(
                count, new LinearLayout.LayoutParams(0, -2, 1)
        );
        row.addView(
                plus, new LinearLayout.LayoutParams(dp(44), dp(46))
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(5), 0, dp(5));
        parent.addView(row, params);
    }

    private boolean changeQuantity(Product p, int direction) {
        if (busyCart()) {
            message(
                    "Корзина",
                    "Сначала завершите проверку или " +
                    "проверьте неподтверждённый заказ."
            );
            return false;
        }

        int current = halves(p.id);
        int step = kg(p) ? 1 : 2;
        int next = step == 2 && current % 2 != 0
                ? current + (direction > 0 ? 1 : -1)
                : current + direction * step;

        if (next > 100) {
            message("Количество", "Можно выбрать до 50 " + p.unit + ".");
            return false;
        }
        ProductMotion.Flight motionFlight =
                direction > 0
                        ? productMotion.prepare(p.id)
                        : null;

        if (next <= 0) cart.remove(p.id);
        else cart.put(p.id, next);

        saveCart();
        redraw();
        playProductFlight(motionFlight);
        return true;
    }

    // ==================== ГОРОД И ПОКУПАТЕЛЬ ====================

    private void cityFields(LinearLayout parent) {
        parent.addView(bold("Город доставки", 15, WHITE));
        Button selector = secondary(
                city.isEmpty() ? "Выберите город  ›" : city + "  ›",
                () -> {
                    if (busyCart()) return;
                    saveProfile();
                    AlertDialog dialog = dialogBuilder()
                            .setTitle("Город доставки")
                            .setSingleChoiceItems(
                                    CITIES,
                                    Arrays.asList(CITIES).indexOf(city),
                                    (d, which) -> {
                                        city = CITIES[which];
                                        saveProfile();
                                        d.dismiss();
                                        render(true);
                                    }
                            )
                            .setNegativeButton("Отмена", null)
                            .create();
                    dialog.show();
                    decorate(dialog);
                }
        );
        comfortCitySelector = selector;
        selector.setEnabled(!busyCart());
        parent.addView(selector);

        if ("Другой город".equals(city)) {
            otherCityInput = input(
                    parent, "Название вашего города", otherCity,
                    InputType.TYPE_CLASS_TEXT |
                            InputType.TYPE_TEXT_FLAG_CAP_WORDS,
                    80
            );
            otherCityInput.setEnabled(!busyCart());
        }
        parent.addView(text(
                deliveryHint(city, total()), 13, MUTED
        ));
    }

    private void customerFields(LinearLayout parent, boolean order) {
        nameInput = input(
                parent, "Ваше имя", customerName,
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_FLAG_CAP_WORDS, 80
        );
        phoneInput = input(
                parent, "Телефон: +7…", customerPhone,
                InputType.TYPE_CLASS_PHONE, 32
        );
        ShopComfort.attachPhone(phoneInput);

        if (!order) {
            cityFields(parent);
            addressInput = input(
                    parent, "Полный адрес доставки", customerAddress,
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
        mode.setPadding(0, dp(16), 0, dp(16));
        mode.setChecked(delivery);
        mode.setThumbTintList(ColorStateList.valueOf(GOLD));
        mode.setEnabled(!busyCart());
        parent.addView(mode);
        mode.setOnCheckedChangeListener((v, checked) -> {
            saveProfile();
            delivery = checked;
            clearSelectedSlot();
            saveProfile();
            render(true);
        });

        if (delivery) {
            cityFields(parent);
            addressInput = input(
                    parent, "Улица, дом, квартира / полный адрес",
                    customerAddress,
                    InputType.TYPE_CLASS_TEXT |
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    250
            );
        } else {
            parent.addView(text(
                    "Самовывоз:\n" + PICKUP, 14, WHITE
            ));
        }

        scheduleFields(parent);
        commentInput = input(
                parent, "Комментарий к заказу", customerComment,
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                300
        );

        CheckBox agreement = new CheckBox(this);
        comfortAgreement = agreement;
        agreement.setText(
                "Согласен на передачу имени, телефона и адреса " +
                "магазину для обработки этого заказа."
        );
        agreement.setTextColor(MUTED);
        agreement.setTextSize(12);
        agreement.setButtonTintList(ColorStateList.valueOf(GOLD));
        agreement.setChecked(consent);
        agreement.setOnCheckedChangeListener(
                (v, checked) -> consent = checked
        );
        parent.addView(agreement);
    }

    // ==================== РАСПИСАНИЕ ====================

    private Slot parseSlot(JSONObject value) throws Exception {
        Slot result = new Slot();
        result.id = value.getString("id");
        result.date = value.getString("date");
        result.start = value.getString("startTime");
        result.end = value.getString("endTime");
        result.fulfillment = value.getString("fulfillment");

        if (!result.id.matches(SLOT_PATTERN) ||
                !result.date.matches("\\d{4}-\\d{2}-\\d{2}") ||
                !result.start.matches("[0-2][0-9]:[0-5][0-9]") ||
                !result.end.matches("[0-2][0-9]:[0-5][0-9]") ||
                !Arrays.asList("delivery", "pickup")
                        .contains(result.fulfillment)) {
            throw new Exception("Некорректный интервал");
        }

        LocalDate.parse(result.date);
        LocalTime from = LocalTime.parse(result.start);
        LocalTime to = LocalTime.parse(result.end);
        if (!from.isBefore(to)) throw new Exception("Некорректное время");

        String zone = str(value, "timeZone");
        if (!zone.isEmpty() && !"Europe/Moscow".equals(zone)) {
            throw new Exception("Неожиданный часовой пояс");
        }
        return result;
    }

    private List<Slot> parseSlots(JSONObject data) throws Exception {
        JSONArray array = data.getJSONArray("slots");
        List<Slot> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.getJSONObject(i);
            if (!value.optBoolean("enabled", true)) continue;
            Slot slot = parseSlot(value);
            if (!ids.add(slot.id)) throw new Exception("Повтор интервала");
            result.add(slot);
        }

        result.sort(Comparator
                .comparing((Slot s) -> s.date)
                .thenComparing(s -> s.start)
                .thenComparing(s -> s.id));
        return result;
    }

    private Slot findSlot(String id) {
        for (Slot slot : slots) {
            if (slot.id.equals(id)) return slot;
        }
        return null;
    }

    private void clearSelectedSlot() {
        selectedSlotId = "";
        selectedSlotSnapshot = null;
    }

    private void replaceSlots(List<Slot> loaded) {
        slots.clear();
        slots.addAll(loaded);
        slotsLoaded = true;
        scheduleError = "";

        if (!selectedSlotId.isEmpty()) {
            Slot latest = findSlot(selectedSlotId);
            if (latest == null ||
                    !latest.fulfillment.equals(fulfillment()) ||
                    !latest.same(selectedSlotSnapshot)) {
                clearSelectedSlot();
                scheduleError =
                        "Прежний интервал изменился или закрыт. " +
                        "Выберите время заново либо согласуйте его.";
            }
        }
    }

    private void loadSlots() {
        if (destroyed || loadingSlots || preparing) return;
        saveProfile();
        loadingSlots = true;
        redraw();

        api.loadReceivingSlots(new ShopApi.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;
                saveProfile();
                loadingSlots = false;
                try {
                    replaceSlots(parseSlots(data));
                } catch (Exception error) {
                    slotsLoaded = false;
                    scheduleError = "Не удалось прочитать расписание.";
                }
                render(true);
            }

            @Override
            public void onError(ShopApi.ApiError error) {
                if (destroyed) return;
                saveProfile();
                loadingSlots = false;
                slotsLoaded = false;
                scheduleError = error.getMessage();
                render(true);
            }
        });
    }

    private void scheduleFields(LinearLayout parent) {
        parent.addView(bold("Дата и время получения", 18, WHITE));
        parent.addView(text("Московское время, UTC+3.", 12, MUTED));

        Slot selected = selectedSlotSnapshot;
        parent.addView(text(
                selected != null
                        ? "Выбрано: " + selected.label()
                        : "Время будет согласовано с магазином.",
                14, selected != null ? GOLD : MUTED
        ));

        if (loadingSlots) {
            parent.addView(text("Загружаем расписание…", 12, MUTED));
        }
        if (!scheduleError.isEmpty()) {
            parent.addView(text(scheduleError, 12, GOLD));
        }

        Button choose = button(
                "Выбрать дату и время", this::chooseDate
        );
        choose.setEnabled(!busyCart() && !loadingSlots && slotsLoaded);
        parent.addView(choose);

        if (!selectedSlotId.isEmpty()) {
            Button clear = secondary(
                    "Согласовать время с магазином",
                    () -> {
                        if (busyCart()) return;
                        saveProfile();
                        clearSelectedSlot();
                        render(true);
                    }
            );
            clear.setEnabled(!busyCart());
            parent.addView(clear);
        }

        Button refresh = secondary(
                loadingSlots ? "Загрузка…" : "Обновить расписание",
                this::loadSlots
        );
        refresh.setEnabled(!busyCart() && !loadingSlots);
        parent.addView(refresh);

        parent.addView(text(
                "Если подходящего интервала нет, время можно " +
                "согласовать с магазином. Выбор интервала не заменяет " +
                "подтверждение продавца. Для других городов " +
                "возможность доставки уточняется отдельно.",
                12, MUTED
        ));
    }

    private void chooseDate() {
        if (busyCart() || !slotsLoaded || loadingSlots) return;
        saveProfile();

        Set<String> unique = new LinkedHashSet<>();
        for (Slot slot : slots) {
            if (slot.fulfillment.equals(fulfillment())) {
                unique.add(slot.date);
            }
        }

        if (unique.isEmpty()) {
            message(
                    "Нет доступных интервалов",
                    "Для выбранного способа получения пока нет " +
                    "открытых интервалов. Можно обновить расписание " +
                    "или согласовать время с магазином."
            );
            return;
        }

        List<String> dates = new ArrayList<>(unique);
        String[] labels = new String[dates.size()];
        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = LocalDate.parse(dates.get(i));
            labels[i] = String.format(
                    Locale.ROOT, "%02d.%02d.%04d",
                    date.getDayOfMonth(), date.getMonthValue(),
                    date.getYear()
            );
        }

        AlertDialog dialog = dialogBuilder()
                .setTitle("Выберите дату")
                .setItems(labels, (d, which) -> chooseTime(dates.get(which)))
                .setNegativeButton("Отмена", null)
                .create();
        dialog.show();
        decorate(dialog);
    }

    private void chooseTime(String date) {
        if (busyCart() || destroyed) return;

        List<Slot> available = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.date.equals(date) &&
                    slot.fulfillment.equals(fulfillment())) {
                available.add(slot);
            }
        }

        String[] labels = new String[available.size()];
        for (int i = 0; i < available.size(); i++) {
            Slot slot = available.get(i);
            labels[i] = slot.start + "–" + slot.end;
        }

        AlertDialog dialog = dialogBuilder()
                .setTitle("Время — UTC+3")
                .setItems(labels, (d, which) -> {
                    if (busyCart()) return;
                    saveProfile();
                    selectedSlotSnapshot = available.get(which);
                    selectedSlotId = selectedSlotSnapshot.id;
                    render(true);
                })
                .setNegativeButton("Отмена", null)
                .create();
        dialog.show();
        decorate(dialog);
    }

    private String slotText(JSONObject order) {
        JSONObject value = order.optJSONObject("receivingSlot");
        if (value != null) {
            try {
                Slot slot = parseSlot(value);
                if (!slot.fulfillment.equals(order.optString("fulfillment"))) {
                    throw new Exception("Другой способ получения");
                }
                return "Дата и время: " + slot.label() +
                        "\nМосковское время, UTC+3.";
            } catch (Exception ignored) {
                return "Дата и время: уточните у магазина.";
            }
        }
        return "Дата и время: по согласованию с магазином.";
    }

    // ==================== КОРЗИНА ====================

    private void showCart() {
        section("Корзина");

        if (pending != null) {
            LinearLayout notice = panel();
            notice.addView(bold("Проверим прежний заказ", 20, GOLD));
            notice.addView(text(
                    "Результат прошлой отправки не подтверждён. " +
                    "Проверка использует прежний запрос " +
                    "без изменения идентификатора и содержимого.",
                    14, WHITE
            ));
            Button retry = button(
                    sending ? "Проверяем…" : "Проверить прежний заказ",
                    this::confirmPending
            );
            retry.setEnabled(!sending && !preparing && repeatingId == null);
            notice.addView(retry);
        }

        if (cart.isEmpty()) {
            emptyState(
                    3, "Ваша корзина пока пуста",
                    "Добавьте что-нибудь вкусное из каталога.",
                    "Выбрать товары", () -> openCatalog(false)
            );
            return;
        }

        for (String id : new ArrayList<>(cart.keySet())) {
            Product p = findProduct(id);
            LinearLayout item = panel();

            if (p == null) {
                item.addView(text(
                        "Товар временно недоступен в каталоге.",
                        15, GOLD
                ));
                if (!busyCart()) {
                    item.addView(secondary(
                            "Убрать недоступный товар",
                            () -> {
                                if (busyCart()) return;
                                cart.remove(id);
                                saveCart();
                                redraw();
                            }
                    ));
                }
                continue;
            }

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(
                    new ProductPhoto(this, p.name, p.category, p.imageUrl),
                    new LinearLayout.LayoutParams(dp(88), dp(88))
            );

            LinearLayout description = new LinearLayout(this);
            description.setOrientation(LinearLayout.VERTICAL);
            description.setPadding(dp(12), 0, 0, 0);
            description.addView(bold(p.name, 17, WHITE));
            description.addView(text(p.category, 11, MUTED));
            row.addView(
                    description, new LinearLayout.LayoutParams(0, -2, 1)
            );
            item.addView(row);

            badge(item, p);
            prices(item, p, 17);
            if (!kg(p) && halves(id) % 2 != 0) {
                item.addView(text(
                        "Единица продажи изменилась. Исправьте " +
                        "количество кнопкой «+» или «−».",
                        13, GOLD
                ));
            }
            quantityControls(item, p);
            item.addView(bold(
                    "За позицию: " + money(lineTotal(p)), 17, WHITE
            ));
        }

        LinearLayout summary = panel();
        summary.addView(text("ВАШ ЗАКАЗ", 11, MUTED));
        summary.addView(bold(money(total()), 32, GOLD));
        summary.addView(text(
                "Сумма товаров · акционные цены уже учтены",
                12, MUTED
        ));
        summary.addView(text(DELIVERY, 13, MUTED));
        summary.addView(text("Оплата при получении.", 13, WHITE));

        if (pending != null) return;

        LinearLayout details = panel();
        details.addView(bold("Получение и контакты", 21, WHITE));
        customerFields(details, true);

        Button send = button(
                sending ? "Отправляем…" :
                preparing ? "Проверяем…" :
                "Оформить · " + money(total()),
                this::prepareOrder
        );
        send.setEnabled(
                freshCatalog && !loadingCatalog && !busyCart()
        );
        details.addView(send);
        details.addView(text(
                "Перед отправкой покажем данные для подтверждения.",
                12, MUTED
        ));

        Button clear = secondary("Очистить корзину", () -> {
            if (busyCart()) return;
            AlertDialog dialog = dialogBuilder()
                    .setTitle("Очистить корзину?")
                    .setNegativeButton("Оставить", null)
                    .setPositiveButton("Очистить", (d, which) -> {
                        if (busyCart()) return;
                        saveProfile();
                        cart.clear();
                        saveCart();
                        render(false);
                    })
                    .create();
            dialog.show();
            decorate(dialog);
        });
        clear.setEnabled(!busyCart());
        content.addView(clear);
    }

    // ==================== ПРОФИЛЬ ====================

    private void showProfile() {
        section("Мой профиль");

        LinearLayout greeting = panel();
        greeting.addView(bold(
                customerName.isEmpty()
                        ? "Рады видеть вас!"
                        : "Здравствуйте, " + customerName + "!",
                24, WHITE
        ));
        greeting.addView(text(
                "Ваши покупки, контакты и любимые товары.",
                13, MUTED
        ));

        LinearLayout orders = panel();
        orders.addView(bold("Мои заказы", 21, GOLD));
        orders.addView(text(
                historyReadable
                        ? "На этом телефоне: " + history.length()
                        : "Не удалось прочитать локальную историю.",
                14, WHITE
        ));
        orders.addView(button("Открыть историю  →", this::openHistory));
        orders.addView(text(
                "После удаления приложения история и ключи " +
                "просмотра заказов не восстановятся.",
                12, MUTED
        ));

        String last = prefs.getString("last_order_v2", "");
        if (!last.isEmpty()) {
            orders.addView(bold("Последний заказ", 16, WHITE));
            orders.addView(text(last, 14, MUTED));
        }
        if (pending != null) {
            orders.addView(button(
                    "Проверить неподтверждённый заказ",
                    () -> openTab(3)
            ));
        }

        LinearLayout profile = panel();
        profile.addView(bold("Данные покупателя", 21, WHITE));
        profile.addView(text(
                "Хранятся на этом телефоне. " +
                "Регистрация на сервере не используется.",
                12, MUTED
        ));
        customerFields(profile, false);
        profile.addView(button("Сохранить данные", () -> {
            saveProfile();
            message(
                    "Данные сохранены",
                    "Они будут доступны при оформлении."
            );
        }));

        ShopContacts.addTo(this, content);

        LinearLayout conditions = panel();
        
        conditions.addView(text(
                "Даты и интервалы — по московскому времени. " +
                "Детали заказа подтверждает продавец.",
                13, MUTED
        ));
    }

    // ==================== ИСТОРИЯ ====================

    private String statusName(String value) {
        switch (value) {
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
        return number > 0 ? "Заказ № " + number : "Заказ сохранён";
    }

    private String dateText(String value) {
        try {
            return DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT,
                    new Locale("ru", "RU")
            ).format(Date.from(Instant.parse(value)));
        } catch (Exception error) {
            return "Дата уточняется";
        }
    }

    private JSONObject historyOrder(String id) {
        for (int i = 0; i < history.length(); i++) {
            JSONObject value = history.optJSONObject(i);
            if (value != null && id.equals(value.optString("id"))) {
                return value;
            }
        }
        return null;
    }

    private JSONArray historyWith(JSONObject replacement, boolean first)
            throws Exception {
        if (!historyReadable) throw new Exception("История не прочитана");

        String id = replacement.getString("id");
        JSONArray result = new JSONArray();
        boolean found = false;

        if (first) {
            result.put(replacement);
            found = true;
        }
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.getJSONObject(i);
            if (id.equals(item.getString("id"))) {
                if (!first) {
                    result.put(replacement);
                    found = true;
                }
            } else {
                result.put(item);
            }
        }
        if (!found) result.put(replacement);
        return result;
    }

    private boolean canTrack(JSONObject order) {
        return order.optBoolean("trackingAvailable", false) &&
                order.optString("id").matches(ORDER_PATTERN) &&
                order.optString("orderAccessToken")
                        .matches("[0-9a-f]{64}");
    }

    private void showHistory() {
        content.addView(secondary(
                "← Назад в профиль", () -> openTab(4)
        ));
        section("Мои заказы");

        LinearLayout notice = panel();
        notice.addView(text(
                "Обновление статуса не создаёт заказ. Повтор " +
                "переносит товары в корзину по текущим ценам.",
                13, MUTED
        ));

        if (!historyReadable) {
            notice.addView(text(
                    "История не прочитана и не перезаписана. " +
                    "Не удаляйте приложение. Свяжитесь с магазином.",
                    14, GOLD
            ));
            return;
        }
        if (pending != null) {
            notice.addView(button(
                    "Проверить прежнюю отправку", () -> openTab(3)
            ));
        }
        if (history.length() == 0) {
            emptyState(
                    3, "Здесь будут ваши заказы",
                    "Записи появятся после подтверждения сервера.",
                    "Перейти к покупкам", () -> openCatalog(false)
            );
            return;
        }

        for (int i = 0; i < history.length(); i++) {
            JSONObject order = history.optJSONObject(i);
            if (order == null) continue;
            final String id = order.optString("id");

            LinearLayout card = panel();
            card.addView(bold(orderTitle(order), 23, WHITE));
            card.addView(text(
                    (order.optBoolean("serverSnapshot", false)
                            ? "Оформлен: " : "Подтверждён на телефоне: ") +
                    dateText(order.optString("createdAt")),
                    12, MUTED
            ));

            TextView status = bold(
                    statusName(order.optString("status")), 14, GOLD
            );
            status.setPadding(dp(12), dp(8), dp(12), dp(8));
            status.setBackground(
                    surface(Color.rgb(48, 43, 26), 0, 10)
            );
            card.addView(
                    status, new LinearLayout.LayoutParams(-2, -2)
            );

            long checked = order.optLong("checkedAt", 0);
            if (checked > 0) {
                card.addView(text(
                        "Последняя проверка: " +
                        dateText(Instant.ofEpochMilli(checked).toString()),
                        11, MUTED
                ));
            }

            card.addView(text(receivingDescription(order), 14, MUTED));
            card.addView(text(slotText(order), 14, GOLD));

            JSONArray items = order.optJSONArray("items");
            if (items != null) {
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item == null) continue;

                    double q = item.optDouble("quantity", 0);
                    String line = item.optString("name", "Товар");
                    if (Double.isFinite(q) && q > 0) {
                        line += " — " + quantityFormat.format(q) +
                                " " + item.optString("unit");
                    }
                    if (order.optBoolean("serverSnapshot", false)) {
                        try {
                            line += " — " + money(responseKopecks(item));
                        } catch (Exception ignored) {
                        }
                    }
                    card.addView(text(line, 14, WHITE));
                }
            }

            card.addView(bold(
                    "Товары: " + orderMoney(order), 22, GOLD
            ));

            JSONObject info = order.optJSONObject("deliveryInfo");
            String terms = info == null ? "" : str(info, "message");
            card.addView(text(
                    terms.isEmpty()
                            ? "Условия доставки относятся к оформленному " +
                              "заказу и согласуются с магазином."
                            : terms,
                    12, MUTED
            ));

            if (!order.optBoolean("serverSnapshot", false)) {
                card.addView(text(
                        "Обновите статус для получения сохранённого " +
                        "магазином состава и времени.",
                        12, MUTED
                ));
            }

            if (canTrack(order)) {
                Button refresh = button(
                        refreshing.contains(id)
                                ? "Обновляем…" : "Обновить статус",
                        () -> refreshOrder(id)
                );
                refresh.setEnabled(
                        !refreshing.contains(id) &&
                        repeatingId == null && !preparing
                );
                card.addView(refresh);
            } else {
                card.addView(text(
                        "Нет ключа просмотра. Уточните статус у магазина.",
                        12, GOLD
                ));
            }

            Button repeat = secondary(
                    id.equals(repeatingId)
                            ? "Подготавливаем…" : "Повторить заказ",
                    () -> repeatOrder(id)
            );
            repeat.setEnabled(
                    !busyCart() && !loadingCatalog && refreshing.isEmpty()
            );
            card.addView(repeat);
        }
    }

    private String receivingDescription(JSONObject order) {
        if ("pickup".equals(order.optString("fulfillment"))) {
            return "Самовывоз:\n" + PICKUP;
        }
        String selectedCity = str(order, "deliveryCity");
        return "Доставка:\n" +
                (selectedCity.isEmpty()
                        ? "" : "Город: " + selectedCity + "\n") +
                str(order, "address");
    }

    private void validateItems(JSONArray items) throws Exception {
        if (items.length() < 1 || items.length() > 100) {
            throw new Exception("Некорректный состав");
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String id = item.getString("productId");
            double q = item.getDouble("quantity");
            if (!id.matches("[a-z0-9_-]{1,80}") ||
                    !ids.add(id) ||
                    !Double.isFinite(q) || q < 0.5 || q > 50 ||
                    q * 2 != Math.rint(q * 2) ||
                    item.getString("name").trim().isEmpty() ||
                    item.getString("unit").trim().isEmpty()) {
                throw new Exception("Некорректная позиция");
            }
            responseKopecks(item);
        }
    }

    private void copyReceivingSlot(
            JSONObject destination, JSONObject source
    ) throws Exception {
        if (!source.has("receivingSlot")) return;
        if (source.isNull("receivingSlot")) {
            destination.put("receivingSlot", JSONObject.NULL);
            return;
        }
        Slot slot = parseSlot(source.getJSONObject("receivingSlot"));
        if (!slot.fulfillment.equals(
                destination.optString("fulfillment"))) {
            throw new Exception("Несовпадение способа получения");
        }
        destination.put("receivingSlot", slot.json());
    }

    private JSONObject storeRemote(String id, JSONObject data)
            throws Exception {
        JSONObject remote = data.getJSONObject("order");
        if (!id.equals(remote.getString("id"))) {
            throw new Exception("Другой заказ");
        }
        responseKopecks(remote);
        String status = remote.getString("status");
        String method = remote.getString("fulfillment");
        if (!knownStatus(status) ||
                !Arrays.asList("delivery", "pickup").contains(method)) {
            throw new Exception("Некорректный ответ");
        }

        JSONArray items = remote.getJSONArray("items");
        validateItems(items);
        JSONObject current = historyOrder(id);
        if (current == null) throw new Exception("Нет записи");

        JSONObject updated = new JSONObject(current.toString());
        updated.put("orderNumber", remote.getLong("orderNumber"));
        updated.put("createdAt", remote.getString("createdAt"));
        updated.put("updatedAt", remote.optString("updatedAt"));
        updated.put("fulfillment", method);
        updated.put("items", items);
        updated.put("totalRub", remote.get("totalRub"));
        updated.put("status", status);
        updated.put("checkedAt", System.currentTimeMillis());
        updated.put("serverSnapshot", true);

        if (remote.has("deliveryCity")) {
            updated.put("deliveryCity", str(remote, "deliveryCity"));
        }
        if (remote.has("address")) {
            updated.put("address", str(remote, "address"));
        }
        if (remote.optJSONObject("deliveryInfo") != null) {
            updated.put(
                    "deliveryInfo", remote.getJSONObject("deliveryInfo")
            );
        }
        copyReceivingSlot(updated, remote);

        JSONArray next = historyWith(updated, false);
        if (!prefs.edit().putString(
                HISTORY_KEY, next.toString()
        ).commit()) {
            throw new Exception("Не удалось сохранить историю");
        }
        history = next;
        return updated;
    }

    private void refreshOrder(String id) {
        if (destroyed || refreshing.contains(id) ||
                repeatingId != null || preparing) return;

        JSONObject local = historyOrder(id);
        if (local == null || !canTrack(local)) {
            message("Статус", "Нет ключа просмотра этого заказа.");
            return;
        }

        refreshing.add(id);
        redraw();

        api.loadOrder(
                id, local.optString("orderAccessToken"),
                new ShopApi.Callback() {
                    @Override
                    public void onSuccess(JSONObject data) {
                        if (destroyed) return;
                        refreshing.remove(id);
                        try {
                            JSONObject updated = storeRemote(id, data);
                            redraw();
                            Toast.makeText(
                                    StoreActivity.this,
                                    "Статус: " +
                                    statusName(updated.optString("status")),
                                    Toast.LENGTH_SHORT
                            ).show();
                        } catch (Exception error) {
                            redraw();
                            message(
                                    "История",
                                    "Не удалось прочитать или сохранить " +
                                    "ответ. Прежняя запись оставлена."
                            );
                        }
                    }

                    @Override
                    public void onError(ShopApi.ApiError error) {
                        if (destroyed) return;
                        refreshing.remove(id);
                        redraw();
                        message("Проверка статуса", error.getMessage());
                    }
                }
        );
    }

    // ==================== ПОВТОР ЗАКАЗА ====================

    private void repeatOrder(String id) {
        if (busyCart() || loadingCatalog || !refreshing.isEmpty()) {
            message(
                    "Повтор заказа",
                    "Сначала завершите текущую проверку " +
                    "или проверьте прежнюю отправку."
            );
            return;
        }

        JSONObject local = historyOrder(id);
        if (!historyReadable || local == null) return;

        if (!local.optBoolean("serverSnapshot", false) &&
                !canTrack(local)) {
            message(
                    "Повтор заказа",
                    "Нет подтверждённого состава и ключа просмотра. " +
                    "Выберите товары вручную. Корзина не изменена."
            );
            return;
        }

        saveProfile();
        repeatingId = id;
        render(true);

        if (local.optBoolean("serverSnapshot", false)) {
            fetchRepeatCatalog(id);
            return;
        }

        api.loadOrder(
                id, local.optString("orderAccessToken"),
                new ShopApi.Callback() {
                    @Override
                    public void onSuccess(JSONObject data) {
                        if (destroyed || !id.equals(repeatingId)) return;
                        try {
                            storeRemote(id, data);
                            fetchRepeatCatalog(id);
                        } catch (Exception error) {
                            failRepeat("Не удалось получить состав заказа.");
                        }
                    }

                    @Override
                    public void onError(ShopApi.ApiError error) {
                        if (destroyed || !id.equals(repeatingId)) return;
                        failRepeat(error.getMessage());
                    }
                }
        );
    }

    private void fetchRepeatCatalog(String id) {
        loadingCatalog = true;
        freshCatalog = false;
        redraw();

        api.loadProducts(new ShopApi.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed || !id.equals(repeatingId)) return;
                try {
                    List<Product> loaded = parseCatalog(data);
                    saveProfile();
                    products.clear();
                    products.addAll(loaded);
                    loadingCatalog = false;
                    freshCatalog = true;

                    JSONObject source = historyOrder(id);
                    if (source == null) throw new Exception("Нет заказа");
                    RepeatPlan plan = buildRepeat(source);

                    if (plan.quantities.isEmpty()) {
                        failRepeat(
                                "Нет доступных товаров для переноса.\n\n" +
                                join(plan.skipped)
                        );
                        return;
                    }
                    render(true);
                    confirmRepeat(id, source, plan);
                } catch (Exception error) {
                    loadingCatalog = false;
                    failRepeat("Не удалось подготовить повтор заказа.");
                }
            }

            @Override
            public void onError(ShopApi.ApiError error) {
                if (destroyed || !id.equals(repeatingId)) return;
                loadingCatalog = false;
                freshCatalog = false;
                failRepeat(error.getMessage());
            }
        });
    }

    private RepeatPlan buildRepeat(JSONObject source) throws Exception {
        JSONArray items = source.getJSONArray("items");
        validateItems(items);
        RepeatPlan result = new RepeatPlan();

        for (int i = 0; i < items.length(); i++) {
            JSONObject old = items.getJSONObject(i);
            Product p = findProduct(old.getString("productId"));
            String name = old.getString("name");
            String oldUnit = unit(old.getString("unit"));
            int value = (int) Math.round(old.getDouble("quantity") * 2);

            if (p == null) {
                result.skipped.add(name + " — сейчас недоступен.");
                continue;
            }
            if (!supportedUnit(oldUnit) ||
                    !supportedUnit(p.unit) ||
                    !oldUnit.equals(unit(p.unit)) ||
                    (!kg(p) && value % 2 != 0)) {
                result.skipped.add(
                        name + " — изменилась единица продажи " +
                        "или количество не подходит."
                );
                continue;
            }

            long line = (long) p.price * 50 * value;
            result.total += line;
            result.quantities.put(p.id, value);
            result.included.add(
                    p.name + " — " + quantity(p, value) +
                    " — " + money(line)
            );
        }

        if (result.total > MAX_TOTAL) {
            throw new Exception("Слишком большая сумма");
        }
        return result;
    }

    private String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append("\n");
            result.append("• ").append(value);
        }
        return result.toString();
    }

    private void failRepeat(String reason) {
        repeatingId = null;
        if (destroyed) return;
        redraw();
        message(
                "Повтор заказа",
                reason + "\n\nКорзина не заменена. Новый заказ не отправлен."
        );
    }

    private void confirmRepeat(
            String id, JSONObject source, RepeatPlan plan
    ) {
        if (destroyed || isFinishing()) {
            repeatingId = null;
            return;
        }

        String body = "Товары из " + orderTitle(source) + ":\n\n" +
                join(plan.included) +
                "\n\nПо текущим ценам: " + money(plan.total) +
                (plan.skipped.isEmpty()
                        ? "" : "\n\nНе будут добавлены:\n" + join(plan.skipped)) +
                (cart.isEmpty()
                        ? "\n\nТовары будут добавлены в корзину."
                        : "\n\nВНИМАНИЕ: нынешняя корзина будет заменена.") +
                "\n\nГород и данные покупателя сохранятся. " +
                "Дату и время нужно выбрать заново. " +
                "Это не отправка заказа.";

        AlertDialog dialog = dialogBuilder()
                .setTitle("Повторить заказ?")
                .setMessage(body)
                .setNegativeButton("Отмена", null)
                .setPositiveButton(
                        cart.isEmpty()
                                ? "Добавить в корзину"
                                : "Заменить корзину",
                        null
                )
                .create();

        dialog.setOnDismissListener(d -> {
            if (id.equals(repeatingId)) {
                repeatingId = null;
                if (!destroyed) redraw();
            }
        });

        dialog.show();
        decorate(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (destroyed || !id.equals(repeatingId) ||
                            sending || pending != null || preparing) return;

                    try {
                        if (!prefs.edit().putString(
                                "cart_v2", cartJson(plan.quantities).toString()
                        ).commit()) {
                            throw new Exception("Ошибка сохранения");
                        }

                        saveProfile();
                        cart.clear();
                        cart.putAll(plan.quantities);
                        repeatingId = null;
                        consent = false;
                        customerComment = "";
                        clearSelectedSlot();
                        tab = 3;
                        showingHistory = false;
                        dialog.dismiss();
                        render(false);
                    } catch (Exception error) {
                        message(
                                "Корзина",
                                "Не удалось сохранить корзину. " +
                                "Заказ не отправлен."
                        );
                    }
                });
    }

    // ==================== КАТАЛОГ С СЕРВЕРА ====================

    private int price(JSONObject item, String key) throws Exception {
        int result = new BigDecimal(
                item.get(key).toString()
        ).intValueExact();
        if (result < 1 || result > 1000000) {
            throw new Exception("Некорректная цена");
        }
        return result;
    }

    private List<Product> parseCatalog(JSONObject data) throws Exception {
        JSONArray array = data.getJSONArray("products");
        List<Product> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (!item.optBoolean("available", true)) continue;

            Product p = new Product();
            p.id = str(item, "id");
            p.name = str(item, "name");
            p.category = str(item, "category");
            p.description = str(item, "description");
            p.unit = str(item, "unit");
            p.imageUrl = "";

            for (String key : new String[]{
                    "imageUrl", "image_url",
                    "photoUrl", "photo_url", "image"
            }) {
                String url = str(item, key);
                if (url.startsWith("https://")) {
                    p.imageUrl = url;
                    break;
                }
            }

            p.price = price(item, "priceRub");
            p.oldPrice = 0;
            if (item.has("oldPriceRub") && !item.isNull("oldPriceRub")) {
                try {
                    int old = price(item, "oldPriceRub");
                    p.oldPrice = old > p.price ? old : 0;
                } catch (Exception ignored) {
                }
            }

            if (p.category.isEmpty()) p.category = "Каталог";
            if (p.unit.isEmpty()) p.unit = "кг";
            if (!p.id.matches("[a-z0-9_-]{1,80}") ||
                    p.name.isEmpty() || !ids.add(p.id)) {
                throw new Exception("Некорректный товар");
            }
            result.add(p);
        }
        return result;
    }

    private void loadCatalog() {
        if (loadingCatalog || destroyed ||
                preparing || repeatingId != null) return;

        saveProfile();
        loadingCatalog = true;
        render(true);

        api.loadProducts(new ShopApi.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;
                saveProfile();
                try {
                    List<Product> loaded = parseCatalog(data);
                    products.clear();
                    products.addAll(loaded);
                    freshCatalog = true;
                    loadingCatalog = false;
                    render(true);
                } catch (Exception error) {
                    loadingCatalog = false;
                    freshCatalog = false;
                    render(true);
                    message(
                            "Каталог",
                            "Не удалось прочитать товары. Повторите обновление."
                    );
                }
            }

            @Override
            public void onError(ShopApi.ApiError error) {
                if (destroyed) return;
                saveProfile();
                loadingCatalog = false;
                freshCatalog = false;
                render(true);
                message("Каталог", error.getMessage());
            }
        });
    }

    // ==================== ПОДГОТОВКА ЗАКАЗА ====================

    private String createToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[64];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 15];
        }
        return new String(result);
    }

    private void prepareOrder() {
        if (busyCart()) return;
        saveProfile();

        if (!historyReadable) {
            message(
                    "История",
                    "История не прочитана. Чтобы не потерять записи, " +
                    "отправка остановлена. Не удаляйте приложение."
            );
            return;
        }

        String phone = customerPhone.replaceAll("[^0-9]", "");

        if (!freshCatalog || loadingCatalog || cart.isEmpty()) {
            message("Заказ", "Обновите каталог и выберите товары.");
            return;
        }
        if (cart.size() > 100) {
            message("Заказ", "Допускается до 100 разных товаров.");
            return;
        }
        if (customerName.length() < 2 || customerName.length() > 80) {
            message("Имя", "Введите имя от 2 до 80 символов.");
            return;
        }
        if (!phone.matches("[78][0-9]{10}")) {
            message("Телефон", "Пример: +7 949 123-45-67.");
            return;
        }
        if (delivery && !knownCity(city)) {
            message("Город", "Выберите город доставки.");
            return;
        }

        String destination = actualCity();

        if (delivery &&
                (destination.length() < 2 ||
                 destination.length() > 80 ||
                 destination.matches(".*[\\p{Cntrl}].*") ||
                 Arrays.asList("другой город", "другой", "other")
                         .contains(destination.toLowerCase(Locale.ROOT)))) {
            message(
                    "Название города",
                    "Укажите настоящее название вашего города " +
                    "в отдельном поле."
            );
            return;
        }
        if (delivery &&
                (customerAddress.length() < 5 ||
                 customerAddress.length() > 250)) {
            message("Адрес", "Введите полный адрес от 5 до 250 символов.");
            return;
        }
        if (customerComment.length() > 300) {
            message("Комментарий", "Допускается до 300 символов.");
            return;
        }
        if (!consent) {
            message("Согласие", "Подтвердите согласие на обработку заказа.");
            return;
        }

        try {
            JSONArray items = new JSONArray();
            for (String id : new TreeSet<>(cart.keySet())) {
                Product p = findProduct(id);
                int q = halves(id);
                if (p == null || !supportedUnit(p.unit) ||
                        q < 1 || q > 100 || (!kg(p) && q % 2 != 0)) {
                    message(
                            "Проверьте корзину",
                            "Один из товаров недоступен либо его " +
                            "количество не соответствует единице продажи."
                    );
                    return;
                }
                JSONObject item = new JSONObject();
                item.put("productId", id);
                item.put("quantity", q / 2.0);
                items.put(item);
            }

            long sum = total();
            if (sum <= 0 || sum > MAX_TOTAL) {
                throw new Exception("Некорректная сумма");
            }

            JSONObject order = new JSONObject();
            order.put("requestId", UUID.randomUUID().toString());
            order.put("name", customerName);
            order.put("phone", phone);
            order.put("fulfillment", fulfillment());
            order.put("address", delivery ? customerAddress : "");
            if (delivery) order.put("deliveryCity", destination);
            order.put("comment", customerComment);
            order.put("items", items);
            order.put("orderAccessToken", createToken());

            if (selectedSlotId.isEmpty()) {
                confirmNewOrder(order, sum, null);
            } else {
                Slot chosen = selectedSlotSnapshot;
                if (chosen == null ||
                        !chosen.fulfillment.equals(fulfillment())) {
                    clearSelectedSlot();
                    redraw();
                    message("Время", "Выберите дату и время заново.");
                    return;
                }
                order.put("receivingSlotId", chosen.id);
                verifySlotBeforeConfirm(order, sum, chosen);
            }
        } catch (Exception error) {
            message("Заказ", "Не удалось подготовить заказ.");
        }
    }

    private void verifySlotBeforeConfirm(
            JSONObject order, long sum, Slot chosen
    ) {
        preparing = true;
        redraw();

        api.loadReceivingSlots(new ShopApi.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;
                saveProfile();
                preparing = false;

                try {
                    List<Slot> loaded = parseSlots(data);
                    Slot latest = null;
                    for (Slot slot : loaded) {
                        if (slot.id.equals(chosen.id)) latest = slot;
                    }

                    replaceSlots(loaded);
                    render(true);

                    if (!chosen.same(latest)) {
                        clearSelectedSlot();
                        render(true);
                        message(
                                "Расписание изменилось",
                                "Выбранный интервал закрыт или изменён. " +
                                "Выберите время заново. Заказ не отправлен."
                        );
                        return;
                    }
                    confirmNewOrder(order, sum, latest);
                } catch (Exception error) {
                    slotsLoaded = false;
                    render(true);
                    message(
                            "Расписание",
                            "Не удалось проверить интервал. " +
                            "Заказ не отправлен. Обновите расписание."
                    );
                }
            }

            @Override
            public void onError(ShopApi.ApiError error) {
                if (destroyed) return;
                saveProfile();
                preparing = false;
                render(true);
                message(
                        "Расписание",
                        error.getMessage() + "\n\nЗаказ не отправлен."
                );
            }
        });
    }

    private void confirmNewOrder(JSONObject order, long sum, Slot chosen) {
        if (destroyed || isFinishing()) return;
        preparing = true;
        redraw();

        String time = chosen == null
                ? "Дата и время: по согласованию с магазином."
                : "Дата и время: " + chosen.label() +
                  "\nМосковское время, UTC+3.";

        String shipping = "delivery".equals(order.optString("fulfillment"))
                ? deliveryHint(order.optString("deliveryCity"), sum)
                : "Самовывоз из магазина.";

        AlertDialog dialog = dialogBuilder()
                .setTitle("Подтвердите заказ")
                .setMessage(
                        "Товары: " + money(sum) +
                        "\n\n" + receivingDescription(order) +
                        "\n\n" + time +
                        "\n\n" + shipping +
                        "\n\nОплата при получении. " +
                        "Сервер проверит актуальные цены и интервал. " +
                        "Доставку и время подтверждает магазин. " +
                        "Платная доставка в сумму товаров не включена."
                )
                .setNegativeButton("Назад", null)
                .setPositiveButton("Отправить", null)
                .create();

        dialog.setOnDismissListener(d -> {
            preparing = false;
            if (!destroyed) redraw();
        });

        dialog.show();
        decorate(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (sending || pending != null || repeatingId != null) {
                        return;
                    }
                    String payload = order.toString();
                    if (!prefs.edit().putString(
                            "pending_order", payload
                    ).commit()) {
                        message(
                                "Заказ",
                                "Не удалось сохранить запрос на телефоне. " +
                                "Отправка не выполнена."
                        );
                        return;
                    }
                    pending = payload;
                    dialog.dismiss();
                    sendPending();
                });
    }

    // ==================== ПРЕЖНИЕ ЗАПРОСЫ ====================

    private void confirmPending() {
        if (sending || preparing ||
                pending == null || repeatingId != null) return;

        try {
            JSONObject order = new JSONObject(pending);
            AlertDialog dialog = dialogBuilder()
                    .setTitle("Проверить прежний заказ?")
                    .setMessage(
                            order.optString("name") + "\n" +
                            order.optString("phone") +
                            "\n\n" + receivingDescription(order) +
                            "\n\nИспользуются прежний идентификатор " +
                            "и прежнее содержимое, включая выбранный " +
                            "ранее интервал. Уже сохранённый заказ " +
                            "не создаётся повторно."
                    )
                    .setNegativeButton("Назад", null)
                    .setPositiveButton(
                            "Проверить", (d, which) -> sendPending()
                    )
                    .create();
            dialog.show();
            decorate(dialog);
        } catch (Exception error) {
            message(
                    "Прежний заказ",
                    "Не удалось прочитать запрос. Свяжитесь с магазином. " +
                    "Не удаляйте приложение."
            );
        }
    }

    private JSONObject initialEntry(
            JSONObject submitted, JSONObject response, long confirmed
    ) throws Exception {
        String id = response.getString("id");
        JSONObject previous = historyOrder(id);
        if (previous != null) return new JSONObject(previous.toString());

        JSONObject entry = new JSONObject();
        String token = submitted.optString("orderAccessToken", "");
        boolean trackable = token.matches("[0-9a-f]{64}") &&
                response.optBoolean("orderTrackingAvailable", false);

        entry.put("id", id);
        entry.put("orderNumber", response.optLong("orderNumber", 0));
        entry.put(
                "totalRub", BigDecimal.valueOf(confirmed, 2).toPlainString()
        );

        String status = response.optString("status", "new");
        entry.put("status", knownStatus(status) ? status : "new");
        entry.put("createdAt", Instant.now().toString());
        entry.put("checkedAt", System.currentTimeMillis());
        entry.put("fulfillment", submitted.optString("fulfillment"));
        entry.put("deliveryCity", str(submitted, "deliveryCity"));
        entry.put("address", str(submitted, "address"));
        entry.put("trackingAvailable", trackable);
        entry.put("serverSnapshot", false);
        if (trackable) entry.put("orderAccessToken", token);

        if (response.optJSONObject("deliveryInfo") != null) {
            entry.put(
                    "deliveryInfo", response.getJSONObject("deliveryInfo")
            );
        }

        copyReceivingSlot(entry, response);

        JSONArray summary = new JSONArray();
        JSONArray requested = submitted.getJSONArray("items");
        for (int i = 0; i < requested.length(); i++) {
            JSONObject item = requested.getJSONObject(i);
            String productId = item.getString("productId");
            Product p = findProduct(productId);
            JSONObject saved = new JSONObject();
            saved.put("productId", productId);
            saved.put("quantity", item.get("quantity"));
            saved.put("name", p == null ? "Товар " + productId : p.name);
            saved.put("unit", p == null ? "" : p.unit);
            summary.put(saved);
        }
        entry.put("items", summary);
        return entry;
    }

    private void sendPending() {
        if (destroyed || sending || preparing ||
                pending == null || repeatingId != null) return;

        final JSONObject order;
        try {
            order = new JSONObject(pending);
        } catch (Exception error) {
            message("Заказ", "Не удалось прочитать сохранённый запрос.");
            return;
        }

        saveProfile();
        sending = true;
        render(true);

        // Старый запрос не изменяем: ID, ключ и интервал остаются прежними.
        api.sendOrder(order, new ShopApi.Callback() {
            @Override
            public void onSuccess(JSONObject data) {
                if (destroyed) return;

                final long confirmed;
                final JSONArray next;
                final String receipt;

                try {
                    String id = data.getString("id");
                    if (!id.matches(ORDER_PATTERN)) {
                        throw new Exception("Некорректный ID");
                    }

                    confirmed = responseKopecks(data);
                    JSONObject entry = initialEntry(order, data, confirmed);
                    next = historyWith(entry, true);
                    receipt = orderTitle(entry) +
                            "\nТовары: " + money(confirmed) +
                            "\n" + slotText(entry);
                } catch (Exception error) {
                    sending = false;
                    redraw();
                    message(
                            "Результат пока не подтверждён",
                            "Не удалось прочитать ответ или подготовить " +
                            "историю. Сохранённый запрос оставлен. " +
                            "Используйте проверку прежнего заказа, " +
                            "не оформляйте новый."
                    );
                    return;
                }

                boolean saved = prefs.edit()
                        .putString(HISTORY_KEY, next.toString())
                        .remove("pending_order")
                        .putString("last_order_v2", receipt)
                        .putString("cart_v2", "{}")
                        .commit();

                sending = false;
                if (!saved) {
                    redraw();
                    message(
                            "Заказ сохранён магазином",
                            receipt + "\n\nНе удалось обновить данные " +
                            "телефона. Не оформляйте заказ заново."
                    );
                    return;
                }

                saveProfile();
                history = next;
                pending = null;
                cart.clear();
                consent = false;
                customerComment = "";
                clearSelectedSlot();
                tab = 4;
                showingHistory = true;
                render(false);

                message(
                        "Спасибо за заказ!",
                        receipt +
                        "\n\nЗаказ сохранён в магазине. Ожидайте " +
                        "подтверждения продавца. Оплата при получении. " +
                        "Для актуального состава нажмите " +
                        "«Обновить статус»."
                );
            }

            @Override
            public void onError(ShopApi.ApiError error) {
                if (destroyed) return;
                saveProfile();
                sending = false;

                if (error.shouldClearPendingOrder() &&
                        error.statusCode != 409) {
                    if (prefs.edit().remove("pending_order").commit()) {
                        pending = null;
                    }
                }

                if (error.statusCode == 400 && pending == null) {
                    clearSelectedSlot();
                    slotsLoaded = false;
                    scheduleError =
                            "Обновите расписание перед новым выбором времени.";
                }

                render(true);

                String advice;
                if (error.statusCode == 409) {
                    advice = "\n\nКонфликт идентификатора или ключа. " +
                            "Свяжитесь с магазином. " +
                            "Не оформляйте этот заказ заново.";
                } else if (pending != null) {
                    advice = "\n\nЗапрос сохранён на телефоне. " +
                            "Используйте «Проверить прежний заказ».";
                } else {
                    advice = "\n\nПроверьте данные, обновите каталог " +
                            "и расписание перед новой попыткой.";
                }

                message("Результат отправки", error.getMessage() + advice);
            }
        });
    }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    @Override
    public void onBackPressed() {
        if (tab == 4 && showingHistory) {
            openTab(4);
        } else if (tab != 0) {
            openTab(0);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        saveProfile();
        productFlightGeneration++;
        if (productMotion != null) {
            productMotion.stopFlight();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        productFlightGeneration++;
        if (productMotion != null) {
            productMotion.close();
        }
        if (api != null) api.close();
        super.onDestroy();
    }
}
// КОНЕЦ ФАЙЛА
