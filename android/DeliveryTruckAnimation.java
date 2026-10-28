package ru.makeevka.fishshop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * Декоративная анимация доставки.
 * Не меняет корзину, данные покупателя и условия доставки.
 */
public final class DeliveryTruckAnimation {

    private DeliveryTruckAnimation() {
    }

    public static void install(ViewGroup card) {
        if (card == null) return;

        final Ride[] active = new Ride[1];

        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(
                "Доставка бесплатно от 2 000 рублей. " +
                "Нажмите, чтобы увидеть машинку."
        );

        card.setOnClickListener(view -> {
            if (active[0] != null ||
                    !card.isAttachedToWindow() ||
                    card.getWidth() <= 0 ||
                    card.getHeight() <= 0) {
                return;
            }

            if (!ValueAnimator.areAnimatorsEnabled()) {
                card.announceForAccessibility(
                        "Доставка бесплатно от 2 000 рублей"
                );
                return;
            }

            Ride ride = new Ride(
                    card.getContext(),
                    card,
                    () -> active[0] = null
            );

            active[0] = ride;
            ride.start();
        });
    }

    private static final class Ride extends View {

        private static final int GOLD =
                Color.rgb(212, 175, 55);

        private static final int DARK =
                Color.rgb(25, 27, 30);

        private final ViewGroup host;
        private final Runnable completed;

        private final Paint paint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Path path = new Path();

        private final OnAttachStateChangeListener hostListener =
                new OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        finish();
                    }
                };

        private ValueAnimator animator;
        private float progress;
        private boolean finished;

        Ride(
                Context context,
                ViewGroup host,
                Runnable completed
        ) {
            super(context);

            this.host = host;
            this.completed = completed;

            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(
                    IMPORTANT_FOR_ACCESSIBILITY_NO
            );
        }

        void start() {
            layout(
                    0,
                    0,
                    host.getWidth(),
                    host.getHeight()
            );

            host.addOnAttachStateChangeListener(hostListener);
            host.getOverlay().add(this);

            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(2800L);
            animator.setInterpolator(
                    new LinearInterpolator()
            );

            animator.addUpdateListener(value -> {
                progress = (float) value.getAnimatedValue();
                invalidate();
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

        private float clamp(float value) {
            return Math.max(0f, Math.min(1f, value));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (finished) return;

            float visibility = Math.min(
                    clamp(progress / 0.12f),
                    clamp((1f - progress) / 0.16f)
            );

            float radius =
                    18f * getResources()
                            .getDisplayMetrics().density;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(DARK);
            paint.setAlpha(
                    Math.round(255f * visibility)
            );

            canvas.drawRoundRect(
                    0f,
                    0f,
                    getWidth(),
                    getHeight(),
                    radius,
                    radius,
                    paint
            );

            float density = getResources()
                    .getDisplayMetrics().density;

            float scale = Math.min(
                    density,
                    getHeight() / 62f
            );

            float truckWidth = 104f * scale;

            float travel = clamp(
                    (progress - 0.08f) / 0.84f
            );

            // Плавный разгон и торможение.
            float eased = travel * travel *
                    (3f - 2f * travel);

            float x = -truckWidth +
                    (getWidth() + truckWidth) * eased;

            float y = getHeight() / 2f;

            canvas.save();
            canvas.translate(x, y);
            canvas.scale(scale, scale);

            paint.setAlpha(255);
            drawTruck(canvas, visibility);

            canvas.restore();
        }

        private void stroke(
                float width,
                float opacity
        ) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(GOLD);
            paint.setAlpha(
                    Math.round(255f * clamp(opacity))
            );
            paint.setStrokeWidth(width);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        private void drawTruck(
                Canvas canvas,
                float visibility
        ) {
            float bounce = (float) Math.sin(
                    progress * Math.PI * 16
            ) * 0.65f;

            canvas.save();
            canvas.translate(0f, bounce);

            // Лёгкий золотой след.
            for (int i = 0; i < 3; i++) {
                stroke(
                        1.5f,
                        visibility * (0.35f - i * 0.08f)
                );

                float end = -5f - i * 5f;
                float length = 12f + i * 5f;

                canvas.drawLine(
                        end - length,
                        -8f + i * 9f,
                        end,
                        -8f + i * 9f,
                        paint
                );
            }

            stroke(2f, visibility);

            // Кузов.
            canvas.drawRoundRect(
                    0f, -23f,
                    61f, 13f,
                    5f, 5f,
                    paint
            );

            // Кабина.
            path.reset();
            path.moveTo(61f, -14f);
            path.lineTo(80f, -14f);
            path.lineTo(94f, 0f);
            path.lineTo(94f, 13f);
            path.lineTo(61f, 13f);
            path.close();
            canvas.drawPath(path, paint);

            // Окно кабины.
            path.reset();
            path.moveTo(68f, -8f);
            path.lineTo(77f, -8f);
            path.lineTo(85f, 0f);
            path.lineTo(68f, 0f);
            path.close();
            canvas.drawPath(path, paint);

            canvas.drawLine(
                    68f, 6f,
                    73f, 6f,
                    paint
            );

            // Фара.
            canvas.drawLine(
                    90f, 6f,
                    94f, 6f,
                    paint
            );

            // Золотая рыбка на кузове.
            path.reset();
            path.moveTo(19f, -6f);
            path.cubicTo(
                    27f, -17f,
                    40f, -17f,
                    48f, -6f
            );
            path.cubicTo(
                    40f, 5f,
                    27f, 5f,
                    19f, -6f
            );
            path.lineTo(12f, -13f);
            path.lineTo(12f, 1f);
            path.close();

            canvas.drawPath(path, paint);

            canvas.drawCircle(
                    40f, -7f,
                    0.9f, paint
            );

            canvas.drawLine(
                    29f, -14f,
                    26f, -18f,
                    paint
            );

            // Колёса вращаются во время поездки.
            drawWheel(
                    canvas,
                    18f,
                    15f,
                    progress * 1440f,
                    visibility
            );

            drawWheel(
                    canvas,
                    77f,
                    15f,
                    progress * 1440f,
                    visibility
            );

            canvas.restore();
        }

        private void drawWheel(
                Canvas canvas,
                float x,
                float y,
                float angle,
                float visibility
        ) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(DARK);
            paint.setAlpha(
                    Math.round(255f * visibility)
            );

            canvas.drawCircle(x, y, 8f, paint);

            stroke(2f, visibility);
            canvas.drawCircle(x, y, 7f, paint);

            canvas.save();
            canvas.rotate(angle, x, y);

            stroke(1.3f, visibility);

            canvas.drawLine(
                    x - 4f, y,
                    x + 4f, y,
                    paint
            );

            canvas.drawLine(
                    x, y - 4f,
                    x, y + 4f,
                    paint
            );

            canvas.restore();
        }

        @Override
        protected void onWindowVisibilityChanged(
                int visibility
        ) {
            super.onWindowVisibilityChanged(visibility);

            if (visibility != VISIBLE) {
                finish();
            }
        }

        private void finish() {
            if (finished) return;
            finished = true;

            host.removeOnAttachStateChangeListener(
                    hostListener
            );

            if (animator != null) {
                animator.removeAllUpdateListeners();
                animator.removeAllListeners();
                animator.cancel();
                animator = null;
            }

            host.getOverlay().remove(this);
            completed.run();
        }
    }
}
// КОНЕЦ ФАЙЛА
