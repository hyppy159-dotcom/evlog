package com.jhkim.evlog.vehicle;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * AAOS(안드로이드 오토모티브)의 차량 데이터를 읽습니다.
 * <p>
 * android.car 라이브러리는 차량에만 존재하므로 컴파일 의존성을 만들지 않고
 * 전부 리플렉션으로 접근합니다. 그래서 이 APK 하나로 휴대폰에서도 동작합니다.
 * <p>
 * 차마다 제공하는 속성과 단위가 달라서, 값을 그대로 믿지 않고
 * 크기로 단위를 판별하고 실패 이유를 남겨 둡니다({@link #diagnose}).
 */
public class CarApiSource {

    private static final String TAG = "CarApiSource";
    private static final int AREA_GLOBAL = 0;

    /** 차량 데이터에 필요한 권한들. */
    public static final String[] CAR_PERMISSIONS = {
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_ENERGY_PORTS",
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_INFO",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"
    };

    /** 진단 화면에 보여줄 속성 목록: 표시 이름, VehiclePropertyIds 필드 이름, 필요한 권한 */
    private static final String[][] PROBE_LIST = {
            {"배터리 잔량", "EV_BATTERY_LEVEL", "CAR_ENERGY"},
            {"배터리 총 용량", "INFO_EV_BATTERY_CAPACITY", "CAR_INFO"},
            {"주행 가능 거리", "RANGE_REMAINING", "CAR_ENERGY"},
            {"충전구 연결", "EV_CHARGE_PORT_CONNECTED", "CAR_ENERGY_PORTS"},
            {"충전 출력", "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", "CAR_ENERGY"},
            {"주행 속도", "PERF_VEHICLE_SPEED", "CAR_SPEED"},
            {"시동 상태", "IGNITION_STATE", "CAR_POWERTRAIN"},
            {"기어", "GEAR_SELECTION", "CAR_POWERTRAIN"},
            {"외기 온도", "ENV_OUTSIDE_TEMPERATURE", "CAR_EXTERIOR_ENVIRONMENT"},
    };

    private Object car;
    private Object propertyManager;
    private Method mGetProperty;
    private Method mGetFloat;
    private Method mGetBoolean;
    private Method mGetInt;
    private Method mGetConfig;

    private int pSpeed;
    private int pBatteryLevel;
    private int pBatteryCapacity;
    private int pPortConnected;
    private int pChargeRate;
    private int pRange;
    private int pOutsideTemp;
    private int pIgnitionState;

    private float capacityWhCache = -1;
    private volatile boolean started;
    /** 마지막으로 배터리를 읽지 못한 이유. 진단과 화면 안내에 씁니다. */
    private volatile String lastBatteryError = "아직 시도하지 않음";

    // ---------------------------------------------------------------- 연결

    /** 이 기기가 차량이고 android.car 를 쓸 수 있는지. */
    public static boolean isAvailable(Context ctx) {
        try {
            if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false;
            Class.forName("android.car.Car");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 차량 권한이 모두 허용됐는지. */
    public static boolean hasAllPermissions(Context ctx) {
        for (String p : CAR_PERMISSIONS) {
            if (!hasPermission(ctx, p)) return false;
        }
        return true;
    }

    public static boolean hasPermission(Context ctx, String permission) {
        try {
            return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 차량 서비스에 연결합니다. 부팅 직후에는 서비스가 아직 안 떠 있을 수 있어
     * 실패해도 예외를 던지지 않고 false 만 돌려줍니다. 호출 쪽에서 다시 시도하세요.
     * <p>차량 서비스 연결은 잠깐 블로킹될 수 있으니 백그라운드 스레드에서 부르세요.
     */
    public synchronized boolean start(Context ctx) {
        if (started && isConnected()) return true;
        release();
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            Method create = carCls.getMethod("createCar", Context.class);
            car = create.invoke(null, ctx.getApplicationContext());
            if (car == null) {
                lastBatteryError = "차량 서비스에 연결하지 못했습니다(createCar 가 null).";
                return false;
            }

            Method getManager = carCls.getMethod("getCarManager", String.class);
            propertyManager = getManager.invoke(car, propertyServiceName(carCls));
            if (propertyManager == null) {
                lastBatteryError = "차량 속성 서비스를 가져오지 못했습니다.";
                release();
                return false;
            }

            Class<?> pmCls = propertyManager.getClass();
            mGetProperty = findMethod(pmCls, "getProperty", int.class, int.class);
            mGetFloat = findMethod(pmCls, "getFloatProperty", int.class, int.class);
            mGetBoolean = findMethod(pmCls, "getBooleanProperty", int.class, int.class);
            mGetInt = findMethod(pmCls, "getIntProperty", int.class, int.class);
            mGetConfig = findMethod(pmCls, "getCarPropertyConfig", int.class);

            pSpeed = propId("PERF_VEHICLE_SPEED");
            pBatteryLevel = propId("EV_BATTERY_LEVEL");
            pBatteryCapacity = propId("INFO_EV_BATTERY_CAPACITY");
            pPortConnected = propId("EV_CHARGE_PORT_CONNECTED");
            pChargeRate = propId("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE");
            pRange = propId("RANGE_REMAINING");
            pOutsideTemp = propId("ENV_OUTSIDE_TEMPERATURE");
            pIgnitionState = propId("IGNITION_STATE");

            started = true;
            lastBatteryError = "연결됨. 아직 값을 읽지 않음";
            return true;
        } catch (Throwable t) {
            lastBatteryError = "차량 API 연결 실패: " + rootMessage(t);
            Log.w(TAG, "차량 API 연결 실패", t);
            release();
            return false;
        }
    }

    private boolean isConnected() {
        if (car == null) return false;
        try {
            Method m = car.getClass().getMethod("isConnected");
            Object v = m.invoke(car);
            return !(v instanceof Boolean) || (Boolean) v;
        } catch (Throwable t) {
            return true;   // 확인할 방법이 없으면 연결된 것으로 봅니다.
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... args) {
        try {
            return cls.getMethod(name, args);
        } catch (Throwable t) {
            return null;
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

    private void release() {
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

    public void stop() {
        release();
    }

    public boolean isStarted() {
        return started;
    }

    public String lastBatteryError() {
        return lastBatteryError;
    }

    // ---------------------------------------------------------------- 읽기

    /**
     * 속성 하나를 읽습니다. 차마다 같은 속성을 float 로도 int 로도 내놓기 때문에
     * 일반 getProperty 를 먼저 쓰고, 없으면 타입별 게터로 넘어갑니다.
     *
     * @param err 실패 사유를 담아 갈 버퍼(선택)
     */
    private Object readRaw(int prop, StringBuilder err) {
        if (!started || prop == 0) {
            if (err != null) err.append(prop == 0 ? "이 안드로이드 버전에 없는 속성" : "차량 미연결");
            return null;
        }
        Throwable first = null;

        if (mGetProperty != null) {
            try {
                Object cpv = mGetProperty.invoke(propertyManager, prop, AREA_GLOBAL);
                if (cpv != null) {
                    Object v = cpv.getClass().getMethod("getValue").invoke(cpv);
                    if (v != null) return v;
                }
            } catch (Throwable t) {
                first = t;
            }
        }

        Method[] typed = {mGetFloat, mGetInt, mGetBoolean};
        for (Method m : typed) {
            if (m == null) continue;
            try {
                Object v = m.invoke(propertyManager, prop, AREA_GLOBAL);
                if (v != null) return v;
            } catch (Throwable t) {
                if (first == null) first = t;
            }
        }

        if (err != null) err.append(first != null ? rootMessage(first) : "값 없음");
        return null;
    }

    private Float readNumber(int prop, StringBuilder err) {
        Object v = readRaw(prop, err);
        if (v instanceof Number) {
            float f = ((Number) v).floatValue();
            if (Float.isNaN(f) || Float.isInfinite(f)) return null;
            return f;
        }
        if (v instanceof Boolean) return ((Boolean) v) ? 1f : 0f;
        if (v != null && err != null) err.append("예상 못한 타입 ").append(v.getClass().getSimpleName());
        return null;
    }

    private Boolean readBool(int prop, StringBuilder err) {
        Object v = readRaw(prop, err);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return null;
    }

    /** 충전 출력 단위가 mW / W / kW 로 제각각이라 크기로 판별합니다. */
    private static float normalizeChargeKw(float raw) {
        float v = Math.abs(raw);
        if (v > 200000f) return v / 1_000_000f;
        if (v > 200f) return v / 1000f;
        return v;
    }

    /**
     * 배터리 잔량을 % 로 환산합니다.
     * 표준은 Wh 지만 실제로는 % 나 kWh 로 주는 차도 있어, 용량과 대조해 가장 그럴듯한 해석을 고릅니다.
     *
     * @return 0~100, 판단 불가면 -1
     */
    static float toSocPercent(float raw, float capacityWh) {
        if (raw < 0) return -1;

        // 용량을 아는 경우: Wh 로 봤을 때 비율이 말이 되면 그게 맞습니다.
        if (capacityWh > 1000f) {
            float asWh = raw / capacityWh * 100f;
            if (asWh >= 0f && asWh <= 105f) return Math.min(100f, asWh);

            float asKwh = raw * 1000f / capacityWh * 100f;
            if (asKwh >= 0f && asKwh <= 105f && raw <= 500f) return Math.min(100f, asKwh);
        }

        // 용량을 모르거나 위 해석이 다 어긋나면 크기로 판단합니다.
        if (raw <= 100f) return raw;                       // 퍼센트로 주는 차
        if (raw <= 500f) return -1;                        // kWh 인데 용량을 몰라 환산 불가
        return -1;                                          // Wh 인데 용량을 몰라 환산 불가
    }

    /** 차량에서 읽을 수 있는 값들을 snapshot 에 채웁니다. */
    public void fill(VehicleSnapshot s, float fallbackCapacityKwh) {
        if (!started) return;

        Float speedMs = readNumber(pSpeed, null);
        if (speedMs != null) {
            s.hasSpeed = true;
            s.speedKmh = Math.abs(speedMs) * 3.6f;
            s.fromCar = true;
        }

        // 총 용량은 한 번만 읽어 캐시합니다.
        if (capacityWhCache <= 0) {
            Float cap = readNumber(pBatteryCapacity, null);
            if (cap != null && cap > 0) {
                // 용량도 Wh / kWh 가 섞여 있습니다.
                capacityWhCache = cap > 1000f ? cap : cap * 1000f;
            }
        }
        float capWh = capacityWhCache > 0 ? capacityWhCache : fallbackCapacityKwh * 1000f;
        if (capacityWhCache > 0) {
            s.hasCapacityWh = true;
            s.capacityWh = capacityWhCache;
        }

        StringBuilder err = new StringBuilder();
        Float level = readNumber(pBatteryLevel, err);
        if (level == null) {
            lastBatteryError = err.length() > 0 ? err.toString() : "값을 받지 못함";
        } else {
            float soc = toSocPercent(level, capWh);
            if (soc < 0) {
                lastBatteryError = String.format(Locale.KOREA,
                        "잔량 원본값 %.1f 을 용량 %.0fWh 기준으로 해석하지 못했습니다.", level, capWh);
            } else {
                lastBatteryError = "정상";
                s.hasSoc = true;
                s.socPct = Math.max(0f, Math.min(100f, soc));
                s.fromCar = true;

                // 잔여 에너지는 Wh 로 볼 수 있을 때만 채웁니다(주행 소비량 계산용).
                if (capWh > 0) {
                    s.hasBatteryWh = true;
                    s.batteryWh = s.socPct / 100f * capWh;
                }
            }
        }

        Boolean port = readBool(pPortConnected, null);
        if (port != null) {
            s.hasPortConnected = true;
            s.portConnected = port;
            s.fromCar = true;
        }

        Float rate = readNumber(pChargeRate, null);
        if (rate != null) {
            s.hasChargeKw = true;
            s.chargeKw = normalizeChargeKw(rate);
        }

        Float range = readNumber(pRange, null);
        if (range != null && range >= 0) {
            s.hasRangeKm = true;
            s.rangeKm = range > 2000f ? range / 1000f : range;   // m 로 주는 차 / km 로 주는 차
        }

        Float temp = readNumber(pOutsideTemp, null);
        if (temp != null && temp > -60f && temp < 80f) {
            s.hasOutsideTempC = true;
            s.outsideTempC = temp;
        }

        Float ign = readNumber(pIgnitionState, null);
        if (ign != null) {
            s.hasIgnition = true;
            // 0 UNDEFINED, 1 LOCK, 2 OFF, 3 ACC, 4 ON, 5 START
            s.ignitionOn = ign.intValue() >= 4;
        }
    }

    // ---------------------------------------------------------------- 진단

    /**
     * 차량 데이터가 왜 안 나오는지 사람이 읽을 수 있는 보고서로 만듭니다.
     * 백그라운드 스레드에서 부르세요.
     */
    public static String diagnose(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("■ 기기\n");
        boolean automotive = false;
        try {
            automotive = ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
        } catch (Throwable ignored) {
        }
        sb.append("  오토모티브 기기: ").append(automotive ? "예" : "아니오 (휴대폰/태블릿)").append('\n');

        boolean carLib;
        try {
            Class.forName("android.car.Car");
            carLib = true;
        } catch (Throwable t) {
            carLib = false;
        }
        sb.append("  android.car 라이브러리: ").append(carLib ? "있음" : "없음").append('\n');
        sb.append("  안드로이드 ").append(android.os.Build.VERSION.RELEASE)
                .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("  ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n');

        sb.append("\n■ 권한\n");
        for (String p : CAR_PERMISSIONS) {
            String shortName = p.substring(p.lastIndexOf('.') + 1);
            sb.append("  ").append(shortName).append(": ")
                    .append(hasPermission(ctx, p) ? "허용" : "거부/미허용").append('\n');
        }

        if (!automotive || !carLib) {
            sb.append("\n차량이 아니라서 차량 데이터를 읽을 수 없습니다. GPS 기반으로만 동작합니다.\n");
            return sb.toString();
        }

        sb.append("\n■ 연결\n");
        CarApiSource src = new CarApiSource();
        boolean ok = src.start(ctx);
        sb.append("  차량 서비스: ").append(ok ? "연결됨" : "실패").append('\n');
        if (!ok) {
            sb.append("  사유: ").append(src.lastBatteryError()).append('\n');
            return sb.toString();
        }

        sb.append("\n■ 속성별 읽기 결과\n");
        try {
            for (String[] row : PROBE_LIST) {
                String label = row[0];
                int id = propId(row[1]);
                sb.append("  ").append(label).append(" (").append(row[1]).append(")\n    ");
                if (id == 0) {
                    sb.append("이 안드로이드 버전에 없는 속성\n");
                    continue;
                }
                StringBuilder err = new StringBuilder();
                Object v = src.readRaw(id, err);
                if (v != null) {
                    sb.append("값 = ").append(v).append('\n');
                } else {
                    sb.append("실패: ").append(err.length() > 0 ? err : "알 수 없음").append('\n');
                }
            }

            sb.append("\n■ 배터리 해석\n");
            VehicleSnapshot s = new VehicleSnapshot();
            src.fill(s, com.jhkim.evlog.Prefs.capacityKwh(ctx));
            sb.append("  총 용량: ").append(s.hasCapacityWh
                    ? String.format(Locale.KOREA, "%.1f kWh (차량에서 읽음)", s.capacityWh / 1000f)
                    : String.format(Locale.KOREA, "%.1f kWh (설정값 사용)", com.jhkim.evlog.Prefs.capacityKwh(ctx)))
                    .append('\n');
            sb.append("  잔량: ").append(s.hasSoc
                    ? String.format(Locale.KOREA, "%.1f%%", s.socPct) : "읽지 못함").append('\n');
            sb.append("  상태: ").append(src.lastBatteryError()).append('\n');
        } finally {
            src.stop();
        }

        return sb.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        if (c instanceof InvocationTargetException && c.getCause() != null) c = c.getCause();
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        String name = c.getClass().getSimpleName();
        if ("SecurityException".equals(name)) {
            return "권한 없음 (SecurityException" + (msg != null ? ": " + msg : "") + ")";
        }
        if ("IllegalArgumentException".equals(name)) {
            return "이 차량이 지원하지 않는 속성 (" + (msg != null ? msg : "IllegalArgumentException") + ")";
        }
        return name + (msg != null ? ": " + msg : "");
    }
}
