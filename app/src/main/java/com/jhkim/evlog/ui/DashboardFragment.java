package com.jhkim.evlog.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.jhkim.evlog.R;
import com.jhkim.evlog.db.Db;
import com.jhkim.evlog.db.Trip;
import com.jhkim.evlog.logger.LiveState;
import com.jhkim.evlog.logger.LoggerService;
import com.jhkim.evlog.util.Fmt;

import java.util.List;
import java.util.Locale;

public class DashboardFragment extends Fragment {

    private BatteryGaugeView gauge;
    private TrendChartView chart;
    private TextView txtRange, txtSource, txtTemp, txtStatus, txtStatusSub, txtHint;
    private StatTileView statMonthKm, statMonthCost, statAvgEff, statTotalKm;
    private MaterialButton btnToggle;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private int lastRevision = -1;

    private final Runnable liveListener = this::updateLive;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateLive();
            ticker.postDelayed(this, 1000L);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle saved) {
        View v = inflater.inflate(R.layout.fragment_dashboard, container, false);

        gauge = v.findViewById(R.id.gauge);
        chart = v.findViewById(R.id.chart);
        txtRange = v.findViewById(R.id.txt_range);
        txtSource = v.findViewById(R.id.txt_source);
        txtTemp = v.findViewById(R.id.txt_temp);
        txtStatus = v.findViewById(R.id.txt_status);
        txtStatusSub = v.findViewById(R.id.txt_status_sub);
        txtHint = v.findViewById(R.id.txt_hint);
        statMonthKm = v.findViewById(R.id.stat_month_km);
        statMonthCost = v.findViewById(R.id.stat_month_cost);
        statAvgEff = v.findViewById(R.id.stat_avg_eff);
        statTotalKm = v.findViewById(R.id.stat_total_km);
        btnToggle = v.findViewById(R.id.btn_toggle);

        chart.setEmptyText(getString(R.string.empty_chart));

        btnToggle.setOnClickListener(b -> {
            if (LiveState.serviceRunning) {
                LoggerService.stop(requireContext());
            } else {
                LoggerService.start(requireContext());
            }
            btnToggle.postDelayed(this::updateLive, 400);
        });

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        LiveState.addListener(liveListener);
        ticker.post(tick);
        lastRevision = -1;
        loadData();
        updateLive();
    }

    @Override
    public void onPause() {
        super.onPause();
        LiveState.removeListener(liveListener);
        ticker.removeCallbacks(tick);
    }

    /** 1초마다 바뀌는 값들. */
    private void updateLive() {
        if (!isAdded() || gauge == null) return;

        gauge.setSoc(LiveState.socPct);

        if (LiveState.rangeKm > 0) {
            txtRange.setText(String.format(Locale.KOREA, "%.0f km", LiveState.rangeKm));
        } else {
            txtRange.setText("—");
        }

        String source = "데이터 출처: " + LiveState.sourceLabel;
        // 차에 붙었는데도 잔량이 안 나오면 이유를 바로 보여줍니다.
        if (LiveState.carConnected && LiveState.socPct < 0
                && LiveState.carBatteryStatus != null && !LiveState.carBatteryStatus.isEmpty()
                && !"정상".equals(LiveState.carBatteryStatus)) {
            source = source + "\n배터리: " + LiveState.carBatteryStatus
                    + "\n(메뉴 → 차량 데이터 진단에서 자세히)";
        }
        txtSource.setText(source);

        if (LiveState.outsideTempC > -100) {
            txtTemp.setText(String.format(Locale.KOREA, "외기 %.1f℃", LiveState.outsideTempC));
            txtTemp.setVisibility(View.VISIBLE);
        } else {
            txtTemp.setVisibility(View.GONE);
        }

        if (LiveState.chargeActive) {
            txtStatus.setText(String.format(Locale.KOREA, "충전 중 · %.1f kWh", LiveState.chargeKwh));
            txtStatusSub.setText(LiveState.chargeKw > 0
                    ? String.format(Locale.KOREA, "현재 출력 %.1f kW", LiveState.chargeKw)
                    : "충전이 끝나면 자동으로 저장됩니다.");
        } else if (LiveState.tripActive) {
            txtStatus.setText(String.format(Locale.KOREA, "주행 중 · %.1f km", LiveState.tripKm));
            String speed = LiveState.speedKmh >= 0
                    ? String.format(Locale.KOREA, "%.0f km/h · ", LiveState.speedKmh) : "";
            txtStatusSub.setText(speed + Fmt.duration(LiveState.tripElapsedS));
        } else if (LiveState.serviceRunning) {
            txtStatus.setText("기록 대기 중");
            txtStatusSub.setText("주행을 시작하면 자동으로 기록합니다.");
        } else {
            txtStatus.setText("기록 꺼짐");
            txtStatusSub.setText("기록을 시작하면 주행과 충전이 자동으로 쌓입니다.");
        }

        btnToggle.setText(LiveState.serviceRunning
                ? R.string.stop_logging : R.string.start_logging);

        if (LiveState.dataRevision != lastRevision) {
            loadData();
        }
    }

    /** 저장된 기록이 바뀌었을 때만 다시 계산합니다. */
    private void loadData() {
        if (!isAdded()) return;
        lastRevision = LiveState.dataRevision;
        Db db = Db.get(requireContext());

        statMonthKm.set("이번 달 주행", Fmt.km(db.monthDistanceM() / 1000.0));
        statMonthCost.set("이번 달 충전비", Fmt.won(db.monthChargeCost()));

        double avg = db.averageEfficiency();
        statAvgEff.set("평균 전비", avg > 0 ? Fmt.eff(avg) : "—");
        statTotalKm.set("총 주행거리", Fmt.km(db.totalDistanceM() / 1000.0));

        List<Trip> trips = db.recentTripsWithEfficiency(12);
        float[] values = new float[trips.size()];
        String[] labels = new String[trips.size()];
        for (int i = 0; i < trips.size(); i++) {
            values[i] = (float) trips.get(i).efficiencyKmPerKwh();
            labels[i] = Fmt.dateShort(trips.get(i).startTs);
        }
        chart.setData(values, labels);

        int tripCount = db.tripCount();
        int chargeCount = db.chargeCount();
        if (tripCount == 0 && chargeCount == 0) {
            txtHint.setText("아직 기록이 없습니다. ‘기록 시작’을 누르고 주행하면 자동으로 쌓입니다.\n"
                    + "차량에서는 배터리 잔량까지 함께 읽어 전비를 계산합니다.");
        } else {
            txtHint.setText(String.format(Locale.KOREA,
                    "주행 %d건 · 충전 %d건 기록됨", tripCount, chargeCount));
        }
    }
}
