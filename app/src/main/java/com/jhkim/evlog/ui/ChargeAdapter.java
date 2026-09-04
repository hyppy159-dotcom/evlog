package com.jhkim.evlog.ui;

import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.jhkim.evlog.R;
import com.jhkim.evlog.db.Charge;
import com.jhkim.evlog.util.Fmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChargeAdapter extends RecyclerView.Adapter<ChargeAdapter.VH> {

    public interface OnLongPress {
        void onLongPress(Charge charge);
    }

    private final List<Charge> items = new ArrayList<>();
    private final OnLongPress longPress;

    public ChargeAdapter(OnLongPress longPress) {
        this.longPress = longPress;
    }

    public void setItems(List<Charge> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_charge, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final Charge c = items.get(position);

        h.date.setText(Fmt.dateTime(c.startTs));

        // 완속/급속은 색만이 아니라 항상 글자로도 표시합니다.
        h.kind.setText(c.isDc() ? "급속 (DC)" : "완속 (AC)");
        int color = ContextCompat.getColor(h.itemView.getContext(),
                c.isDc() ? R.color.series2 : R.color.series1);
        if (h.dot.getBackground() != null) {
            h.dot.getBackground().mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }

        h.kwh.setText(String.format(Locale.KOREA, "%.1f", c.kwh()) + " kWh");
        h.cost.setText(Fmt.won(c.cost));

        StringBuilder sb = new StringBuilder();
        long min = c.minutes();
        if (min >= 0) sb.append(min).append("분");
        if (c.maxKw > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(String.format(Locale.KOREA, "최대 %.1f kW", c.maxKw));
        }
        if (c.startSoc >= 0 && c.endSoc >= 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(String.format(Locale.KOREA, "%.0f%% → %.0f%%", c.startSoc, c.endSoc));
        }
        if (c.manual) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("직접 입력");
        }
        if (c.note != null && !c.note.isEmpty()) {
            sb.append("\n").append(c.note);
        }
        h.detail.setText(sb.toString());

        h.itemView.setOnLongClickListener(v -> {
            if (longPress != null) longPress.onLongPress(c);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView date, kind, kwh, cost, detail;
        final View dot;

        VH(@NonNull View v) {
            super(v);
            date = v.findViewById(R.id.txt_date);
            kind = v.findViewById(R.id.txt_kind);
            kwh = v.findViewById(R.id.txt_kwh);
            cost = v.findViewById(R.id.txt_cost);
            detail = v.findViewById(R.id.txt_detail);
            dot = v.findViewById(R.id.dot_kind);
        }
    }
}
