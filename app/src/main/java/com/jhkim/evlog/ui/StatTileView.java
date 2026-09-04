package com.jhkim.evlog.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.jhkim.evlog.R;

/** 라벨 + 큰 숫자 한 칸. 차트가 필요 없는 단일 수치에 씁니다. */
public class StatTileView extends LinearLayout {

    private final TextView labelView;
    private final TextView valueView;

    public StatTileView(Context c) {
        this(c, null);
    }

    public StatTileView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        setOrientation(VERTICAL);

        labelView = new TextView(c);
        labelView.setTextColor(ContextCompat.getColor(c, R.color.ink_muted));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        labelView.setSingleLine(true);

        valueView = new TextView(c);
        valueView.setTextColor(ContextCompat.getColor(c, R.color.ink_primary));
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        valueView.setSingleLine(true);

        addView(labelView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams vp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        vp.topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2,
                getResources().getDisplayMetrics());
        addView(valueView, vp);
    }

    public void set(String label, String value) {
        labelView.setText(label);
        valueView.setText(value);
    }
}
