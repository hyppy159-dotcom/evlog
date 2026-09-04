package com.jhkim.evlog.vehicle;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * AAOS(안드로이드 오토모티브)의 차량 데이터를 읽습니다.
 * <p>
 * android.car 라이브러리는 차량에만 존재하므로 컴파일 의존성을 만들지 않고
 * 전부 리플렉션으로 접근합니다. 그래서 이 APK 하나로 휴대폰에서도 동작합니다.
 */
public class CarApiSource {

    private static final String TAG = "CarApiSource";
    private static final int AREA_GLOBAL = 0;

    private Object car;
    private Object propertyManager;
    private Method mGetFloat;
    private Method mGetBoolean;
    private Method mGetInt;

    private int pSpeed;
    private int pBatteryLevel;
    private int pBatteryCapacity;
    private int pPortConnected;
    private int pChargeRate;
    private int pRange;
    private int pOutsideTemp;
    private int pIgnitionState;

    private float capacityWhCache = -1;
    private boolean started;

    /** 이 기기가 차량이고 android.car 를 쓸 수 있는지. */
    public static boolean isAvailable(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (!pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false;
            Class.forName("android.car.Car");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean start(Context ctx) {
        if (started) return true;
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            Method create = carCls.getMethod("createCar", Context.class);
            car = create.invoke(null, ctx.getApplicationContext());
            if (car == null) return false;

            Method getManager = carCls.getMethod("getCarManager", String.class);
            // Car.PROPERTY_SERVICE 의 값은 "property" 입니다.
            propertyManager = getManager.invoke(car, propertyServiceName(carCls));
            if (propertyManager == null) return false;

            Class<?> pmCls = propertyManager.getClass();
            mGetFloat = pmCls.getMethod("getFloatProperty", int.class, int.class);
            mGetBoolean = pmCls.getMethod("getBooleanProperty", int.class, int.class);
            mGetInt = pmCls.getMethod("getIntProperty", int.class, int.class);

            pSpeed = propId("PERF_VEHICLE_SPEED");
            pBatteryLevel = propId("EV_BATTERY_LEVEL");
            pBatteryCapacity = propId("INFO_EV_BATTERY_CAPACITY");
            pPortConnected = propId("EV_CHARGE_PORT_CONNECTED");
            pChargeRate = propId("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE");
            pRange = propId("RANGE_REMAINING");
            pOutsideTemp = propId("ENV_OUTSIDE_TEMPERATURE");
            pIgnitionState = propId("IGNITION_STATE");

            started = true;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "차량 API 연결 실패: " + t);
            stop();
            return false;
        }
    }

    private static String propertyServiceName(Class<?> carCls) {
        try {
            Object v = carCls.getField("PROPERTY_SERVICE").get(null);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {
        }
        return "property";
    }

    private static int propId(String fieldName) {
        try {
            Class<?> ids = Class.forName("android.car.VehiclePropertyIds");
            return ids.getField(fieldName).getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    public void stop() {
        try {
            if (car != null) {
                Method disconnect = car.getClass().getMethod("disconnect");
                disconnect.invoke(car);
            }
        } catch (Throwable ignored) {
        }
        car = null;
        propertyManager = null;
        started = false;
    }

    public boolean isStarted() {
        return started;
    }

    private Float readFloat(int prop) {
        if (!started || prop == 0) return null;
        try {
            Object v = mGetFloat.invoke(propertyManager, prop, AREA_GLOBAL);
            if (v instanceof Float) {
                float f = (Float) v;
                if (Float.isNaN(f) || Float.isInfinite(f)) return null;
                return f;
            }
        } catch (Throwable ignored) {
            // 권한 없음 / 이 차에 없는 속성 → 그냥 모르는 값으로 둡니다.
        }
        return null;
    }

    private Boolean readBoolean(int prop) {
        if (!started || prop == 0) return null;
        try {
            Object v = mGetBoolean.invoke(propertyManager, prop, AREA_GLOBAL);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Integer readInt(int prop) {
        if (!started || prop == 0) return null;
        try {
            Object v = mGetInt.invoke(propertyManager, prop, AREA_GLOBAL);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 충전 출력값의 단위가 기기마다 mW / W / kW 로 제각각이라 크기로 판별합니다.
     */
    private static float normalizeChargeKw(float raw) {
        float v = Math.abs(raw);
        if (v > 200000f) return v / 1_000_000f;  // mW
        if (v > 200f) return v / 1000f;          // W
        return v;                                 // 이미 kW
    }

    /** 차량에서 읽을 수 있는 값들을 snapshot 에 채웁니다. */
    public void fill(VehicleSnapshot s, float fallbackCapacityKwh) {
        if (!started) return;

        Float speedMs = readFloat(pSpeed);
        if (speedMs != null) {
            s.hasSpeed = true;
            s.speedKmh = Math.abs(speedMs) * 3.6f;
            s.fromCar = true;
        }

        if (capacityWhCache <= 0) {
            Float cap = readFloat(pBatteryCapacity);
            if (cap != null && cap > 1000f) capacityWhCache = cap;
        }
        float capWh = capacityWhCache > 0 ? capacityWhCache : fallbackCapacityKwh * 1000f;
        if (capacityWhCache > 0) {
            s.hasCapacityWh = true;
            s.capacityWh = capacityWhCache;
        }

        Float levelWh = readFloat(pBatteryLevel);
        if (levelWh != null && levelWh >= 0) {
            s.hasBatteryWh = true;
            s.batteryWh = levelWh;
            s.fromCar = true;
            if (capWh > 0) {
                s.hasSoc = true;
                s.socPct = Math.max(0f, Math.min(100f, levelWh / capWh * 100f));
            }
        }

        Boolean port = readBoolean(pPortConnected);
        if (port != null) {
            s.hasPortConnected = true;
            s.portConnected = port;
            s.fromCar = true;
        }

        Float rate = readFloat(pChargeRate);
        if (rate != null) {
            s.hasChargeKw = true;
            s.chargeKw = normalizeChargeKw(rate);
        }

        Float rangeM = readFloat(pRange);
        if (rangeM != null && rangeM >= 0) {
            s.hasRangeKm = true;
            // 대부분 m 단위지만 km 로 주는 구현도 있어 크기로 판별합니다.
            s.rangeKm = rangeM > 2000f ? rangeM / 1000f : rangeM;
        }

        Float temp = readFloat(pOutsideTemp);
        if (temp != null && temp > -60f && temp < 80f) {
            s.hasOutsideTempC = true;
            s.outsideTempC = temp;
        }

        Integer ign = readInt(pIgnitionState);
        if (ign != null) {
            s.hasIgnition = true;
            // 0 UNDEFINED, 1 LOCK, 2 OFF, 3 ACC, 4 ON, 5 START
            s.ignitionOn = ign >= 4;
        }
    }
}
