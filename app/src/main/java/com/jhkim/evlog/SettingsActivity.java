package com.jhkim.evlog;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.jhkim.evlog.db.Db;
import com.jhkim.evlog.logger.LiveState;
import com.jhkim.evlog.sync.SyncClient;
import com.jhkim.evlog.util.CsvExport;
import com.jhkim.evlog.util.Fmt;

import java.io.File;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private EditText inCapacity, inRateAc, inRateDc, inDcKw, inMinTrip;
    private EditText inServerUrl, inServerToken;
    private SwitchMaterial swAutoStart, swSync, swRoute;
    private TextView txtSyncStatus;
    private MaterialButton btnSync;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        inCapacity = findViewById(R.id.in_capacity);
        inRateAc = findViewById(R.id.in_rate_ac);
        inRateDc = findViewById(R.id.in_rate_dc);
        inDcKw = findViewById(R.id.in_dc_kw);
        inMinTrip = findViewById(R.id.in_min_trip);
        swAutoStart = findViewById(R.id.sw_autostart);
        inServerUrl = findViewById(R.id.in_server_url);
        inServerToken = findViewById(R.id.in_server_token);
        swSync = findViewById(R.id.sw_sync);
        swRoute = findViewById(R.id.sw_route);
        txtSyncStatus = findViewById(R.id.txt_sync_status);
        btnSync = findViewById(R.id.btn_sync);

        inCapacity.setText(fmt(Prefs.capacityKwh(this)));
        inRateAc.setText(fmt(Prefs.rateAc(this)));
        inRateDc.setText(fmt(Prefs.rateDc(this)));
        inDcKw.setText(fmt(Prefs.dcThresholdKw(this)));
        inMinTrip.setText(String.valueOf(Prefs.minTripMeters(this)));
        swAutoStart.setChecked(Prefs.autoStart(this));
        inServerUrl.setText(Prefs.serverUrl(this));
        inServerToken.setText(Prefs.serverToken(this));
        swSync.setChecked(Prefs.syncAuto(this));
        swRoute.setChecked(Prefs.routeEnabled(this));
        btnSync.setOnClickListener(v -> syncNow());
        updateSyncStatus();

        MaterialButton save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> save());

        MaterialButton export = findViewById(R.id.btn_export);
        export.setOnClickListener(v -> exportCsv());

        MaterialButton clear = findViewById(R.id.btn_clear);
        clear.setOnClickListener(v -> confirmClear());
    }

    private static String fmt(float v) {
        if (v == Math.rint(v)) return String.valueOf((int) v);
        return String.format(Locale.KOREA, "%.1f", v);
    }

    private float read(EditText e, float fallback) {
        try {
            return Float.parseFloat(e.getText().toString().trim().replace(",", ""));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private void save() {
        float cap = read(inCapacity, Prefs.DEF_CAPACITY_KWH);
        if (cap < 5 || cap > 400) cap = Prefs.DEF_CAPACITY_KWH;
        Prefs.setCapacityKwh(this, cap);
        Prefs.setRateAc(this, Math.max(0, read(inRateAc, Prefs.DEF_RATE_AC)));
        Prefs.setRateDc(this, Math.max(0, read(inRateDc, Prefs.DEF_RATE_DC)));
        Prefs.setDcThresholdKw(this, Math.max(1, read(inDcKw, Prefs.DEF_DC_KW)));
        Prefs.setMinTripMeters(this, (int) Math.max(0, read(inMinTrip, 300)));
        Prefs.setAutoStart(this, swAutoStart.isChecked());
        Prefs.setSyncAuto(this, swSync.isChecked());
        Prefs.setRouteEnabled(this, swRoute.isChecked());
        Prefs.setServerUrl(this, inServerUrl.getText().toString());
        Prefs.setServerToken(this, inServerToken.getText().toString());
        LiveState.bumpData();
        updateSyncStatus();
        Toast.makeText(this, "설정을 저장했습니다.", Toast.LENGTH_SHORT).show();
    }

    // ---------------- 서버 업로드 ----------------

    private void updateSyncStatus() {
        int pending = Db.get(this).pendingCount();
        StringBuilder sb = new StringBuilder();
        if (!Prefs.serverConfigured(this)) {
            sb.append("서버 주소와 토큰을 넣고 저장하면 업로드할 수 있습니다.");
        } else {
            sb.append(pending == 0 ? "올릴 기록이 없습니다." : "올릴 기록 " + pending + "건");
            long last = Prefs.lastSyncTs(this);
            if (last > 0) {
                sb.append("\n마지막 시도: ").append(Fmt.dateTime(last));
                String msg = Prefs.lastSyncMessage(this);
                if (!msg.isEmpty()) sb.append("\n").append(msg);
            }
        }
        txtSyncStatus.setText(sb.toString());
    }

    private void syncNow() {
        // 화면에서 방금 입력한 값을 먼저 저장해 두고 시도합니다.
        Prefs.setServerUrl(this, inServerUrl.getText().toString());
        Prefs.setServerToken(this, inServerToken.getText().toString());
        if (!Prefs.serverConfigured(this)) {
            Toast.makeText(this, "서버 주소와 토큰을 먼저 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        btnSync.setEnabled(false);
        btnSync.setText("올리는 중…");
        SyncClient.syncInBackground(this, result -> runOnUiThread(() -> {
            btnSync.setEnabled(true);
            btnSync.setText("지금 서버로 올리기");
            updateSyncStatus();
            Toast.makeText(this,
                    result.ok ? result.message : "실패: " + result.message,
                    Toast.LENGTH_LONG).show();
        }));
    }

    private void exportCsv() {
        try {
            File[] files = CsvExport.write(this);
            startActivity(CsvExport.shareIntent(this, files));
        } catch (Exception e) {
            Toast.makeText(this, "내보내기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("모든 기록을 삭제할까요?")
                .setMessage("주행과 충전 기록이 모두 지워지며 되돌릴 수 없습니다.\n"
                        + "먼저 CSV로 내보내 두는 것을 권합니다.")
                .setPositiveButton("삭제", (d, w) -> {
                    Db.get(this).clearAll();
                    LiveState.bumpData();
                    Toast.makeText(this, "기록을 모두 삭제했습니다.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
