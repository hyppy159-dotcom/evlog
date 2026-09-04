package com.jhkim.evlog.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.jhkim.evlog.R;

import java.util.Locale;

/**
 * 전비 추이 선 차트.
 * 계열이 하나뿐이라 범례를 두지 않고 제목이 계열 이름을 대신합니다.
 * 값 라벨은 마지막 지점 하나에만 붙이고, 평균은 점선 기준선으로 둡니다.
 */
public class TrendChartView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path linePath = new Path();
    private final Path areaPath = new Path();

    private float[] values = new float[0];
    private String[] labels = new String[0];
    private String emptyText = "";

    public TrendChartView(Context c) {
        this(c, null);
    }

    public TrendChartView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        int series = ContextCompat.getColor(c, R.color.series1);
        int surface = ContextCompat.getColor(c, R.color.surface);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(series);

        areaPaint.setStyle(Paint.Style.FILL);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(series);

        // 점이 겹쳐도 서로 구분되도록 바탕색 링을 두릅니다.
        dotRingPaint.setStyle(Paint.Style.STROKE);
        dotRingPaint.setStrokeWidth(dp(2));
        dotRingPaint.setColor(surface);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(ContextCompat.getColor(c, R.color.grid));

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(dp(1));
        axisPaint.setColor(ContextCompat.getColor(c, R.color.axis));

        avgPaint.setStyle(Paint.Style.STROKE);
        avgPaint.setStrokeWidth(dp(1));
        avgPaint.setColor(ContextCompat.getColor(c, R.color.ink_muted));
        avgPaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));

        tickPaint.setColor(ContextCompat.getColor(c, R.color.ink_muted));
        tickPaint.setTextSize(sp(10));

        labelPaint.setColor(ContextCompat.getColor(c, R.color.ink_primary));
        labelPaint.setTextSize(sp(12));

        emptyPaint.setColor(ContextCompat.getColor(c, R.color.ink_muted));
        emptyPaint.setTextSize(sp(12));
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(float[] values, String[] labels) {
        this.values = values == null ? new float[0] : values;
        this.labels = labels == null ? new String[0] : labels;
        invalidate();
    }

    public void setEmptyText(String t) {
        this.emptyText = t == null ? "" : t;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();

        if (values.length < 2) {
            canvas.drawText(emptyText, w / 2f, h / 2f, emptyPaint);
            return;
        }

        float left = dp(40);
        float right = w - dp(46);
        float top = dp(14);
        float bottom = h - dp(22);
        float plotW = right - left;
        float plotH = bottom - top;
        if (plotW <= 0 || plotH <= 0) return;

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE, sum = 0;
        for (float v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        float avg = sum / values.length;

        // 축 범위를 조금 여유 있게 잡습니다.
        float span = Math.max(0.6f, max - min);
        float lo = Math.max(0f, min - span * 0.25f);
        float hi = max + span * 0.25f;
        if (hi - lo < 0.4f) hi = lo + 0.4f;

        // 가로 격자 + y축 눈금
        int lines = 4;
        for (int i = 0; i <= lines; i++) {
            float y = top + plotH * i / lines;
            canvas.drawLine(left, y, right, y, gridPaint);
            float v = hi - (hi - lo) * i / lines;
            canvas.drawText(String.format(Locale.KOREA, "%.1f", v), 0, y + sp(3.5f), tickPaint);
        }
        canvas.drawLine(left, bottom, right, bottom, axisPaint);

        // 좌표 계산
        int n = values.length;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = left + (n == 1 ? plotW / 2f : plotW * i / (n - 1));
            ys[i] = top + plotH * (hi - values[i]) / (hi - lo);
        }

        // 면적 채우기
        areaPath.reset();
        areaPath.moveTo(xs[0], bottom);
        for (int i = 0; i < n; i++) areaPath.lineTo(xs[i], ys[i]);
        areaPath.lineTo(xs[n - 1], bottom);
        areaPath.close();
        int series = ContextCompat.getColor(getContext(), R.color.series1);
        areaPaint.setShader(new LinearGradient(0, top, 0, bottom,
                (series & 0x00FFFFFF) | 0x40000000, (series & 0x00FFFFFF), Shader.TileMode.CLAMP));
        canvas.drawPath(areaPath, areaPaint);

        // 평균 기준선
        float avgY = top + plotH * (hi - avg) / (hi - lo);
        canvas.drawLine(left, avgY, right, avgY, avgPaint);
        canvas.drawText(String.format(Locale.KOREA, "평균 %.1f", avg),
                left + dp(4), avgY - dp(4), tickPaint);

        // 선
        linePath.reset();
        linePath.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) linePath.lineTo(xs[i], ys[i]);
        canvas.drawPath(linePath, linePaint);

        // 점
        float r = dp(4);
        for (int i = 0; i < n; i++) {
            canvas.drawCircle(xs[i], ys[i], r, dotRingPaint);
            canvas.drawCircle(xs[i], ys[i], r, dotPaint);
        }

        // 마지막 값만 직접 표시
        String lastLabel = String.format(Locale.KOREA, "%.1f", values[n - 1]);
        canvas.drawText(lastLabel, xs[n - 1] + dp(8), ys[n - 1] + sp(4), labelPaint);

        // x축: 처음과 마지막 라벨만
        if (labels.length == n) {
            canvas.drawText(labels[0], left, bottom + sp(13), tickPaint);
            float tw = tickPaint.measureText(labels[n - 1]);
            canvas.drawText(labels[n - 1], right - tw, bottom + sp(13), tickPaint);
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
