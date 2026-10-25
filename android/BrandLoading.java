package ru.makeevka.fishshop;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class BrandLoading {

    private static final int BLACK = Color.rgb(14, 15, 17);
    private static final int GOLD = Color.rgb(212, 175, 55);
    private static final int WHITE = Color.rgb(247, 244, 236);
    private static final int MUTED = Color.rgb(166, 167, 163);

    private final Activity activity;
    private final FrameLayout host;
    private final FrameLayout overlay;
    private final View underlying;
    private final int previousAccessibility;

    private final TextView status;
    private final TextView hint;
    private final Wave wave;
    private final Button retryButton;
    private final Button continueButton;
    private final Runnable retry;

    private boolean finished;
    private boolean loading;
    private boolean wasLoading;
    private boolean failed;

    private final Runnable slowMessage = new Runnable() {
        @Override
        public void run() {
            if (!finished && loading) {
                status.setText("Магазин просыпается…");
                hint.setText(
                        "После перерыва подключение может " +
                        "занять около минуты. Заказы и история " +
                        "остаются на месте."
                );
            }
        }
    };

    public BrandLoading(Activity activity, Runnable retry) {
        this.activity = activity;
        this.retry = retry;

        host = activity.findViewById(android.R.id.content);
        underlying = host.getChildCount() > 0
                ? host.getChildAt(0) : null;

        previousAccessibility = underlying == null
                ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : underlying.getImportantForAccessibility();

        if (underlying != null) {
            underlying.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            );
        }

        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(BLACK);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(
                dp(24), dp(32), dp(24), dp(32)
        );

        TextView logo = label("ЛВ", 52, GOLD);
        logo.setTypeface(
                Typeface.create(
                        "serif", Typeface.BOLD
                )
        );
        logo.setGravity(Gravity.CENTER);
        logo.setPadding(0, 0, 0, 0);
        logo.setContentDescription("Лавка Вкуса");

        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.rgb(23, 24, 26));
        ring.setStroke(dp(1), GOLD);
        logo.setBackground(ring);

        LinearLayout.LayoutParams logoParams =
                new LinearLayout.LayoutParams(dp(136), dp(136));
        logoParams.setMargins(0, 0, 0, dp(24));
        content.addView(logo, logoParams);

        TextView title = label("Лавка Вкуса", 29, WHITE);
        title.setTypeface(
                Typeface.create(
                        "sans-serif-medium", Typeface.NORMAL
                )
        );
        content.addView(title);

        TextView slogan = label(
                "Вкусные покупки\nначинаются здесь",
                16, MUTED
        );
        slogan.setLineSpacing(dp(4), 1);
        content.addView(slogan);

        wave = new Wave(activity);
        wave.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
        );

        LinearLayout.LayoutParams waveParams =
                new LinearLayout.LayoutParams(-1, dp(70));
        waveParams.setMargins(0, dp(24), 0, dp(10));
        content.addView(wave, waveParams);

        status = label("Загружаем ассортимент…", 15, GOLD);
        status.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_POLITE
        );
        content.addView(status);

        hint = label(
                "Подключаемся к магазину",
                13, MUTED
        );
        content.addView(hint);

        retryButton = makeButton(
                "Повторить подключение",
                () -> {
                    if (finished || loading) return;
                    setLoading();
                    this.retry.run();
                }
        );
        retryButton.setVisibility(View.GONE);
        content.addView(retryButton);

        continueButton = makeButton(
                "Открыть магазин без каталога",
                this::finish
        );
        continueButton.setTextColor(GOLD);

        GradientDrawable secondary = new GradientDrawable();
        secondary.setColor(BLACK);
        secondary.setCornerRadius(dp(14));
        secondary.setStroke(dp(1), GOLD);

        continueButton.setBackground(secondary);
        continueButton.setVisibility(View.GONE);
        content.addView(continueButton);

        scroll.addView(
                content,
                new ScrollView.LayoutParams(-1, -1)
        );
        overlay.addView(
                scroll,
                new FrameLayout.LayoutParams(-1, -1)
        );
        host.addView(
                overlay,
                new FrameLayout.LayoutParams(-1, -1)
        );

        if (ValueAnimator.areAnimatorsEnabled()) {
            logo.setAlpha(0f);
            logo.setScaleX(0.92f);
            logo.setScaleY(0.92f);

            logo.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(550)
                    .start();

            title.setAlpha(0f);
            title.animate()
                    .alpha(1f)
                    .setDuration(650)
                    .start();
        }
    }

    public void update(boolean busy, boolean ready) {
        if (finished) return;

        if (ready) {
            finish();
            return;
        }

        if (busy) {
            if (!loading) setLoading();
            return;
        }

        if (wasLoading && loading) {
            showError(
                    "Проверьте подключение к интернету " +
                    "и попробуйте ещё раз."
            );
        }
    }

    private void setLoading() {
        if (finished) return;

        loading = true;
        wasLoading = true;
        failed = false;

        status.setText("Загружаем ассортимент…");
        hint.setText("Подключаемся к магазину");

        retryButton.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);
        wave.setVisibility(View.VISIBLE);
        wave.invalidate();

        overlay.removeCallbacks(slowMessage);
        overlay.postDelayed(slowMessage, 12000);
    }

    public boolean isBlocking() {
        return !finished;
    }

    public boolean isFinished() {
        return finished;
    }

    public void showError(String message) {
        if (finished) return;

        loading = false;
        failed = true;
        overlay.removeCallbacks(slowMessage);

        status.setText("Не удалось загрузить каталог");
        hint.setText(
                message == null || message.trim().isEmpty()
                        ? "Проверьте интернет и повторите подключение."
                        : message
        );

        wave.setVisibility(View.INVISIBLE);
        retryButton.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);
    }

    public void finish() {
        if (finished) return;

        finished = true;
        loading = false;
        overlay.removeCallbacks(slowMessage);

        if (underlying != null) {
            underlying.setImportantForAccessibility(
                    previousAccessibility
            );
        }

        // Не задерживаем открытие магазина ради анимации.
        host.removeView(overlay);
    }

    public void dispose() {
        finish();
    }

    private TextView label(
            String text, int size, int color
    ) {
        TextView result = new TextView(activity);
        result.setText(text);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setGravity(Gravity.CENTER);
        result.setPadding(0, dp(5), 0, dp(5));
        return result;
    }

    private Button makeButton(
            String text, Runnable action
    ) {
        Button result = new Button(activity);
        result.setText(text);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setTextColor(BLACK);
        result.setMinHeight(dp(48));
        result.setMinimumHeight(dp(48));
        result.setPadding(
                dp(12), dp(10), dp(12), dp(10)
        );

        GradientDrawable background = new GradientDrawable();
        background.setColor(GOLD);
        background.setCornerRadius(dp(14));

        result.setBackgroundTintList(null);
        result.setBackground(background);
        result.setStateListAnimator(null);
        result.setOnClickListener(view -> action.run());

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(10), 0, 0);
        result.setLayoutParams(params);
        return result;
    }

    private int dp(int value) {
        return Math.round(
                value *
                activity.getResources()
                        .getDisplayMetrics().density
        );
    }

    private final class Wave extends View {

        private final Paint paint =
                new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final long started =
                SystemClock.uptimeMillis();

        Wave(Activity activity) {
            super(activity);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == View.VISIBLE) invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (finished || failed) return;

            boolean animated = ValueAnimator.areAnimatorsEnabled();
            float phase = animated
                    ? (SystemClock.uptimeMillis() - started) / 650f
                    : 0f;

            float width = getWidth();
            float height = getHeight();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);

            for (int layer = 0; layer < 3; layer++) {
                paint.setColor(GOLD);
                paint.setAlpha(230 - layer * 75);
                paint.setStrokeWidth(
                        getResources().getDisplayMetrics().density *
                        (layer == 0 ? 2f : 1f)
                );

                path.reset();

                for (int x = 0; x <= getWidth(); x += 2) {
                    float fraction = width <= 0 ? 0 : x / width;
                    float envelope = (float) Math.sin(
                            Math.PI * fraction
                    );

                    float y = height / 2f +
                            (float) Math.sin(
                                    fraction * Math.PI * 3.5 -
                                    phase + layer * 0.65
                            ) * height * 0.19f * envelope;

                    if (x == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }

                canvas.drawPath(path, paint);
            }

            if (animated &&
                    isAttachedToWindow() &&
                    isShown() &&
                    getWindowVisibility() == View.VISIBLE) {
                postInvalidateOnAnimation();
            }
        }
    }
}
// КОНЕЦ ФАЙЛА
