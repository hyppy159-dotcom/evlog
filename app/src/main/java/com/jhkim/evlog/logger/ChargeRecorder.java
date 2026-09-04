package com.jhkim.evlog.logger;

import android.content.Context;
import android.util.Log;

import com.jhkim.evlog.Prefs;
import com.jhkim.evlog.db.Charge;
import com.jhkim.evlog.db.Db;
import com.jhkim.evlog.vehicle.VehicleSnapshot;

/**
 * 충전 세션을 스스로 감지해 기록합니다.
 * 충전구 상태를 읽을 수 있으면 그것을 쓰고, 없으면 "정차 중 배터리 증가"로 판단합니다.
 */
public class ChargeRecorder {

    private static final String TAG = "ChargeRecorder";

    /** 충전구 정보가 없을 때, 이만큼 올라야 충전으로 인정(%) */
    private static final double SOC_RISE_START = 1.0;
    /** 배터리가 더 오르지 않고 이 시간이 지나면 종료 */
    private static final long IDLE_END_MS = 10 * 60_000L;
    /** 이보다 적게 충전됐으면 기록하지 않음(Wh) */
    private static final double MIN_ADDED_WH = 400;

    private final Context ctx;
    private final Db db;

    private boolean active;
    private long startTs;
    private double startSoc = -1, startWh = -1;
    private double lastSoc = -1, lastWh = -1;
    private double peakSoc = -1, peakWh = -1;
    private double maxKw = -1;
    private double lat, lon;
    private long lastRiseTs;

    /** 충전구 정보가 없을 때 기준으로 삼는 정차 중 배터리 값 */
    private double parkedRefSoc = -1;
    private long parkedRefTs;

    public ChargeRecorder(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.db = Db.get(ctx);
    }

    public boolean isActive() {
        return active;
    }

    public double addedKwh() {
        double wh = currentAddedWh();
        return wh > 0 ? wh / 1000.0 : 0;
    }

    private double currentAddedWh() {
        if (peakWh > 0 && startWh > 0) return peakWh - startWh;
        if (peakSoc >= 0 && startSoc >= 0) {
            return (peakSoc - startSoc) / 100.0 * Prefs.capacityKwh(ctx) * 1000.0;
        }
        return 0;
    }

    public void update(VehicleSnapshot s) {
        long now = s.ts;
        boolean moving = s.hasSpeed && s.speedKmh > 2f;

        if (s.hasChargeKw && s.chargeKw > maxKw) maxKw = s.chargeKw;

        if (!active) {
            if (shouldStart(s, moving)) begin(s);
            else trackParkedReference(s, moving);
            return;
        }

        // --- 진행 중 ---
        if (s.hasSoc) {
            if (peakSoc < 0 || s.socPct > peakSoc + 0.05) {
                peakSoc = Math.max(peakSoc, s.socPct);
                lastRiseTs = now;
            }
            lastSoc = s.socPct;
        }
        if (s.hasBatteryWh) {
            if (peakWh < 0 || s.batteryWh > peakWh + 20) {
                peakWh = Math.max(peakWh, s.batteryWh);
                lastRiseTs = now;
            }
            lastWh = s.batteryWh;
        }
        if (s.hasLocation) {
            lat = s.lat;
            lon = s.lon;
        }

        LiveState.chargeKwh = addedKwh();
        LiveState.chargeKw = s.hasChargeKw ? s.chargeKw : -1;

        boolean unplugged = s.hasPortConnected && !s.portConnected;
        boolean droveOff = moving;
        boolean idleTooLong = lastRiseTs > 0 && (now - lastRiseTs) > IDLE_END_MS;

        if (unplugged || droveOff || idleTooLong) {
            end(now);
        }
    }

    private boolean shouldStart(VehicleSnapshot s, boolean moving) {
        if (moving) return false;
        if (s.hasPortConnected) {
            return s.portConnected;
        }
        if (s.hasChargeKw && s.chargeKw > 0.5f) return true;
        // 충전구 정보가 없는 차: 정차 중 배터리가 눈에 띄게 오르면 충전으로 봅니다.
        if (s.hasSoc && parkedRefSoc >= 0 && s.socPct - parkedRefSoc >= SOC_RISE_START) {
            return true;
        }
        return false;
    }

    private void trackParkedReference(VehicleSnapshot s, boolean moving) {
        if (moving || !s.hasSoc) {
            parkedRefSoc = -1;
            return;
        }
        // 정차 직후의 잔량을 기준값으로 잡고, 잔량이 내려가면 기준을 갱신합니다.
        if (parkedRefSoc < 0 || s.socPct < parkedRefSoc) {
            parkedRefSoc = s.socPct;
            parkedRefTs = s.ts;
        }
    }

    private void begin(VehicleSnapshot s) {
        active = true;
        startTs = s.ts;
        // 충전 시작 직전 값을 알고 있으면 그것을 시작점으로 씁니다.
        startSoc = parkedRefSoc >= 0 ? parkedRefSoc : (s.hasSoc ? s.socPct : -1);
        startWh = s.hasBatteryWh ? s.batteryWh : -1;
        peakSoc = s.hasSoc ? s.socPct : -1;
        peakWh = startWh;
        lastSoc = peakSoc;
        lastWh = peakWh;
        maxKw = s.hasChargeKw ? s.chargeKw : -1;
        lastRiseTs = s.ts;
        if (s.hasLocation) {
            lat = s.lat;
            lon = s.lon;
        }
        LiveState.chargeActive = true;
        LiveState.notifyChanged();
    }

    public void finishIfActive() {
        if (active) end(System.currentTimeMillis());
    }

    private void end(long endTs) {
        active = false;
        LiveState.chargeActive = false;
        LiveState.chargeKwh = 0;
        LiveState.chargeKw = -1;

        double addedWh = currentAddedWh();
        if (addedWh < MIN_ADDED_WH) {
            Log.i(TAG, "충전량이 적어 저장하지 않음: " + (int) addedWh + "Wh");
            resetState();
            LiveState.notifyChanged();
            return;
        }

        double hours = Math.max(0.02, (endTs - startTs) / 3600000.0);
        double avgKw = (addedWh / 1000.0) / hours;
        double judgeKw = maxKw > 0 ? maxKw : avgKw;
        boolean dc = judgeKw >= Prefs.dcThresholdKw(ctx);

        Charge c = new Charge();
        c.startTs = startTs;
        c.endTs = endTs;
        c.startSoc = startSoc;
        c.endSoc = peakSoc >= 0 ? peakSoc : lastSoc;
        c.addedWh = addedWh;
        c.kind = dc ? Charge.DC : Charge.AC;
        c.maxKw = maxKw > 0 ? maxKw : -1;
        c.cost = (addedWh / 1000.0) * (dc ? Prefs.rateDc(ctx) : Prefs.rateAc(ctx));
        c.lat = lat;
        c.lon = lon;
        c.manual = false;
        c.note = "";

        db.insertCharge(c);
        Log.i(TAG, "충전 저장: " + String.format("%.1fkWh", c.kwh()));
        resetState();
        LiveState.bumpData();
    }

    private void resetState() {
        startSoc = startWh = -1;
        lastSoc = lastWh = -1;
        peakSoc = peakWh = -1;
        maxKw = -1;
        lastRiseTs = 0;
        parkedRefSoc = -1;
    }
}
