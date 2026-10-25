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
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class BrandLoading {

    private static final int BLACK =
            Color.rgb(14, 15, 17);

    private static final int GOLD =
            Color.rgb(212, 175, 55);

    private static final int WHITE =
            Color.rgb(247, 244, 236);

    private static final int MUTED =
            Color.rgb(166, 167, 163);

    private static final long EXIT_DURATION = 360L;

    private final Activity activity;
    private final FrameLayout host;
    private final FrameLayout overlay;
    private final View underlying;
    private final int previousAccessibility;

    private final TextView logo;
    private final TextView title;
    private final TextView status;
    private final TextView hint;

    private final Wave wave;

    private final Button retryButton;
    private final Button continueButton;

    private final Runnable retry;

    /*
     * finished — загрузочный экран больше не принимает
     * изменения состояния.
     *
     * removed — экран действительно удалён из окна.
     * Между этими состояниями идёт плавное исчезновение.
     */
    private boolean finished;
    private boolean removed;
    private boolean loading;
    private boolean wasLoading;
    private boolean failed;

    private final Runnable removeAfterFade =
            new Runnable() {
                @Override
                public void run() {
                    removeOverlay();
                }
            };

    private final Runnable slowMessage =
            new Runnable() {
                @Override
                public void run() {
                    if (finished || removed || !loading) {
                        return;
                    }

                    status.setText("Магазин просыпается…");

                    hint.setText(
                            "После перерыва подключение может " +
                            "занять около минуты. Заказы и история " +
                            "остаются на месте."
                    );
                }
            };

    public BrandLoading(
            Activity activity,
            Runnable retry
    ) {
        this.activity = activity;
        this.retry = retry;

        host = activity.findViewById(
                android.R.id.content
        );

        underlying = host.getChildCount() > 0
                ? host.getChildAt(0)
                : null;

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
        scroll.setOverScrollMode(
                View.OVER_SCROLL_NEVER
        );

        LinearLayout content =
                new LinearLayout(activity);

        content.setOrientation(
                LinearLayout.VERTICAL
        );

        content.setGravity(Gravity.CENTER);

        content.setPadding(
                dp(24), dp(32),
                dp(24), dp(32)
        );

        // -------------------- ЛОГОТИП --------------------

        logo = label("ЛВ", 52, GOLD);

        logo.setTypeface(
                Typeface.create(
                        "serif",
                        Typeface.BOLD
                )
        );

        logo.setPadding(0, 0, 0, 0);
        logo.setContentDescription("Лавка Вкуса");

        GradientDrawable ring =
                new GradientDrawable();

        ring.setShape(GradientDrawable.OVAL);

        ring.setColor(
                Color.rgb(23, 24, 26)
        );

        ring.setStroke(dp(1), GOLD);

        logo.setBackground(ring);

        LinearLayout.LayoutParams logoParams =
                new LinearLayout.LayoutParams(
                        dp(136), dp(136)
                );

        logoParams.setMargins(
                0, 0, 0, dp(24)
        );

        content.addView(logo, logoParams);

        // -------------------- НАЗВАНИЕ --------------------

        title = label(
                "Лавка Вкуса",
                29,
                WHITE
        );

        title.setTypeface(
                Typeface.create(
                        "sans-serif-medium",
                        Typeface.NORMAL
                )
        );

        content.addView(title);

        TextView slogan = label(
                "Вкусные покупки\nначинаются здесь",
                16,
                MUTED
        );

        slogan.setLineSpacing(dp(4), 1);

        content.addView(slogan);

        // -------------------- ВОЛНА --------------------

        wave = new Wave(activity);

        wave.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
        );

        LinearLayout.LayoutParams waveParams =
                new LinearLayout.LayoutParams(
                        -1, dp(76)
                );

        waveParams.setMargins(
                0, dp(24), 0, dp(10)
        );

        content.addView(wave, waveParams);

        // -------------------- СОСТОЯНИЕ --------------------

        status = label(
                "Загружаем ассортимент…",
                15,
                GOLD
        );

        status.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_POLITE
        );

        content.addView(status);

        hint = label(
                "Подключаемся к магазину",
                13,
                MUTED
        );

        content.addView(hint);

        // -------------------- ПОВТОР --------------------

        retryButton = makeButton(
                "Повторить подключение",
                () -> {
                    if (finished || removed || loading) {
                        return;
                    }

                    setLoading();
                    this.retry.run();
                }
        );

        retryButton.setVisibility(View.GONE);
        content.addView(retryButton);

        // -------------------- ВХОД БЕЗ КАТАЛОГА --------------------

        continueButton = makeButton(
                "Открыть магазин без каталога",
                this::finish
        );

        continueButton.setTextColor(GOLD);

        GradientDrawable secondary =
                new GradientDrawable();

        secondary.setColor(BLACK);
        secondary.setCornerRadius(dp(14));
        secondary.setStroke(dp(1), GOLD);

        continueButton.setBackground(secondary);
        continueButton.setVisibility(View.GONE);

        content.addView(continueButton);

        scroll.addView(
                content,
                new ScrollView.LayoutParams(
                        -1, -1
                )
        );

        overlay.addView(
                scroll,
                new FrameLayout.LayoutParams(
                        -1, -1
                )
        );

        host.addView(
                overlay,
                new FrameLayout.LayoutParams(
                        -1, -1
                )
        );

        animateEntrance();
    }

    // -------------------- МЯГКОЕ ПОЯВЛЕНИЕ --------------------

    private void animateEntrance() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            return;
        }

        logo.setAlpha(0f);
        logo.setScaleX(0.96f);
        logo.setScaleY(0.96f);
        logo.setTranslationY(dp(6));

        logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(500L)
                .setInterpolator(
                        new DecelerateInterpolator()
                )
                .start();

        title.setAlpha(0f);
        title.setTranslationY(dp(5));

        title.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450L)
                .setInterpolator(
                        new DecelerateInterpolator()
                )
                .start();
    }

    // -------------------- ОБНОВЛЕНИЕ СОСТОЯНИЯ --------------------

    public void update(
            boolean busy,
            boolean ready
    ) {
        if (finished || removed) {
            return;
        }

        if (ready) {
            finish();
            return;
        }

        if (busy) {
            if (!loading) {
                setLoading();
            }

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
        if (finished || removed) {
            return;
        }

        loading = true;
        wasLoading = true;
        failed = false;

        status.setText(
                "Загружаем ассортимент…"
        );

        hint.setText(
                "Подключаемся к магазину"
        );

        retryButton.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);

        wave.setVisibility(View.VISIBLE);
        wave.invalidate();

        overlay.removeCallbacks(slowMessage);

        overlay.postDelayed(
                slowMessage,
                12000L
        );
    }

    public boolean isBlocking() {
        /*
         * Во время исчезновения экран ещё
         * перехватывает нажатия.
         */
        return !removed;
    }

    public boolean isFinished() {
        /*
         * Для восстановления Activity считаем
         * завершённым и уже начатый переход.
         */
        return finished;
    }

    // -------------------- ОШИБКА --------------------

    public void showError(String message) {
        if (finished || removed) {
            return;
        }

        loading = false;
        failed = true;

        overlay.removeCallbacks(slowMessage);

        status.setText(
                "Не удалось загрузить каталог"
        );

        hint.setText(
                message == null ||
                message.trim().isEmpty()
                        ? "Проверьте интернет и повторите подключение."
                        : message
        );

        wave.setVisibility(View.INVISIBLE);

        retryButton.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);
    }

    // -------------------- ПЛАВНЫЙ ПЕРЕХОД --------------------

    public void finish() {
        if (finished || removed) {
            return;
        }

        finished = true;
        loading = false;

        overlay.removeCallbacks(slowMessage);

        retryButton.setEnabled(false);
        continueButton.setEnabled(false);

        /*
         * Никакой дополнительной паузы:
         * начинаем открывать магазин сразу.
         */
        if (
                !ValueAnimator.areAnimatorsEnabled() ||
                !overlay.isAttachedToWindow() ||
                activity.isFinishing() ||
                activity.isDestroyed()
        ) {
            removeOverlay();
            return;
        }

        /*
         * Волна продолжает двигаться при исчезновении.
         * Нижний экран не масштабируется:
         * текст и фотографии остаются чёткими.
         */
        overlay.animate()
                .alpha(0f)
                .setDuration(EXIT_DURATION)
                .setInterpolator(
                        new AccelerateDecelerateInterpolator()
                )
                .withEndAction(removeAfterFade)
                .start();

        /*
         * Страховка на случай прерывания анимации.
         * Это не задержка открытия — удаление обычно
         * выполняется в конце анимации выше.
         */
        overlay.postDelayed(
                removeAfterFade,
                EXIT_DURATION + 150L
        );
    }

    private void removeOverlay() {
        if (removed) {
            return;
        }

        removed = true;
        finished = true;
        loading = false;

        overlay.removeCallbacks(slowMessage);
        overlay.removeCallbacks(removeAfterFade);

        overlay.animate().withEndAction(null);
        overlay.animate().cancel();

        logo.animate().cancel();
        title.animate().cancel();

        if (underlying != null) {
            underlying.setImportantForAccessibility(
                    previousAccessibility
            );
        }

        if (overlay.getParent() == host) {
            host.removeView(overlay);
        }
    }

    public void dispose() {
        /*
         * При уничтожении Activity убираем всё сразу,
         * без незавершённых анимаций и отложенных действий.
         */
        removeOverlay();
    }

    // -------------------- ЭЛЕМЕНТЫ ОФОРМЛЕНИЯ --------------------

    private TextView label(
            String text,
            int size,
            int color
    ) {
        TextView result = new TextView(activity);

        result.setText(text);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setGravity(Gravity.CENTER);

        result.setPadding(
                0, dp(5), 0, dp(5)
        );

        return result;
    }

    private Button makeButton(
            String text,
            Runnable action
    ) {
        Button result = new Button(activity);

        result.setText(text);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setTextColor(BLACK);

        result.setMinHeight(dp(48));
        result.setMinimumHeight(dp(48));

        result.setPadding(
                dp(12), dp(10),
                dp(12), dp(10)
        );

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(GOLD);
        background.setCornerRadius(dp(14));

        result.setBackgroundTintList(null);
        result.setBackground(background);
        result.setStateListAnimator(null);

        result.setOnClickListener(
                view -> action.run()
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        -1, -2
                );

        params.setMargins(
                0, dp(10), 0, 0
        );

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

    // -------------------- ЗОЛОТАЯ ВОЛНА --------------------

    private final class Wave extends View {

        private static final long CYCLE_MS = 7200L;

        private final Paint paint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Path path = new Path();

        private final long started =
                SystemClock.uptimeMillis();

        Wave(Activity activity) {
            super(activity);

            /*
             * Не включаем программный слой:
             * обычного рисования View достаточно.
             */
            setWillNotDraw(false);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            invalidate();
        }

        @Override
        protected void onWindowVisibilityChanged(
                int visibility
        ) {
            super.onWindowVisibilityChanged(visibility);

            if (visibility == View.VISIBLE) {
                invalidate();
            }
        }

        @Override
        protected void onVisibilityChanged(
                View changedView,
                int visibility
        ) {
            super.onVisibilityChanged(
                    changedView,
                    visibility
            );

            if (visibility == View.VISIBLE) {
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (removed || failed) {
                return;
            }

            float width = getWidth();
            float height = getHeight();

            if (width <= 0 || height <= 0) {
                return;
            }

            boolean animated =
                    ValueAnimator.areAnimatorsEnabled();

            long elapsed =
                    SystemClock.uptimeMillis() - started;

            double phase = animated
                    ? (elapsed % CYCLE_MS) *
                      (Math.PI * 2.0 / CYCLE_MS)
                    : 0.0;

            float density =
                    getResources()
                            .getDisplayMetrics().density;

            /*
             * Шаг в экранных пикселях:
             * меньше лишних точек на плотных экранах.
             */
            float sampleStep =
                    Math.max(2f, density * 1.5f);

            paint.setStrokeCap(
                    Paint.Cap.ROUND
            );

            paint.setStrokeJoin(
                    Paint.Join.ROUND
            );

            for (int layer = 2; layer >= 0; layer--) {
                float amplitude = height *
                        (0.16f - layer * 0.025f);

                /*
                 * Более прозрачные волны движутся
                 * с небольшим сдвигом относительно главной.
                 */
                double offset = layer * 0.72;

                // Мягкая полупрозрачная заливка под волной.
                buildWave(
                        width, height,
                        sampleStep, amplitude,
                        phase, offset
                );

                path.lineTo(width, height * 0.90f);
                path.lineTo(0f, height * 0.90f);
                path.close();

                paint.setStyle(
                        Paint.Style.FILL
                );

                paint.setColor(GOLD);
                paint.setAlpha(
                        layer == 0 ? 12 : 6
                );

                canvas.drawPath(path, paint);

                // Тонкий золотой контур.
                buildWave(
                        width, height,
                        sampleStep, amplitude,
                        phase, offset
                );

                paint.setStyle(
                        Paint.Style.STROKE
                );

                paint.setAlpha(
                        layer == 0
                                ? 235
                                : layer == 1 ? 110 : 55
                );

                paint.setStrokeWidth(
                        density *
                        (layer == 0 ? 1.8f : 1.0f)
                );

                canvas.drawPath(path, paint);
            }

            /*
             * Планируем рисование следующего кадра,
             * только пока экран действительно виден.
             */
            if (
                    animated &&
                    !removed &&
                    isAttachedToWindow() &&
                    isShown() &&
                    getWindowVisibility() == View.VISIBLE
            ) {
                postInvalidateOnAnimation();
            }
        }

        private void buildWave(
                float width,
                float height,
                float sampleStep,
                float amplitude,
                double phase,
                double offset
        ) {
            path.reset();

            int samples = Math.max(
                    2,
                    (int) Math.ceil(width / sampleStep)
            );

            for (int i = 0; i <= samples; i++) {
                float fraction =
                        (float) i / samples;

                float x = fraction * width;

                /*
                 * Края волны остаются спокойными,
                 * основное движение — в середине.
                 */
                double envelope = Math.sin(
                        Math.PI * fraction
                );

                double primary = Math.sin(
                        fraction * Math.PI * 3.0
                                - phase
                                + offset
                );

                double secondary = Math.sin(
                        fraction * Math.PI * 5.0
                                - phase * 2.0
                                + offset
                ) * 0.15;

                float y = height * 0.48f +
                        (float) (
                                (primary + secondary) *
                                amplitude *
                                envelope
                        );

                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
        }
    }
}

// КОНЕЦ ФАЙЛА
