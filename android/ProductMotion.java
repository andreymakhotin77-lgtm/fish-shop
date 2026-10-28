package ru.makeevka.fishshop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/*
 * Декоративные эффекты товаров.
 *
 * Не меняет корзину, цены и заказы.
 * Не выполняет сетевые запросы.
 *
 * Класс работает с миниатюрой уже показанного View,
 * а не со снимком всего экрана.
 */
public final class ProductMotion {

    private final Activity activity;

    private final Map<String, WeakReference<View>> photos =
            new HashMap<>();

    private ValueAnimator flightAnimator;
    private ImageView flyingImage;
    private FrameLayout flightHost;
    private Flight activeFlight;

    private boolean closed;

    public ProductMotion(Activity activity) {
        this.activity = activity;
    }

    /*
     * Снимок миниатюры до перерисовки каталога.
     * После использования освобождается.
     */
    public static final class Flight {

        private Bitmap bitmap;
        private final float centerX;
        private final float centerY;

        private Flight(
                Bitmap bitmap,
                float centerX,
                float centerY
        ) {
            this.bitmap = bitmap;
            this.centerX = centerX;
            this.centerY = centerY;
        }

        public void release() {
            Bitmap previous = bitmap;
            bitmap = null;

            if (previous != null &&
                    !previous.isRecycled()) {
                previous.recycle();
            }
        }
    }

    // ==================== КАРТОЧКИ ====================

    public void bind(
            String productId,
            View card,
            View photo
    ) {
        if (closed ||
                productId == null ||
                card == null ||
                photo == null) {
            return;
        }

        photos.put(
                productId,
                new WeakReference<>(photo)
        );

        /*
         * Меняем только обработку касания.
         * Обработчики нажатий остаются прежними.
         */
        TiltTouch tilt = new TiltTouch(card);
        attachToClickableViews(card, tilt);

        card.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {

                    @Override
                    public void onViewAttachedToWindow(
                            View view
                    ) {
                    }

                    @Override
                    public void onViewDetachedFromWindow(
                            View view
                    ) {
                        tilt.resetImmediately();
                    }
                }
        );
    }

    private void attachToClickableViews(
            View view,
            TiltTouch tilt
    ) {
        if (view.isClickable()) {
            view.setOnTouchListener(tilt);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            for (int i = 0;
                    i < group.getChildCount();
                    i++) {
                attachToClickableViews(
                        group.getChildAt(i),
                        tilt
                );
            }
        }
    }

    private final class TiltTouch
            implements View.OnTouchListener {

        private final View card;
        private final int touchSlop;

        private float downX;
        private float downY;
        private boolean tracking;

        TiltTouch(View card) {
            this.card = card;

            touchSlop = ViewConfiguration
                    .get(card.getContext())
                    .getScaledTouchSlop();

            card.setCameraDistance(
                    8000f *
                    card.getResources()
                            .getDisplayMetrics().density
            );
        }

        @Override
        public boolean onTouch(
                View touched,
                MotionEvent event
        ) {
            if (closed ||
                    !ValueAnimator.areAnimatorsEnabled()) {
                resetImmediately();
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tracking = true;

                    downX = event.getRawX();
                    downY = event.getRawY();

                    int[] location = new int[2];
                    card.getLocationOnScreen(location);

                    float width = Math.max(
                            1f,
                            card.getWidth()
                    );

                    float height = Math.max(
                            1f,
                            card.getHeight()
                    );

                    float x = clamp(
                            (downX - location[0]) / width,
                            0f,
                            1f
                    );

                    float y = clamp(
                            (downY - location[1]) / height,
                            0f,
                            1f
                    );

                    card.animate().cancel();

                    card.animate()
                            .rotationX((0.5f - y) * 4f)
                            .rotationY((x - 0.5f) * 4f)
                            .scaleX(0.995f)
                            .scaleY(0.995f)
                            .setDuration(130L)
                            .setInterpolator(
                                    new DecelerateInterpolator()
                            )
                            .start();

                    break;

                case MotionEvent.ACTION_MOVE:
                    if (tracking &&
                            (
                                Math.abs(
                                        event.getRawX() - downX
                                ) > touchSlop ||
                                Math.abs(
                                        event.getRawY() - downY
                                ) > touchSlop
                            )) {
                        tracking = false;
                        restore();
                    }

                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    tracking = false;
                    restore();
                    break;

                default:
                    break;
            }

            /*
             * false важно:
             * не забираем событие у кнопок и ScrollView.
             */
            return false;
        }

        private void restore() {
            card.animate().cancel();

            card.animate()
                    .rotationX(0f)
                    .rotationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(
                            new DecelerateInterpolator()
                    )
                    .start();
        }

        private void resetImmediately() {
            tracking = false;

            card.animate().cancel();
            card.setRotationX(0f);
            card.setRotationY(0f);
            card.setScaleX(1f);
            card.setScaleY(1f);
        }
    }

    // ==================== МИНИАТЮРА ====================

    public Flight prepare(String productId) {
        if (productId == null) {
            return null;
        }

        WeakReference<View> reference =
                photos.get(productId);

        return prepare(
                reference == null
                        ? null
                        : reference.get()
        );
    }

    public Flight prepare(View source) {
        if (closed ||
                source == null ||
                !source.isAttachedToWindow() ||
                !source.isShown() ||
                !ValueAnimator.areAnimatorsEnabled() ||
                source.getWidth() <= 0 ||
                source.getHeight() <= 0) {
            return null;
        }

        Rect visible = new Rect();

        if (!source.getGlobalVisibleRect(visible) ||
                visible.isEmpty()) {
            return null;
        }

        int[] screenLocation = new int[2];
        source.getLocationOnScreen(screenLocation);

        Bitmap bitmap = null;

        try {
            float ratio = Math.min(
                    1f,
                    Math.min(
                            192f / source.getWidth(),
                            192f / source.getHeight()
                    )
            );

            int width = Math.max(
                    1,
                    Math.round(source.getWidth() * ratio)
            );

            int height = Math.max(
                    1,
                    Math.round(source.getHeight() * ratio)
            );

            bitmap = Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888
            );

            Canvas canvas = new Canvas(bitmap);
            canvas.scale(
                    (float) width / source.getWidth(),
                    (float) height / source.getHeight()
            );

            source.draw(canvas);

            return new Flight(
                    bitmap,
                    screenLocation[0] +
                            source.getWidth() / 2f,
                    screenLocation[1] +
                            source.getHeight() / 2f
            );

        } catch (RuntimeException error) {
            if (bitmap != null &&
                    !bitmap.isRecycled()) {
                bitmap.recycle();
            }

            return null;
        }
    }

    // ==================== ПЕРЕЛЁТ К КОРЗИНЕ ====================

    public void play(
            Flight flight,
            View target
    ) {
        if (flight == null) {
            return;
        }

        stopFlight();

        if (closed ||
                target == null ||
                !target.isAttachedToWindow() ||
                !target.isShown() ||
                activity.isFinishing() ||
                activity.isDestroyed() ||
                !ValueAnimator.areAnimatorsEnabled()) {
            flight.release();
            return;
        }

        FrameLayout host = activity.findViewById(
                android.R.id.content
        );

        if (host == null ||
                flight.bitmap == null ||
                flight.bitmap.isRecycled()) {
            flight.release();
            return;
        }

        int[] hostLocation = new int[2];
        int[] targetLocation = new int[2];

        host.getLocationOnScreen(hostLocation);
        target.getLocationOnScreen(targetLocation);

        final float startX =
                flight.centerX - hostLocation[0];

        final float startY =
                flight.centerY - hostLocation[1];

        final float endX =
                targetLocation[0] - hostLocation[0] +
                target.getWidth() / 2f;

        final float endY =
                targetLocation[1] - hostLocation[1] +
                target.getHeight() / 2f;

        final float controlX =
                (startX + endX) / 2f;

        final float controlY =
                Math.min(startY, endY) - dp(65);

        final int size = dp(58);

        ImageView image = new ImageView(activity);
        image.setImageBitmap(flight.bitmap);
        image.setScaleType(
                ImageView.ScaleType.CENTER_CROP
        );

        image.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
        );

        image.setClickable(false);
        image.setFocusable(false);

        image.setLayoutParams(
                new FrameLayout.LayoutParams(size, size)
        );

        image.layout(0, 0, size, size);

        image.setTranslationX(
                startX - size / 2f
        );

        image.setTranslationY(
                startY - size / 2f
        );

        /*
         * Overlay декоративный:
         * не блокирует кнопки магазина.
         */
        host.getOverlay().add(image);

        activeFlight = flight;
        flyingImage = image;
        flightHost = host;

        ValueAnimator animator =
                ValueAnimator.ofFloat(0f, 1f);

        flightAnimator = animator;

        animator.setDuration(480L);
        animator.setInterpolator(
                new DecelerateInterpolator()
        );

        animator.addUpdateListener(value -> {
            if (closed ||
                    flyingImage != image) {
                return;
            }

            float t =
                    (float) value.getAnimatedValue();

            float inverse = 1f - t;

            float x =
                    inverse * inverse * startX +
                    2f * inverse * t * controlX +
                    t * t * endX;

            float y =
                    inverse * inverse * startY +
                    2f * inverse * t * controlY +
                    t * t * endY;

            image.setTranslationX(
                    x - size / 2f
            );

            image.setTranslationY(
                    y - size / 2f
            );

            float scale = 1f - 0.68f * t;

            image.setScaleX(scale);
            image.setScaleY(scale);

            image.setAlpha(
                    t < 0.75f
                            ? 1f
                            : Math.max(
                                    0f,
                                    (1f - t) / 0.25f
                            )
            );
        });

        animator.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(
                            Animator animation
                    ) {
                        if (flightAnimator == animation) {
                            flightAnimator = null;
                            clearFlightImage();
                        }
                    }
                }
        );

        animator.start();
    }

    public void stopFlight() {
        ValueAnimator previous = flightAnimator;
        flightAnimator = null;

        if (previous != null) {
            previous.removeAllUpdateListeners();
            previous.removeAllListeners();
            previous.cancel();
        }

        clearFlightImage();
    }

    private void clearFlightImage() {
        if (flyingImage != null) {
            if (flightHost != null) {
                flightHost.getOverlay().remove(
                        flyingImage
                );
            }

            flyingImage.setImageDrawable(null);
        }

        flyingImage = null;
        flightHost = null;

        if (activeFlight != null) {
            activeFlight.release();
            activeFlight = null;
        }
    }

    public void close() {
        closed = true;
        stopFlight();
        photos.clear();
    }

    private int dp(int value) {
        return Math.round(
                value *
                activity.getResources()
                        .getDisplayMetrics().density
        );
    }

    private static float clamp(
            float value,
            float minimum,
            float maximum
    ) {
        return Math.max(
                minimum,
                Math.min(maximum, value)
        );
    }
}

// КОНЕЦ ФАЙЛА
