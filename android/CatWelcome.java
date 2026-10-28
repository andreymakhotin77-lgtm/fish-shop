package ru.makeevka.fishshop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;

/**
 * Однократная приветственная сцена.
 * Не разблокирует приложение и не изменяет данные магазина.
 */
public final class CatWelcome {

    private static final String PREFS =
            "lavka_welcome_v1";

    private static final String SEEN =
            "gold_cat_seen";

    private static final int BLACK =
            Color.rgb(14, 15, 17);

    private static final int GOLD =
            Color.rgb(212, 175, 55);

    private static final int WHITE =
            Color.rgb(247, 244, 236);

    private final Activity activity;
    private final Runnable completed;
    private final SharedPreferences prefs;

    private FrameLayout host;
    private FrameLayout overlay;
    private Scene scene;
    private ValueAnimator animator;

    private boolean ended;

    public static boolean shouldShow(Activity activity) {
        return activity != null &&
                !activity.isFinishing() &&
                !activity.isDestroyed() &&
                ValueAnimator.areAnimatorsEnabled() &&
                !activity.getSharedPreferences(
                        PREFS,
                        Activity.MODE_PRIVATE
                ).getBoolean(SEEN, false);
    }

    public CatWelcome(
            Activity activity,
            Runnable completed
    ) {
        this.activity = activity;
        this.completed = completed;

        prefs = activity.getSharedPreferences(
                PREFS,
                Activity.MODE_PRIVATE
        );
    }

    public boolean isBlocking() {
        return !ended;
    }

    public void start() {
        if (ended) return;

        if (activity.isFinishing() ||
                activity.isDestroyed()) {
            dispose();
            return;
        }

        host = activity.findViewById(
                android.R.id.content
        );

        if (host == null) {
            finish();
            return;
        }

        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(BLACK);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        scene = new Scene();
        scene.setContentDescription(
                "Золотой кот убегает с рыбкой. " +
                "Добро пожаловать в Лавку Вкуса."
        );

        overlay.addView(
                scene,
                new FrameLayout.LayoutParams(-1, -1)
        );

        Button skip = new Button(activity);
        skip.setText("Пропустить");
        skip.setAllCaps(false);
        skip.setTextColor(GOLD);
        skip.setTextSize(14);
        skip.setBackgroundColor(Color.TRANSPARENT);
        skip.setMinHeight(dp(48));
        skip.setMinimumHeight(dp(48));
        skip.setOnClickListener(view -> finish());

        FrameLayout.LayoutParams skipParams =
                new FrameLayout.LayoutParams(
                        -2, dp(48)
                );

        skipParams.gravity =
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

        skipParams.bottomMargin = dp(24);

        overlay.addView(skip, skipParams);

        host.addView(
                overlay,
                new FrameLayout.LayoutParams(-1, -1)
        );

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(3000L);
        animator.setInterpolator(
                new LinearInterpolator()
        );

        animator.addUpdateListener(value -> {
            if (ended) return;

            float progress =
                    (float) value.getAnimatedValue();

            scene.progress = progress;
            scene.invalidate();

            // Последние кадры — мягкое открытие магазина.
            float fade = clamp(
                    (progress - 0.87f) / 0.13f
            );

            overlay.setAlpha(1f - fade);
        });

        animator.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(
                            Animator animation
                    ) {
                        finish();
                    }
                }
        );

        animator.start();
    }

    /**
     * Завершение сцены, включая кнопку «Пропустить».
     * При уходе в фон сцену также можно завершить.
     */
    public void finish() {
        if (ended) return;
        ended = true;

        prefs.edit()
                .putBoolean(SEEN, true)
                .apply();

        remove();

        if (completed != null &&
                !activity.isFinishing() &&
                !activity.isDestroyed()) {
            completed.run();
        }
    }

    /**
     * Освобождение ресурсов без открытия магазина.
     */
    public void dispose() {
        if (ended) return;
        ended = true;
        remove();
    }

    private void remove() {
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }

        if (overlay != null &&
                host != null &&
                overlay.getParent() == host) {
            host.removeView(overlay);
        }

        overlay = null;
        scene = null;
    }

    private int dp(int value) {
        return Math.round(
                value *
                activity.getResources()
                        .getDisplayMetrics().density
        );
    }

    private static float clamp(float value) {
        return Math.max(
                0f,
                Math.min(1f, value)
        );
    }

    private final class Scene extends View {

        private final Paint paint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Path path = new Path();

        private float progress;

        Scene() {
            super(activity);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        private void stroke(float width, float alpha) {
            paint.setShader(null);
            paint.setColor(GOLD);
            paint.setAlpha(
                    Math.round(255f * clamp(alpha))
            );
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        private void fill(int color, float alpha) {
            paint.setShader(null);
            paint.setColor(color);
            paint.setAlpha(
                    Math.round(255f * clamp(alpha))
            );
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (getWidth() <= 0 ||
                    getHeight() <= 0) {
                return;
            }

            float scale = Math.min(
                    getWidth() / 360f,
                    getHeight() / 580f
            );

            canvas.save();

            canvas.translate(
                    getWidth() / 2f,
                    getHeight() / 2f
            );

            canvas.scale(scale, scale);

            drawCaption(canvas);

            if (progress < 0.73f) {
                drawCatScene(canvas);
            }

            drawLogo(canvas);

            canvas.restore();
        }

        private void drawCaption(Canvas canvas) {
            float alpha = 1f - clamp(
                    (progress - 0.54f) / 0.14f
            );

            fill(GOLD, alpha);

            paint.setTypeface(
                    Typeface.create(
                            "sans-serif-medium",
                            Typeface.NORMAL
                    )
            );

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(12f);

            canvas.drawText(
                    "Л А В К А   В К У С А",
                    0f, -140f, paint
            );

            fill(WHITE, alpha);
            paint.setTextSize(17f);

            canvas.drawText(
                    "Перед вкусным не устоять",
                    0f, 125f, paint
            );
        }

        private void drawCatScene(Canvas canvas) {
            // Сначала кот стоит, затем ускоряется.
            float run = clamp(
                    (progress - 0.23f) / 0.43f
            );

            float distance =
                    run * run;

            float x =
                    -32f + distance * 345f;

            float gait =
                    (float) Math.sin(
                            run * Math.PI * 12f
                    );

            float bounce =
                    run > 0f
                            ? -Math.abs(gait) * 5f
                            : 0f;

            float alpha = Math.min(
                    clamp(progress / 0.1f),
                    1f - clamp(
                            (progress - 0.64f) / 0.09f
                    )
            );

            // Тень.
            fill(GOLD, alpha * 0.08f);

            canvas.drawOval(
                    x - 58f, 47f,
                    x + 73f, 56f,
                    paint
            );

            canvas.save();
            canvas.translate(x, bounce);

            // След появляется только во время бега.
            if (run > 0f) {
                for (int i = 0; i < 3; i++) {
                    stroke(
                            1.5f,
                            alpha * (0.28f - i * 0.06f)
                    );

                    float y = -3f + i * 12f;

                    canvas.drawLine(
                            -83f - i * 9f,
                            y,
                            -65f,
                            y,
                            paint
                    );
                }
            }

            stroke(2.5f, alpha);

            // Хвост.
            path.reset();
            path.moveTo(-45f, 1f);
            path.cubicTo(
                    -77f, -5f,
                    -78f, -47f,
                    -65f, -53f
            );
            path.cubicTo(
                    -49f, -59f,
                    -48f, -40f,
                    -61f, -36f
            );

            canvas.drawPath(path, paint);

            // Тело.
            canvas.drawOval(
                    -51f, -18f,
                    32f, 27f,
                    paint
            );

            // Лапы: противоположные пары движутся навстречу.
            drawLeg(
                    canvas, -34f,
                    gait * 13f, alpha
            );

            drawLeg(
                    canvas, -17f,
                    -gait * 13f, alpha * 0.6f
            );

            drawLeg(
                    canvas, 16f,
                    -gait * 13f, alpha
            );

            drawLeg(
                    canvas, 29f,
                    gait * 13f, alpha * 0.6f
            );

            canvas.save();

            // Небольшой поворот головы — кот оглядывается.
            float glance = progress < 0.23f
                    ? (float) Math.sin(
                            clamp(
                                    (progress - 0.08f) / 0.15f
                            ) * Math.PI
                    ) * -11f
                    : 0f;

            canvas.rotate(glance, 37f, -20f);

            stroke(2.5f, alpha);

            // Голова и острые ушки.
            path.reset();
            path.moveTo(17f, -29f);
            path.lineTo(16f, -55f);
            path.lineTo(32f, -43f);
            path.quadTo(42f, -47f, 51f, -42f);
            path.lineTo(66f, -55f);
            path.lineTo(66f, -28f);
            path.cubicTo(
                    77f, -2f,
                    55f, 10f,
                    38f, 5f
            );
            path.cubicTo(
                    19f, 3f,
                    12f, -11f,
                    17f, -29f
            );

            canvas.drawPath(path, paint);

            // Глаза.
            stroke(2f, alpha);

            canvas.drawArc(
                    28f, -28f,
                    38f, -19f,
                    190f, 150f,
                    false, paint
            );

            canvas.drawArc(
                    48f, -28f,
                    58f, -19f,
                    190f, 150f,
                    false, paint
            );

            // Нос.
            fill(GOLD, alpha);

            path.reset();
            path.moveTo(42f, -17f);
            path.lineTo(49f, -17f);
            path.lineTo(45.5f, -13f);
            path.close();

            canvas.drawPath(path, paint);

            // Усы.
            stroke(1.3f, alpha);

            canvas.drawLine(
                    31f, -12f,
                    15f, -16f,
                    paint
            );

            canvas.drawLine(
                    31f, -7f,
                    14f, -6f,
                    paint
            );

            canvas.drawLine(
                    58f, -12f,
                    75f, -16f,
                    paint
            );

            canvas.drawLine(
                    58f, -7f,
                    76f, -6f,
                    paint
            );

            // Короткая линия от пасти к рыбке.
            canvas.drawLine(
                    45.5f, -13f,
                    45.5f, 1f,
                    paint
            );

            // Рыбка поперёк пасти.
            canvas.save();

            canvas.rotate(
                    gait * 7f,
                    46f, 7f
            );

            fill(BLACK, alpha);

            canvas.drawOval(
                    29f, 0f,
                    78f, 19f,
                    paint
            );

            stroke(2f, alpha);

            path.reset();
            path.moveTo(29f, 9f);
            path.cubicTo(
                    42f, -5f,
                    64f, -5f,
                    79f, 9f
            );
            path.cubicTo(
                    64f, 23f,
                    42f, 23f,
                    29f, 9f
            );
            path.lineTo(19f, 0f);
            path.lineTo(19f, 18f);
            path.close();

            canvas.drawPath(path, paint);

            fill(GOLD, alpha);

            canvas.drawCircle(
                    68f, 7f,
                    1.5f, paint
            );

            stroke(1.3f, alpha);

            canvas.drawArc(
                    55f, 1f,
                    66f, 17f,
                    100f, 155f,
                    false, paint
            );

            canvas.restore();
            canvas.restore();
            canvas.restore();
        }

        private void drawLeg(
                Canvas canvas,
                float x,
                float swing,
                float alpha
        ) {
            stroke(2.5f, alpha);

            path.reset();
            path.moveTo(x, 21f);
            path.lineTo(
                    x + swing * 0.5f,
                    34f
            );
            path.lineTo(
                    x + swing,
                    48f
            );
            path.lineTo(
                    x + swing + 9f,
                    48f
            );

            canvas.drawPath(path, paint);
        }

        private void drawLogo(Canvas canvas) {
            float alpha = clamp(
                    (progress - 0.64f) / 0.14f
            );

            if (alpha <= 0f) return;

            float size = 0.94f + alpha * 0.06f;

            canvas.save();
            canvas.scale(size, size);

            stroke(1.5f, alpha);

            canvas.drawCircle(
                    0f, -13f,
                    66f, paint
            );

            fill(GOLD, alpha);
            paint.setTypeface(
                    Typeface.create(
                            "serif",
                            Typeface.BOLD
                    )
            );

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(47f);

            float baseline = -13f -
                    (paint.ascent() + paint.descent()) / 2f;

            canvas.drawText(
                    "ЛВ", 0f,
                    baseline, paint
            );

            fill(WHITE, alpha);

            paint.setTypeface(
                    Typeface.create(
                            "sans-serif-medium",
                            Typeface.NORMAL
                    )
            );

            paint.setTextSize(23f);

            canvas.drawText(
                    "Лавка Вкуса",
                    0f, 91f, paint
            );

            canvas.restore();
        }
    }
}
// КОНЕЦ ФАЙЛА
