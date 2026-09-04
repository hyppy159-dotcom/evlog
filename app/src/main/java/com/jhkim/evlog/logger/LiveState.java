package com.jhkim.evlog.logger;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** 서비스가 갱신하고 화면이 구독하는 현재 상태. */
public final class LiveState {

    public static volatile boolean serviceRunning;
    public static volatile String sourceLabel = "대기 중";

    public static volatile boolean tripActive;
    public static volatile double tripKm;
    public static volatile long tripElapsedS;
    public static volatile float speedKmh = -1;

    public static volatile float socPct = -1;
    public static volatile float rangeKm = -1;
    public static volatile float outsideTempC = -999;

    public static volatile boolean chargeActive;
    public static volatile double chargeKwh;
    public static volatile float chargeKw = -1;

    /** 기록이 새로 저장되어 목록을 다시 읽어야 할 때 증가합니다. */
    public static volatile int dataRevision;

    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private LiveState() {
    }

    public static void addListener(Runnable r) {
        if (r != null && !listeners.contains(r)) listeners.add(r);
    }

    public static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    public static void notifyChanged() {
        for (final Runnable r : listeners) {
            main.post(r);
        }
    }

    public static void bumpData() {
        dataRevision++;
        notifyChanged();
    }

    public static void reset() {
        tripActive = false;
        tripKm = 0;
        tripElapsedS = 0;
        speedKmh = -1;
        chargeActive = false;
        chargeKwh = 0;
        chargeKw = -1;
    }
}
