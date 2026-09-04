package com.jhkim.evlog;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.jhkim.evlog.ui.ChargesFragment;
import com.jhkim.evlog.ui.DashboardFragment;
import com.jhkim.evlog.ui.TripsFragment;
import com.jhkim.evlog.util.CsvExport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_BASIC = 100;
    private static final int REQ_BACKGROUND = 101;
    private static final int REQ_CAR = 102;

    /**
     * 차량 데이터 권한. AAOS에서는 위험 권한이라 실행 중에 사용자 동의를 받아야 합니다.
     * 휴대폰에는 이 권한 자체가 없으므로 차량에서만 요청합니다.
     */
    private static final String[] CAR_PERMISSIONS = {
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_ENERGY_PORTS",
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_INFO",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ViewPager2 pager = findViewById(R.id.pager);
        TabLayout tabs = findViewById(R.id.tabs);

        pager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case 1:
                        return new TripsFragment();
                    case 2:
                        return new ChargesFragment();
                    default:
                        return new DashboardFragment();
                }
            }

            @Override
            public int getItemCount() {
                return 3;
            }
        });
        pager.setOffscreenPageLimit(2);

        final String[] titles = {
                getString(R.string.tab_dashboard),
                getString(R.string.tab_trips),
                getString(R.string.tab_charges)
        };
        new TabLayoutMediator(tabs, pager, (tab, position) -> tab.setText(titles[position])).attach();

        if (!requestCarPermissions()) {
            requestBasicPermissions();
        }
    }

    // ----------------- 권한 -----------------

    /** 차량이면 차량 데이터 권한을 먼저 요청합니다. 요청했으면 true. */
    private boolean requestCarPermissions() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false;
        List<String> need = new ArrayList<>();
        for (String p : CAR_PERMISSIONS) {
            try {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    need.add(p);
                }
            } catch (Throwable ignored) {
                // 이 차량에 없는 권한은 건너뜁니다.
            }
        }
        if (need.isEmpty()) return false;
        try {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_CAR);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestBasicPermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_BASIC);
        } else {
            maybeAskBackground();
        }
    }

    private void maybeAskBackground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        new AlertDialog.Builder(this)
                .setTitle("백그라운드 위치 권한")
                .setMessage(getString(R.string.need_background_location))
                .setPositiveButton("설정하기", (d, w) -> ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND))
                .setNegativeButton("나중에", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAR) {
            // 차량 권한 결과와 무관하게 위치·알림 권한을 이어서 요청합니다.
            requestBasicPermissions();
            return;
        }
        if (requestCode == REQ_BASIC) {
            boolean granted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                }
            }
            if (granted) {
                maybeAskBackground();
            } else {
                Toast.makeText(this, R.string.need_location, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ----------------- 메뉴 -----------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_export) {
            exportCsv();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void exportCsv() {
        try {
            File[] files = CsvExport.write(this);
            startActivity(CsvExport.shareIntent(this, files));
        } catch (Exception e) {
            Toast.makeText(this, "내보내기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
