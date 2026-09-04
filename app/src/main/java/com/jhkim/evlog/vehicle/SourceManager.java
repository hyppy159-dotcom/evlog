package com.jhkim.evlog.vehicle;

import android.content.Context;

import com.jhkim.evlog.Prefs;

/**
 * 차량 API와 GPS를 함께 관리합니다.
 * 차 안에서는 차량 값이 우선이고, 휴대폰에서는 GPS만으로 동작합니다.
 */
public class SourceManager {

    private final CarApiSource car = new CarApiSource();
    private final GpsSource gps = new GpsSource();
    private Context ctx;

    public void start(Context context) {
        ctx = context.getApplicationContext();
        if (CarApiSource.isAvailable(ctx)) {
            car.start(ctx);
        }
        gps.start(ctx);
    }

    public void stop() {
        car.stop();
        gps.stop();
    }

    /** GPS 권한을 나중에 받은 경우를 위해 재시도합니다. */
    public void retryGps() {
        if (ctx != null && !gps.isStarted()) gps.start(ctx);
    }

    public boolean carConnected() {
        return car.isStarted();
    }

    public boolean gpsConnected() {
        return gps.isStarted();
    }

    /** 사용자에게 보여줄 데이터 출처 이름. */
    public String sourceLabel() {
        if (car.isStarted() && gps.isStarted()) return "차량 데이터 + GPS";
        if (car.isStarted()) return "차량 데이터";
        if (gps.isStarted()) return "GPS";
        return "대기 중";
    }

    public VehicleSnapshot read() {
        VehicleSnapshot s = new VehicleSnapshot();
        s.ts = System.currentTimeMillis();
        if (car.isStarted()) {
            car.fill(s, Prefs.capacityKwh(ctx));
        }
        gps.fill(s);
        return s;
    }
}
