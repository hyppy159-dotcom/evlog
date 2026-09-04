package com.jhkim.evlog.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.jhkim.evlog.R;

import java.util.Locale;

/**
 * 배터리 잔량 게이지.
 * 하나의 값을 한계(100%)에 견주어 보여주는 미터라서, 같은 계열 트랙 위에
 * 한 가지 색으로 채웁니다. 잔량이 낮을 때만 상태색으로 바뀌고,
 * 색만으로 알리지 않도록 아래에 문구가 함께 나옵니다.
 */
public class BatteryGaugeView extends View {

    private static final float START_ANGLE = 135f;
    private static final float SWEEP = 270f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private float soc = -1f;
    private String caption = "";

    public BatteryGaugeView(Context c) {
        this(c, null);
    }

    public BatteryGaugeView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        float stroke = dp(14);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(ContextCompat.getColor(c, R.color.grid));

        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeWidth(stroke);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setColor(ContextCompat.getColor(c, R.color.series1));

        valuePaint.setColor(ContextCompat.getColor(c, R.color.ink_primary));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        valuePaint.setTextSize(sp(34));

        unitPaint.setColor(ContextCompat.getColor(c, R.color.ink_secondary));
        unitPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setTextSize(sp(13));

        captionPaint.setColor(ContextCompat.getColor(c, R.color.ink_muted));
        captionPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setTextSize(sp(12));
    }

    /** @param socPct 0~100, 모르면 음수 */
    public void setSoc(float socPct) {
        this.soc = socPct;
        if (socPct < 0) {
            caption = "잔량 정보 없음";
            fillPaint.setColor(ContextCompat.getColor(getContext(), R.color.grid));
        } else if (socPct < 10) {
            caption = "충전 필요";
            fillPaint.setColor(ContextCompat.getColor(getContext(), R.color.status_critical));
        } else if (socPct < 20) {
            caption = "잔량 부족";
            fillPaint.setColor(ContextCompat.getColor(getContext(), R.color.status_warning));
        } else {
            caption = "배터리 잔량";
            fillPaint.setColor(ContextCompat.getColor(getContext(), R.color.series1));
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int size = (int) dp(160);
        int w = resolveSize(size, widthSpec);
        int h = resolveSize(size, heightSpec);
        int side = Math.min(w, h);
        setMeasuredDimension(side, side);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = dp(12);
        float side = Math.min(getWidth(), getHeight());
        arcRect.set(pad, pad, side - pad, side - pad);

        canvas.drawArc(arcRect, START_ANGLE, SWEEP, false, trackPaint);

        if (soc >= 0) {
            float ratio = Math.max(0f, Math.min(1f, soc / 100f));
            // 0%일 때도 둥근 끝이 보이지 않도록 아주 작은 값은 그리지 않습니다.
            if (ratio > 0.005f) {
                canvas.drawArc(arcRect, START_ANGLE, SWEEP * ratio, false, fillPaint);
            }
        }

        float cx = side / 2f;
        float cy = side / 2f;

        String value = soc >= 0 ? String.format(Locale.KOREA, "%.0f", soc) : "—";
        canvas.drawText(value, cx, cy + sp(6), valuePaint);
        if (soc >= 0) {
            canvas.drawText("%", cx, cy + sp(24), unitPaint);
            canvas.drawText(caption, cx, cy + sp(42), captionPaint);
        } else {
            canvas.drawText(caption, cx, cy + sp(26), captionPaint);
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }
}
