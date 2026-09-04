package com.jhkim.evlog.vehicle;

import android.content.Context;

import com.jhkim.evlog.Prefs;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 차량 API와 GPS를 함께 관리합니다.
 * 차 안에서는 차량 값이 우선이고, 휴대폰에서는 GPS만으로 동작합니다.
 * <p>
 * 차량 서비스는 시동 직후 아직 안 떠 있을 수 있어, 연결될 때까지 주기적으로 다시 시도합니다.
 */
public class SourceManager {

    /** 차량 재연결 시도 간격(초). 서비스가 1초마다 tick 합니다. */
    private static final int CAR_RETRY_SEC = 15;

    private final CarApiSource car = new CarApiSource();
    private final GpsSource gps = new GpsSource();
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private Context ctx;
    private int tick;

    public void start(Context context) {
        ctx = context.getApplicationContext();
        connectCarAsync();
        gps.start(ctx);
    }

    public void stop() {
        car.stop();
        gps.stop();
    }

    /** 서비스가 1초마다 부릅니다. 끊긴 연결을 조용히 되살립니다. */
    public void tick() {
        tick++;
        if (ctx == null) return;

        if (!gps.isStarted() && tick % 30 == 0 && GpsSource.hasPermission(ctx)) {
            gps.start(ctx);
        }
        if (!car.isStarted() && tick % CAR_RETRY_SEC == 0) {
            connectCarAsync();
        }
    }

    /** 차량 서비스 연결은 잠깐 멈출 수 있어 별도 스레드에서 합니다. */
    private void connectCarAsync() {
        if (ctx == null) return;
        if (!CarApiSource.isAvailable(ctx)) return;
        if (!connecting.compareAndSet(false, true)) return;
        final Context c = ctx;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    car.start(c);
                } finally {
                    connecting.set(false);
                }
            }
        }, "car-connect").start();
    }

    public boolean carConnected() {
        return car.isStarted();
    }

    public boolean gpsConnected() {
        return gps.isStarted();
    }

    /** 배터리를 못 읽는 이유(차량일 때만 의미 있음). */
    public String carBatteryStatus() {
        return car.lastBatteryError();
    }

    /** 사용자에게 보여줄 데이터 출처 이름. */
    public String sourceLabel() {
        boolean isCar = ctx != null && CarApiSource.isAvailable(ctx);
        if (car.isStarted() && gps.isStarted()) return "차량 데이터 + GPS";
        if (car.isStarted()) return "차량 데이터";
        if (isCar && gps.isStarted()) return "GPS (차량 데이터 연결 중)";
        if (gps.isStarted()) return "GPS";
        if (isCar) return "차량 데이터 연결 중";
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
