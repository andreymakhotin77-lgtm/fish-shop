package ru.makeevka.fishshop;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/*
 * Загрузочный экран «Жидкое золото».
 *
 * Объём имитируется обычным рисованием Canvas:
 * градиенты, толщина медальона, блики и мягкое движение.
 *
 * Дополнительные библиотеки и изображения не нужны.
 * Искусственной задержки перед открытием магазина нет.
 */
public final class BrandLoading {

    private static final int BLACK =
            Color.rgb(14, 15, 17);

    private static final int GOLD =
            Color.rgb(212, 175, 55);

    private static final int WHITE =
            Color.rgb(247, 244, 236);

    private static final int MUTED =
            Color.rgb(166, 167, 163);

    private static final long EXIT_DURATION = 420L;

    private final Activity activity;
    private final FrameLayout host;
    private final FrameLayout overlay;
    private final View underlying;

    private final int previousAccessibility;

    private final GoldScene scene;
    private final TextView status;
    private final TextView hint;

    private final Button retryButton;
    private final Button continueButton;

    private final Runnable retry;

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
                            "занять около минуты. " +
                            "Можно немного подождать."
                    );

                    continueButton.setVisibility(View.VISIBLE);
                }
            };

    public BrandLoading(
            Activity activity,
            Runnable retry
    ) {
        if (activity == null || retry == null) {
            throw new IllegalArgumentException(
                    "Не заданы Activity или действие повтора."
            );
        }

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

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);

        body.setPadding(
                dp(24), dp(24),
                dp(24), dp(28)
        );

        TextView signature = label(
                "Л А В К А   В К У С А",
                11,
                GOLD
        );

        body.addView(signature);

        scene = new GoldScene(activity);
        scene.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
        );

        body.addView(
                scene,
                new LinearLayout.LayoutParams(
                        -1, dp(280)
                )
        );

        TextView title = label(
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

        body.addView(title);

        TextView slogan = label(
                "Вкусные покупки начинаются здесь",
                14,
                MUTED
        );

        body.addView(slogan);

        status = label(
                "Загружаем ассортимент…",
                15,
                GOLD
        );

        status.setPadding(
                0, dp(24), 0, dp(6)
        );

        status.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_POLITE
        );

        body.addView(status);

        hint = label(
                "Подключаемся к магазину",
                13,
                MUTED
        );

        body.addView(hint);

        retryButton = makeButton(
                "Повторить подключение",
                () -> {
                    if (finished || removed || loading) {
                        return;
                    }

                    setLoading();
                    this.retry.run();
                },
                false
        );

        retryButton.setVisibility(View.GONE);
        body.addView(retryButton);

        continueButton = makeButton(
                "Открыть магазин без каталога",
                this::finish,
                true
        );

        continueButton.setVisibility(View.GONE);
        body.addView(continueButton);

        scroll.addView(
                body,
                new ScrollView.LayoutParams(-1, -1)
        );

        overlay.addView(
                scroll,
                new FrameLayout.LayoutParams(-1, -1)
        );

        /*
         * Не вызываем bringToFront():
         * экран защиты входа должен оставаться выше
         * загрузочного экрана, если он уже отображается.
         */
        host.addView(
                overlay,
                new FrameLayout.LayoutParams(-1, -1)
        );
    }

    // ==================== СОСТОЯНИЕ ====================

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

        status.setText("Загружаем ассортимент…");
        hint.setText("Подключаемся к магазину");

        retryButton.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);

        scene.invalidate();

        overlay.removeCallbacks(slowMessage);
        overlay.postDelayed(slowMessage, 12000L);
    }

    public boolean isBlocking() {
        return !removed;
    }

    public boolean isFinished() {
        return finished;
    }

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

        retryButton.setVisibility(View.VISIBLE);
        continueButton.setVisibility(View.VISIBLE);

        /*
         * При ошибке останавливаем движение.
         * Медальон остаётся видимым.
         */
        scene.invalidate();
    }

    // ==================== ПЕРЕХОД ====================

    public void finish() {
        if (finished || removed) {
            return;
        }

        finished = true;
        loading = false;

        overlay.removeCallbacks(slowMessage);

        retryButton.setEnabled(false);
        continueButton.setEnabled(false);

        if (!ValueAnimator.areAnimatorsEnabled() ||
                !overlay.isAttachedToWindow() ||
                activity.isFinishing() ||
                activity.isDestroyed()) {
            removeOverlay();
            return;
        }

        /*
         * Открываем магазин сразу после готовности.
         * Не масштабируем основной интерфейс.
         */
        overlay.animate()
                .alpha(0f)
                .setDuration(EXIT_DURATION)
                .setInterpolator(
                        new AccelerateDecelerateInterpolator()
                )
                .withEndAction(removeAfterFade)
                .start();

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
        removeOverlay();
    }

    // ==================== ОФОРМЛЕНИЕ ====================

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
            Runnable action,
            boolean secondary
    ) {
        Button result = new Button(activity);
        result.setText(text);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setTextColor(
                secondary ? GOLD : BLACK
        );

        result.setMinHeight(dp(48));
        result.setMinimumHeight(dp(48));

        result.setPadding(
                dp(12), dp(10),
                dp(12), dp(10)
        );

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                secondary ? BLACK : GOLD
        );

        background.setCornerRadius(dp(14));

        if (secondary) {
            background.setStroke(dp(1), GOLD);
        }

        result.setBackgroundTintList(null);
        result.setBackground(background);
        result.setStateListAnimator(null);

        result.setOnClickListener(
                view -> action.run()
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);

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

    // ==================== ЗОЛОТАЯ СЦЕНА ====================

    private final class GoldScene extends View {

        private static final long CYCLE_MS = 8400L;

        /*
         * Логическая сцена 360 × 300.
         * Масштабируется под доступный размер View.
         */
        private static final float SCENE_WIDTH = 360f;
        private static final float SCENE_HEIGHT = 300f;

        private final Paint paint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint letters =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Path wavePath = new Path();

        private final Shader metal;
        private final Shader darkMetal;
        private final Shader glow;
        private final Shader shadow;
        private final Shader letterGold;

        private final long started =
                SystemClock.uptimeMillis();

        GoldScene(Activity owner) {
            super(owner);

            setWillNotDraw(false);

            /*
             * Градиенты создаются один раз,
             * а не заново для каждого кадра.
             */
            metal = new LinearGradient(
                    95f, 45f,
                    258f, 211f,
                    new int[]{
                            Color.rgb(109, 75, 22),
                            Color.rgb(229, 190, 82),
                            Color.rgb(255, 237, 171),
                            Color.rgb(188, 139, 41),
                            Color.rgb(92, 60, 19)
                    },
                    new float[]{
                            0f, 0.27f, 0.43f, 0.68f, 1f
                    },
                    Shader.TileMode.CLAMP
            );

            darkMetal = new LinearGradient(
                    110f, 55f,
                    245f, 195f,
                    new int[]{
                            Color.rgb(56, 48, 30),
                            Color.rgb(27, 28, 29),
                            Color.rgb(15, 17, 20)
                    },
                    null,
                    Shader.TileMode.CLAMP
            );

            glow = new RadialGradient(
                    180f, 122f,
                    133f,
                    new int[]{
                            Color.argb(42, 212, 175, 55),
                            Color.argb(13, 212, 175, 55),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.58f, 1f},
                    Shader.TileMode.CLAMP
            );

            shadow = new RadialGradient(
                    180f, 222f,
                    92f,
                    new int[]{
                            Color.argb(150, 0, 0, 0),
                            Color.TRANSPARENT
                    },
                    null,
                    Shader.TileMode.CLAMP
            );

            letterGold = new LinearGradient(
                    135f, 80f,
                    222f, 153f,
                    new int[]{
                            Color.rgb(255, 238, 181),
                            Color.rgb(229, 193, 100),
                            Color.rgb(159, 111, 28)
                    },
                    null,
                    Shader.TileMode.CLAMP
            );

            letters.setTypeface(
                    Typeface.create(
                            "serif",
                            Typeface.BOLD
                    )
            );

            letters.setTextAlign(
                    Paint.Align.CENTER
            );

            letters.setTextSize(61f);
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

            if (removed ||
                    getWidth() <= 0 ||
                    getHeight() <= 0) {
                return;
            }

            boolean animated =
                    ValueAnimator.areAnimatorsEnabled() &&
                    !failed;

            long elapsed =
                    SystemClock.uptimeMillis() - started;

            double phase = animated
                    ? (elapsed % CYCLE_MS) *
                      (Math.PI * 2.0 / CYCLE_MS)
                    : 0.0;

            float scale = Math.min(
                    getWidth() / SCENE_WIDTH,
                    getHeight() / SCENE_HEIGHT
            );

            canvas.save();

            canvas.translate(
                    (getWidth() - SCENE_WIDTH * scale) / 2f,
                    (getHeight() - SCENE_HEIGHT * scale) / 2f
            );

            canvas.scale(scale, scale);

            drawGlow(canvas);
            drawParticles(canvas, phase);
            drawShadow(canvas, phase);
            drawCoin(canvas, phase);
            drawWaves(canvas, phase);

            canvas.restore();

            /*
             * Ограничиваем запрос перерисовки
             * примерно 30 кадрами в секунду.
             *
             * В фоне и при скрытом View новые кадры
             * не запрашиваются.
             */
            if (animated &&
                    !removed &&
                    isAttachedToWindow() &&
                    isShown() &&
                    getWindowVisibility() == View.VISIBLE) {
                postInvalidateDelayed(33L);
            }
        }

        private void resetPaint() {
            paint.setShader(null);
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(1f);
        }

        private void drawGlow(Canvas canvas) {
            resetPaint();
            paint.setShader(glow);

            canvas.drawCircle(
                    180f, 122f, 133f, paint
            );

            paint.setShader(null);
        }

        private void drawParticles(
                Canvas canvas,
                double phase
        ) {
            resetPaint();
            paint.setColor(GOLD);

            for (int i = 0; i < 14; i++) {
                double offset =
                        i * Math.PI * 2.0 / 14.0;

                double angle = phase + offset;

                float radius =
                        100f + (i % 3) * 10f;

                float x = 180f +
                        (float) Math.cos(angle) * radius;

                float y = 123f +
                        (float) Math.sin(angle) *
                        (75f + (i % 4) * 6f);

                float brightness =
                        0.5f +
                        0.5f * (float) Math.sin(
                                phase * 2.0 + offset
                        );

                paint.setAlpha(
                        35 + (int) (brightness * 115f)
                );

                float size =
                        i % 4 == 0 ? 1.8f : 1.1f;

                canvas.drawCircle(x, y, size, paint);
            }

            paint.setAlpha(255);
        }

        private void drawShadow(
                Canvas canvas,
                double phase
        ) {
            resetPaint();

            float widthScale =
                    0.88f +
                    0.04f * (float) Math.sin(phase);

            canvas.save();
            canvas.scale(
                    widthScale,
                    0.18f,
                    180f,
                    222f
            );

            paint.setShader(shadow);

            canvas.drawCircle(
                    180f, 222f, 92f, paint
            );

            canvas.restore();
            paint.setShader(null);
        }

        private void drawCoin(
                Canvas canvas,
                double phase
        ) {
            float lift =
                    (float) Math.sin(phase) * 4f;

            float tilt =
                    (float) Math.sin(phase) * 3.5f;

            float perspective =
                    0.93f +
                    (float) Math.cos(phase) * 0.025f;

            canvas.save();

            canvas.translate(0f, lift);

            canvas.rotate(
                    tilt,
                    180f,
                    118f
            );

            canvas.scale(
                    1f,
                    perspective,
                    180f,
                    118f
            );

            // Нижний край: имитация толщины.
            resetPaint();
            paint.setColor(
                    Color.rgb(86, 57, 17)
            );

            canvas.drawCircle(
                    180f, 125f, 79f, paint
            );

            // Основное металлическое кольцо.
            paint.setShader(metal);

            canvas.drawCircle(
                    180f, 118f, 79f, paint
            );

            // Тёмная внутренняя поверхность.
            paint.setShader(darkMetal);

            canvas.drawCircle(
                    180f, 118f, 71f, paint
            );

            paint.setShader(null);

            // Внутренняя тонкая окантовка.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.9f);
            paint.setColor(
                    Color.rgb(202, 164, 67)
            );
            paint.setAlpha(155);

            canvas.drawCircle(
                    180f, 118f, 65f, paint
            );

            // Мягкий свет на верхнем краю.
            paint.setStrokeWidth(1.8f);
            paint.setColor(
                    Color.rgb(255, 237, 179)
            );
            paint.setAlpha(190);

            canvas.drawArc(
                    102f, 40f,
                    258f, 196f,
                    205f, 102f,
                    false,
                    paint
            );

            // Сдержанный движущийся блик.
            float glintAngle =
                    -100f +
                    (float) Math.sin(phase) * 28f;

            paint.setStrokeWidth(3f);
            paint.setAlpha(170);

            canvas.drawArc(
                    104f, 42f,
                    256f, 194f,
                    glintAngle, 21f,
                    false,
                    paint
            );

            // Тень букв создаёт эффект рельефа.
            letters.setShader(null);
            letters.setColor(
                    Color.rgb(5, 6, 8)
            );

            float baseline = 118f -
                    (letters.ascent() + letters.descent()) / 2f;

            canvas.drawText(
                    "ЛВ",
                    181.5f,
                    baseline + 2.5f,
                    letters
            );

            // Золотые буквы.
            letters.setShader(letterGold);

            canvas.drawText(
                    "ЛВ",
                    180f,
                    baseline,
                    letters
            );

            letters.setShader(null);

            // Небольшая декоративная точка.
            resetPaint();
            paint.setColor(GOLD);

            canvas.drawCircle(
                    180f, 169f, 1.8f, paint
            );

            canvas.restore();
            resetPaint();
        }

        private void drawWaves(
                Canvas canvas,
                double phase
        ) {
            for (int layer = 2; layer >= 0; layer--) {
                buildWave(phase, layer);

                wavePath.lineTo(348f, 286f);
                wavePath.lineTo(12f, 286f);
                wavePath.close();

                resetPaint();
                paint.setColor(GOLD);
                paint.setAlpha(
                        layer == 0 ? 13 : 6
                );

                canvas.drawPath(wavePath, paint);

                buildWave(phase, layer);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(
                        layer == 0 ? 1.7f : 0.9f
                );

                paint.setAlpha(
                        layer == 0 ? 220 :
                        layer == 1 ? 95 : 45
                );

                canvas.drawPath(wavePath, paint);
            }

            resetPaint();
        }

        private void buildWave(
                double phase,
                int layer
        ) {
            wavePath.reset();

            final int samples = 112;

            for (int i = 0; i <= samples; i++) {
                float fraction =
                        (float) i / samples;

                float x =
                        12f + fraction * 336f;

                double envelope =
                        Math.sin(Math.PI * fraction);

                double primary = Math.sin(
                        fraction * Math.PI * 3.0 -
                        phase +
                        layer * 0.75
                );

                double secondary = Math.sin(
                        fraction * Math.PI * 5.0 -
                        phase * 2.0 +
                        layer
                ) * 0.16;

                float amplitude =
                        13f - layer * 2.4f;

                float y = 263f +
                        (float) (
                                (primary + secondary) *
                                envelope *
                                amplitude
                        );

                if (i == 0) {
                    wavePath.moveTo(x, y);
                } else {
                    wavePath.lineTo(x, y);
                }
            }
        }
    }
}

// КОНЕЦ ФАЙЛА
