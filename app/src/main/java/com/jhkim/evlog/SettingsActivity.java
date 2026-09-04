package com.jhkim.evlog;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.jhkim.evlog.db.Db;
import com.jhkim.evlog.logger.LiveState;
import com.jhkim.evlog.util.CsvExport;

import java.io.File;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private EditText inCapacity, inRateAc, inRateDc, inDcKw, inMinTrip;
    private SwitchMaterial swAutoStart;

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

        inCapacity.setText(fmt(Prefs.capacityKwh(this)));
        inRateAc.setText(fmt(Prefs.rateAc(this)));
        inRateDc.setText(fmt(Prefs.rateDc(this)));
        inDcKw.setText(fmt(Prefs.dcThresholdKw(this)));
        inMinTrip.setText(String.valueOf(Prefs.minTripMeters(this)));
        swAutoStart.setChecked(Prefs.autoStart(this));

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
        LiveState.bumpData();
        Toast.makeText(this, "설정을 저장했습니다.", Toast.LENGTH_SHORT).show();
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
