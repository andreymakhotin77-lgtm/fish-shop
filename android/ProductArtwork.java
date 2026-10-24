package ru.makeevka.fishshop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

public final class ProductArtwork extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean crayfish;

    private final int gold = Color.rgb(212, 175, 55);
    private final int softGold = Color.rgb(110, 91, 35);
    private final int background = Color.rgb(23, 23, 23);

    public ProductArtwork(
            Context context,
            String productName,
            String category
    ) {
        super(context);

        String label = (
                productName + " " + category
        ).toLowerCase(java.util.Locale.ROOT);

        crayfish = label.contains("раки")
                || label.contains("рак ")
                || label.startsWith("рак");

        setContentDescription(
                crayfish
                        ? "Декоративная иллюстрация рака, не фото товара"
                        : "Декоративная иллюстрация рыбы, не фото товара"
        );

        setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(background);

        float radius = 16f * getResources()
                .getDisplayMetrics().density;

        canvas.drawRoundRect(
                new RectF(0, 0, getWidth(), getHeight()),
                radius,
                radius,
                paint
        );

        canvas.save();

        float scale = Math.min(
                getWidth() / 320f,
                getHeight() / 170f
        );

        canvas.translate(
                (getWidth() - 320f * scale) / 2f,
                (getHeight() - 170f * scale) / 2f
        );

        canvas.scale(scale, scale);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(1.2f);
        paint.setColor(softGold);

        canvas.drawCircle(160, 80, 61, paint);
        canvas.drawCircle(160, 80, 69, paint);

        paint.setStrokeWidth(3f);
        paint.setColor(gold);

        if (crayfish) {
            drawCrayfish(canvas);
        } else {
            drawFish(canvas);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(softGold);
        paint.setTextSize(10);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("ЛАВКА ВКУСА", 160, 157, paint);

        canvas.restore();
    }

    private void line(
            Canvas canvas,
            float x1, float y1,
            float x2, float y2
    ) {
        canvas.drawLine(x1, y1, x2, y2, paint);
    }

    private void drawFish(Canvas canvas) {
        Path body = new Path();
        body.moveTo(100, 82);
        body.cubicTo(132, 43, 193, 40, 225, 82);
        body.cubicTo(193, 124, 132, 121, 100, 82);
        body.close();
        canvas.drawPath(body, paint);

        Path tail = new Path();
        tail.moveTo(102, 82);
        tail.lineTo(72, 58);
        tail.lineTo(79, 82);
        tail.lineTo(72, 106);
        tail.close();
        canvas.drawPath(tail, paint);

        Path topFin = new Path();
        topFin.moveTo(139, 57);
        topFin.lineTo(163, 34);
        topFin.lineTo(180, 54);
        canvas.drawPath(topFin, paint);

        Path bottomFin = new Path();
        bottomFin.moveTo(143, 109);
        bottomFin.lineTo(164, 128);
        bottomFin.lineTo(181, 109);
        canvas.drawPath(bottomFin, paint);

        Path gill = new Path();
        gill.moveTo(192, 61);
        gill.quadTo(180, 82, 192, 103);
        canvas.drawPath(gill, paint);

        line(canvas, 128, 82, 161, 82);
        line(canvas, 145, 70, 161, 82);
        line(canvas, 145, 94, 161, 82);

        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(207, 77, 3.3f, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    private void drawCrayfish(Canvas canvas) {
        canvas.drawOval(
                new RectF(146, 56, 174, 96),
                paint
        );

        canvas.drawRoundRect(
                new RectF(148, 95, 172, 122),
                7, 7, paint
        );

        line(canvas, 150, 103, 170, 103);
        line(canvas, 150, 112, 170, 112);

        Path tail = new Path();
        tail.moveTo(151, 122);
        tail.lineTo(139, 136);
        tail.lineTo(160, 131);
        tail.lineTo(181, 136);
        tail.lineTo(169, 122);
        canvas.drawPath(tail, paint);

        for (int side : new int[]{-1, 1}) {
            for (int i = 0; i < 3; i++) {
                float y = 77 + i * 11;
                line(canvas, 160 + side * 13, y,
                        160 + side * 35, y + 3);
                line(canvas, 160 + side * 35, y + 3,
                        160 + side * 44, y + 14);
            }

            line(canvas, 160 + side * 13, 64,
                    160 + side * 35, 49);
            line(canvas, 160 + side * 35, 49,
                    160 + side * 43, 35);

            Path claw = new Path();
            claw.moveTo(160 + side * 43, 35);
            claw.cubicTo(
                    160 + side * 65, 30,
                    160 + side * 62, 9,
                    160 + side * 47, 17
            );
            claw.lineTo(160 + side * 45, 27);
            claw.lineTo(160 + side * 38, 17);
            claw.cubicTo(
                    160 + side * 27, 13,
                    160 + side * 26, 31,
                    160 + side * 43, 35
            );
            canvas.drawPath(claw, paint);

            line(canvas, 160 + side * 6, 57,
                    160 + side * 14, 38);
        }

        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(153, 61, 2.5f, paint);
        canvas.drawCircle(167, 61, 2.5f, paint);
        paint.setStyle(Paint.Style.STROKE);
    }
}
// КОНЕЦ ФАЙЛА
