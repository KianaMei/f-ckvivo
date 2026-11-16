package dev.ganvivo.dragall;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

final class SystemShareIcon extends Drawable {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint symbol = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;

    SystemShareIcon(float density) {
        size = Math.round(48 * density);
        background.setShader(new LinearGradient(0, 0, 48, 48,
                Color.rgb(65, 147, 235), Color.rgb(67, 94, 209), Shader.TileMode.CLAMP));
        symbol.setColor(Color.WHITE);
        symbol.setStrokeWidth(2.8f);
        symbol.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float edge = Math.min(bounds.width(), bounds.height());
        if (edge <= 0) return;
        int save = canvas.save();
        canvas.translate(bounds.exactCenterX() - edge / 2, bounds.exactCenterY() - edge / 2);
        canvas.scale(edge / 48, edge / 48);
        canvas.drawRoundRect(0, 0, 48, 48, 13, 13, background);
        canvas.drawLine(15, 24, 33, 14, symbol);
        canvas.drawLine(15, 24, 33, 34, symbol);
        canvas.drawCircle(15, 24, 3.5f, symbol);
        canvas.drawCircle(33, 14, 3.5f, symbol);
        canvas.drawCircle(33, 34, 3.5f, symbol);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        background.setAlpha(alpha);
        symbol.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        background.setColorFilter(filter);
        symbol.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }
}
