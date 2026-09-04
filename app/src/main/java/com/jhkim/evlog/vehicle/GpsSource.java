package com.jhkim.evlog.vehicle;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.core.content.ContextCompat;

/** 휴대폰(또는 차량 헤드유닛)의 GPS로 위치·속도를 읽습니다. */
public class GpsSource implements LocationListener {

    private static final String TAG = "GpsSource";

    private LocationManager lm;
    private HandlerThread thread;
    private volatile Location last;
    private volatile long lastAt;
    /** 위치 제공자가 속도를 안 줄 때, 이전 좌표로 직접 계산한 속도(m/s) */
    private volatile float derivedSpeedMs = -1;
    private Location prevForSpeed;
    private boolean started;

    public static boolean hasPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean start(Context ctx) {
        if (started) return true;
        if (!hasPermission(ctx)) return false;
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        thread = new HandlerThread("gps-source");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, h.getLooper());
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this, h.getLooper());
            }
            started = true;
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "위치 권한 없음", e);
            stop();
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "GPS 시작 실패", t);
            stop();
            return false;
        }
    }

    public void stop() {
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Throwable ignored) {
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        lm = null;
        started = false;
        prevForSpeed = null;
        derivedSpeedMs = -1;
    }

    public boolean isStarted() {
        return started;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        Location prev = last;
        // 정확도가 크게 나쁜 값은 버립니다.
        if (location.hasAccuracy() && location.getAccuracy() > 60f && prev != null) return;

        // 속도를 주지 않는 기기가 있어, 이전 좌표와의 차이로도 계산해 둡니다.
        if (!location.hasSpeed() && prevForSpeed != null) {
            long dtMs = location.getTime() - prevForSpeed.getTime();
            if (dtMs > 500 && dtMs < 30000) {
                float d = prevForSpeed.distanceTo(location);
                float v = d / (dtMs / 1000f);
                derivedSpeedMs = (v >= 0 && v < 90f) ? v : -1;   // 324km/h 넘는 값은 버림
            }
        } else if (location.hasSpeed()) {
            derivedSpeedMs = -1;
        }
        prevForSpeed = location;

        last = location;
        lastAt = System.currentTimeMillis();
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    /** 위치와 속도를 snapshot 에 채웁니다. 차량 속도가 이미 있으면 속도는 덮어쓰지 않습니다. */
    public void fill(VehicleSnapshot s) {
        Location l = last;
        if (l == null) return;
        // 20초 넘게 갱신이 없으면 신선하지 않은 값으로 봅니다.
        if (System.currentTimeMillis() - lastAt > 20000L) return;

        s.hasLocation = true;
        s.lat = l.getLatitude();
        s.lon = l.getLongitude();
        s.accuracyM = l.hasAccuracy() ? l.getAccuracy() : 9999f;

        if (!s.hasSpeed) {
            if (l.hasSpeed()) {
                s.hasSpeed = true;
                s.speedKmh = Math.abs(l.getSpeed()) * 3.6f;
            } else if (derivedSpeedMs >= 0) {
                s.hasSpeed = true;
                s.speedKmh = derivedSpeedMs * 3.6f;
            }
        }
    }
}
