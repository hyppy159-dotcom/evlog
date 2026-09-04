package com.jhkim.evlog.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.jhkim.evlog.R;
import com.jhkim.evlog.db.Trip;
import com.jhkim.evlog.util.Fmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TripAdapter extends RecyclerView.Adapter<TripAdapter.VH> {

    public interface OnLongPress {
        void onLongPress(Trip trip);
    }

    private final List<Trip> items = new ArrayList<>();
    private final OnLongPress longPress;

    public TripAdapter(OnLongPress longPress) {
        this.longPress = longPress;
    }

    public void setItems(List<Trip> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_trip, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final Trip t = items.get(position);

        h.date.setText(Fmt.dateTime(t.startTs));
        h.source.setText("car".equals(t.source) ? "차량 데이터" : "GPS");
        h.km.setText(String.format(Locale.KOREA, "%.1f", t.km()) + " km");

        double eff = t.efficiencyKmPerKwh();
        h.eff.setText(eff > 0 ? String.format(Locale.KOREA, "%.1f", eff) : "—");

        StringBuilder sb = new StringBuilder();
        sb.append(Fmt.duration(t.totalS));
        sb.append("  ·  평균 ").append(String.format(Locale.KOREA, "%.0f km/h", t.avgKmh));
        sb.append("  ·  최고 ").append(String.format(Locale.KOREA, "%.0f km/h", t.maxKmh));
        if (t.startSoc >= 0 && t.endSoc >= 0) {
            sb.append("\n배터리 ").append(String.format(Locale.KOREA, "%.0f%% → %.0f%%", t.startSoc, t.endSoc));
        }
        if (t.usedWh > 0) {
            sb.append("  ·  사용 ").append(String.format(Locale.KOREA, "%.1f kWh", t.usedWh / 1000.0));
            sb.append(" (").append(String.format(Locale.KOREA, "%.0f Wh/km", t.whPerKm())).append(")");
        }
        h.detail.setText(sb.toString());

        h.itemView.setOnLongClickListener(v -> {
            if (longPress != null) longPress.onLongPress(t);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView date, source, km, eff, detail;

        VH(@NonNull View v) {
            super(v);
            date = v.findViewById(R.id.txt_date);
            source = v.findViewById(R.id.txt_source);
            km = v.findViewById(R.id.txt_km);
            eff = v.findViewById(R.id.txt_eff);
            detail = v.findViewById(R.id.txt_detail);
        }
    }
}
