package ru.makeevka.fishshop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * Кот пробегает поверх магазина.
 * Нет отдельного экрана, затемнения и перехвата касаний.
 */
public final class CatWelcome {

    // LAVKA_CAT_24_HOURS_V1
    private static final String DAILY_PREFS =
            "lavka_cat_frequency_v1";
    private static final String LAST_RUN =
            "last_actual_run_ms";
    private static final long INTERVAL_MS =
            24L * 60L * 60L * 1000L;


    private final Activity activity;
    private final Runnable completed;

    private ViewGroup host;
    private CatScene scene;
    private ValueAnimator animator;

    private boolean ended;
    private long deadline;

    private final Runnable waitForStore = new Runnable() {
        @Override
        public void run() {
            checkAndStart();
        }
    };

    public CatWelcome(
            Activity activity,
            Runnable completed
    ) {
        this.activity = activity;
        this.completed = completed;
    }

    // Старое хранилище первого запуска больше не используется.
    public static boolean shouldShow(Activity activity) {
        if (activity == null ||
                activity.isFinishing() ||
                activity.isDestroyed() ||
                !ValueAnimator.areAnimatorsEnabled()) {
            return false;
        }

        long last = activity.getSharedPreferences(
                DAILY_PREFS, Activity.MODE_PRIVATE
        ).getLong(LAST_RUN, 0L);

        if (last <= 0L) return true;

        long now = System.currentTimeMillis();
        return now >= last &&
                now - last >= INTERVAL_MS;
    }

    // Пробежка никогда не блокирует магазин.
    public boolean isBlocking() {
        return false;
    }

    public void start() {
        if (ended) return;

        host = activity.findViewById(android.R.id.content);

        if (host == null || !shouldShow(activity)) {
            finish();
            return;
        }

        deadline = android.os.SystemClock.uptimeMillis()
                + 90000L;

        host.post(waitForStore);
    }

    private void checkAndStart() {
        if (ended) return;

        if (!shouldShow(activity) ||
                android.os.SystemClock.uptimeMillis() > deadline) {
            finish();
            return;
        }

        if (!(activity instanceof StoreActivity) ||
                !((StoreActivity) activity).isHomeForCat()) {
            finish();
            return;
        }

        boolean ready =
                host.isAttachedToWindow() &&
                host.getWidth() > 0 &&
                host.getHeight() > 0 &&
                activity.hasWindowFocus();

        // Ждём исчезновения загрузки и экрана защиты.
        // Основной интерфейс — первый дочерний элемент.
        for (int i = 1; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);

            if (child.getVisibility() == View.VISIBLE) {
                ready = false;
                break;
            }
        }

        if (!ready) {
            host.postDelayed(waitForStore, 250L);
            return;
        }

        // Count only an actual animation start.
        boolean saved = activity.getSharedPreferences(
                DAILY_PREFS, Activity.MODE_PRIVATE
        ).edit().putLong(
                LAST_RUN, System.currentTimeMillis()
        ).commit();

        if (!saved) {
            finish();
            return;
        }

        scene = new CatScene();
        scene.layout(
                0, 0,
                host.getWidth(),
                host.getHeight()
        );

        host.getOverlay().add(scene);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2600L);
        animator.setInterpolator(new LinearInterpolator());

        animator.addUpdateListener(value -> {
            if (scene == null || ended) return;

            scene.progress =
                    (float) value.getAnimatedValue();

            scene.invalidate();
        });

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finish();
            }
        });

        animator.start();
    }

    public void finish() {
        if (ended) return;
        ended = true;
        remove();

        if (completed != null &&
                !activity.isFinishing() &&
                !activity.isDestroyed()) {
            completed.run();
        }
    }

    public void dispose() {
        if (ended) return;
        ended = true;
        remove();
    }

    private void remove() {
        if (host != null) {
            host.removeCallbacks(waitForStore);
        }

        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }

        if (host != null && scene != null) {
            host.getOverlay().remove(scene);
        }

        scene = null;
    }

    private final class CatScene extends View {

        private final Paint paint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Path path = new Path();

        private final int gold = Color.rgb(212, 175, 55);
        private final int dark = Color.rgb(25, 27, 30);

        private float progress;

        CatScene() {
            super(activity);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(
                    IMPORTANT_FOR_ACCESSIBILITY_NO
            );
        }

        private void stroke(float width, int alpha) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(gold);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(width);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        private void fill(int color, int alpha) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setAlpha(alpha);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float density = getResources()
                    .getDisplayMetrics().density;

            float scale = Math.min(
                    density * 0.9f,
                    getWidth() / 280f
            );

            float width = 170f * scale;
            float x = -width +
                    (getWidth() + width * 2f) * progress;

            float gait = (float) Math.sin(
                    progress * Math.PI * 18f
            );

            float y = getHeight() * 0.38f;

            canvas.save();
            canvas.translate(x, y);
            canvas.scale(scale, scale);

            fill(gold, 22);
            canvas.drawOval(
                    -55f, 46f, 80f, 54f, paint
            );

            canvas.translate(
                    0f, -Math.abs(gait) * 4f
            );

            // След.
            for (int i = 0; i < 3; i++) {
                stroke(1.4f, 75 - i * 18);
                canvas.drawLine(
                        -90f - i * 7f,
                        -5f + i * 11f,
                        -67f,
                        -5f + i * 11f,
                        paint
                );
            }

            stroke(2.5f, 255);

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

            // Тело с тёмной заливкой.
            fill(dark, 245);
            canvas.drawOval(
                    -51f, -18f, 32f, 27f, paint
            );

            stroke(2.5f, 255);
            canvas.drawOval(
                    -51f, -18f, 32f, 27f, paint
            );

            leg(canvas, -34f, gait * 14f);
            leg(canvas, -17f, -gait * 14f);
            leg(canvas, 16f, -gait * 14f);
            leg(canvas, 29f, gait * 14f);

            // Голова с ушами.
            path.reset();
            path.moveTo(17f, -29f);
            path.lineTo(16f, -55f);
            path.lineTo(32f, -43f);
            path.quadTo(42f, -47f, 51f, -42f);
            path.lineTo(66f, -55f);
            path.lineTo(66f, -28f);
            path.cubicTo(
                    77f, -2f, 55f, 10f, 38f, 5f
            );
            path.cubicTo(
                    19f, 3f, 12f, -11f, 17f, -29f
            );
            path.close();

            fill(dark, 255);
            canvas.drawPath(path, paint);
            stroke(2.5f, 255);
            canvas.drawPath(path, paint);

            // Глаза и нос.
            fill(gold, 255);
            canvas.drawCircle(33f, -24f, 2f, paint);
            canvas.drawCircle(53f, -24f, 2f, paint);

            path.reset();
            path.moveTo(42f, -17f);
            path.lineTo(49f, -17f);
            path.lineTo(45.5f, -13f);
            path.close();
            canvas.drawPath(path, paint);

            stroke(1.3f, 255);

            canvas.drawLine(31f, -12f, 15f, -16f, paint);
            canvas.drawLine(31f, -7f, 14f, -6f, paint);
            canvas.drawLine(58f, -12f, 75f, -16f, paint);
            canvas.drawLine(58f, -7f, 76f, -6f, paint);
            canvas.drawLine(45.5f, -13f, 45.5f, 1f, paint);

            // Рыбка в зубах.
            canvas.save();
            canvas.rotate(gait * 7f, 46f, 7f);

            path.reset();
            path.moveTo(29f, 9f);
            path.cubicTo(
                    42f, -5f, 64f, -5f, 79f, 9f
            );
            path.cubicTo(
                    64f, 23f, 42f, 23f, 29f, 9f
            );
            path.lineTo(19f, 0f);
            path.lineTo(19f, 18f);
            path.close();

            fill(dark, 255);
            canvas.drawPath(path, paint);
            stroke(2f, 255);
            canvas.drawPath(path, paint);

            fill(gold, 255);
            canvas.drawCircle(68f, 7f, 1.5f, paint);

            canvas.restore();
            canvas.restore();
        }

        private void leg(
                Canvas canvas,
                float x,
                float swing
        ) {
            stroke(2.5f, 255);
            path.reset();
            path.moveTo(x, 21f);
            path.lineTo(x + swing * 0.5f, 34f);
            path.lineTo(x + swing, 48f);
            path.lineTo(x + swing + 9f, 48f);
            canvas.drawPath(path, paint);
        }
    }
}
// КОНЕЦ ФАЙЛА
