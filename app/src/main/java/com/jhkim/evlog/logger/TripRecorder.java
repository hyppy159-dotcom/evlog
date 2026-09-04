package com.jhkim.evlog.logger;

import android.content.Context;
import android.util.Log;

import com.jhkim.evlog.Prefs;
import com.jhkim.evlog.db.Db;
import com.jhkim.evlog.db.Trip;
import com.jhkim.evlog.vehicle.VehicleSnapshot;

/** 주행 시작·종료를 스스로 판단해 기록합니다. */
public class TripRecorder {

    private static final String TAG = "TripRecorder";

    /** 이 속도를 넘으면 주행 시작 */
    private static final float START_KMH = 4f;
    /** 이 속도 미만이면 정차로 봄 */
    private static final float STOP_KMH = 2f;
    /** 차량 데이터가 있을 때 정차 후 종료까지 */
    private static final long END_AFTER_STOP_CAR_MS = 3 * 60_000L;
    /** GPS만 있을 때 정차 후 종료까지 */
    private static final long END_AFTER_STOP_GPS_MS = 5 * 60_000L;
    /** 한 번에 인정하는 최대 시간 간격(초) — 절전 등으로 오래 끊긴 구간은 버립니다. */
    private static final double MAX_DT_S = 12.0;

    private final Context ctx;
    private final Db db;

    private boolean active;
    private long startTs;
    private long lastTs;
    private double distanceM;
    private double movingMs;
    private double maxKmh;
    private double prevKmh;

    private double startSoc = -1;
    private double startWh = -1;
    private double lastSoc = -1;
    private double lastWh = -1;

    private double startLat, startLon, endLat, endLon;
    private double lastLat = Double.NaN, lastLon = Double.NaN;

    private long stoppedSince;
    private boolean sawCarData;

    public TripRecorder(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.db = Db.get(ctx);
    }

    public boolean isActive() {
        return active;
    }

    public double currentKm() {
        return distanceM / 1000.0;
    }

    public long elapsedS() {
        return active ? (System.currentTimeMillis() - startTs) / 1000L : 0;
    }

    public void update(VehicleSnapshot s) {
        long now = s.ts;
        float kmh = s.hasSpeed ? s.speedKmh : 0f;

        if (!active) {
            if (s.hasSpeed && kmh >= START_KMH) {
                begin(s);
            }
            return;
        }

        double dt = (now - lastTs) / 1000.0;
        if (dt < 0) dt = 0;
        if (dt > MAX_DT_S) dt = 0;   // 큰 공백은 거리 적산에서 제외

        // 거리 적산: 차량 속도가 있으면 속도 적분, 없으면 GPS 좌표 차이
        if (s.fromCar && s.hasSpeed && dt > 0) {
            double avgMs = ((prevKmh + kmh) / 2.0) / 3.6;
            distanceM += avgMs * dt;
        } else if (s.hasLocation && !Double.isNaN(lastLat)) {
            double d = distanceBetween(lastLat, lastLon, s.lat, s.lon);
            // 튀는 값·정지 중 표류 제거
            if (d > 2 && d < 400 && s.accuracyM < 40f) {
                distanceM += d;
            }
        }

        if (s.hasLocation) {
            lastLat = s.lat;
            lastLon = s.lon;
            endLat = s.lat;
            endLon = s.lon;
        }

        if (kmh > maxKmh) maxKmh = kmh;
        if (kmh >= STOP_KMH) movingMs += dt * 1000.0;

        if (s.hasBatteryWh) lastWh = s.batteryWh;
        if (s.hasSoc) lastSoc = s.socPct;
        if (s.fromCar) sawCarData = true;

        prevKmh = kmh;
        lastTs = now;

        // --- 종료 판정 ---
        if (s.hasIgnition && !s.ignitionOn) {
            end(now);
            return;
        }
        if (s.hasPortConnected && s.portConnected) {
            // 충전기를 꽂았다면 주행은 이미 끝난 것으로 봅니다.
            end(now);
            return;
        }

        boolean stopped = !s.hasSpeed || kmh < STOP_KMH;
        if (stopped) {
            if (stoppedSince == 0) stoppedSince = now;
            long limit = sawCarData ? END_AFTER_STOP_CAR_MS : END_AFTER_STOP_GPS_MS;
            if (now - stoppedSince > limit) {
                end(stoppedSince);
            }
        } else {
            stoppedSince = 0;
        }
    }

    private void begin(VehicleSnapshot s) {
        active = true;
        startTs = s.ts;
        lastTs = s.ts;
        distanceM = 0;
        movingMs = 0;
        maxKmh = s.hasSpeed ? s.speedKmh : 0;
        prevKmh = maxKmh;
        stoppedSince = 0;
        sawCarData = s.fromCar;
        startSoc = s.hasSoc ? s.socPct : -1;
        startWh = s.hasBatteryWh ? s.batteryWh : -1;
        lastSoc = startSoc;
        lastWh = startWh;
        if (s.hasLocation) {
            startLat = s.lat;
            startLon = s.lon;
            endLat = s.lat;
            endLon = s.lon;
            lastLat = s.lat;
            lastLon = s.lon;
        } else {
            lastLat = Double.NaN;
        }
        LiveState.tripActive = true;
        LiveState.notifyChanged();
    }

    /** 서비스가 멈출 때 진행 중인 주행을 마무리합니다. */
    public void finishIfActive() {
        if (active) end(System.currentTimeMillis());
    }

    private void end(long endTs) {
        active = false;
        LiveState.tripActive = false;
        LiveState.tripKm = 0;

        long totalS = Math.max(0, (endTs - startTs) / 1000L);
        int minM = Prefs.minTripMeters(ctx);
        if (distanceM < minM || totalS < 60) {
            Log.i(TAG, "너무 짧은 주행이라 저장하지 않음: " + (int) distanceM + "m");
            reset();
            LiveState.notifyChanged();
            return;
        }

        Trip t = new Trip();
        t.startTs = startTs;
        t.endTs = endTs;
        t.distanceM = distanceM;
        t.movingS = (long) (movingMs / 1000.0);
        t.totalS = totalS;
        double movingS = Math.max(1.0, movingMs / 1000.0);
        t.avgKmh = (distanceM / movingS) * 3.6;
        t.maxKmh = maxKmh;
        t.startSoc = startSoc;
        t.endSoc = lastSoc;

        double used = -1;
        if (startWh > 0 && lastWh > 0 && startWh > lastWh) {
            used = startWh - lastWh;
        } else if (startSoc >= 0 && lastSoc >= 0 && startSoc > lastSoc) {
            used = (startSoc - lastSoc) / 100.0 * Prefs.capacityKwh(ctx) * 1000.0;
        }
        t.usedWh = used;

        t.startLat = startLat;
        t.startLon = startLon;
        t.endLat = endLat;
        t.endLon = endLon;
        t.source = sawCarData ? "car" : "gps";
        t.note = "";

        db.insertTrip(t);
        Log.i(TAG, "주행 저장: " + String.format("%.1fkm", t.km()));
        reset();
        LiveState.bumpData();
    }

    private void reset() {
        distanceM = 0;
        movingMs = 0;
        maxKmh = 0;
        prevKmh = 0;
        startSoc = lastSoc = -1;
        startWh = lastWh = -1;
        stoppedSince = 0;
        lastLat = Double.NaN;
    }

    /** 하버사인 거리(m). */
    public static double distanceBetween(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
