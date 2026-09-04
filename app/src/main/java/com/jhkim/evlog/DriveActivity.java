package com.jhkim.evlog;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.jhkim.evlog.logger.LiveState;
import com.jhkim.evlog.logger.LoggerService;
import com.jhkim.evlog.util.Fmt;

import java.util.Locale;

/**
 * 주행 중 모니터 화면.
 * <p>
 * 안드로이드 오토모티브는 주행 중에 "운전 방해 최적화(distractionOptimized)"로 선언되지 않은
 * 화면을 가려 버립니다. 이 화면은 그 규칙에 맞춰 만든 전용 화면입니다.
 * 목록도 스크롤도 없고, 숫자 네 개만 크게 보여주며, 조작할 것은 닫기 하나뿐입니다.
 * 화면이 꺼지지 않도록 KEEP_SCREEN_ON 도 겁니다.
 */
public class DriveActivity extends AppCompatActivity {

    private TextView vSoc, vRange, vTrip, vEff, vFooter;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable liveListener = this::update;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            ticker.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drive);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        vSoc = findViewById(R.id.v_soc);
        vRange = findViewById(R.id.v_range);
        vTrip = findViewById(R.id.v_trip);
        vEff = findViewById(R.id.v_eff);
        vFooter = findViewById(R.id.v_footer);

        View close = findViewById(R.id.btn_close);
        close.setOnClickListener(v -> finish());

        // 모니터를 열었는데 기록이 꺼져 있으면 켜 줍니다.
        if (!LiveState.serviceRunning) {
            LoggerService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LiveState.addListener(liveListener);
        ticker.post(tick);
        update();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LiveState.removeListener(liveListener);
        ticker.removeCallbacks(tick);
    }

    private void update() {
        if (isFinishing()) return;

        vSoc.setText(LiveState.socPct >= 0
                ? String.format(Locale.KOREA, "%.0f%%", LiveState.socPct) : "—");

        vRange.setText(LiveState.rangeKm > 0
                ? String.format(Locale.KOREA, "%.0f", LiveState.rangeKm) : "—");

        vTrip.setText(String.format(Locale.KOREA, "%.1f", LiveState.tripKm));

        vEff.setText(LiveState.tripEfficiency > 0
                ? String.format(Locale.KOREA, "%.1f", LiveState.tripEfficiency) : "—");

        StringBuilder sb = new StringBuilder();
        if (LiveState.speedKmh >= 0) {
            sb.append(String.format(Locale.KOREA, "%.0f km/h", LiveState.speedKmh));
        }
        if (LiveState.tripActive) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append(Fmt.duration(LiveState.tripElapsedS));
        } else if (LiveState.chargeActive) {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append(String.format(Locale.KOREA, "충전 중 %.1f kWh", LiveState.chargeKwh));
        } else {
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append(LiveState.serviceRunning ? "주행 대기" : "기록 꺼짐");
        }
        sb.append("   ·   ").append(LiveState.sourceLabel);
        vFooter.setText(sb.toString());
    }
}
