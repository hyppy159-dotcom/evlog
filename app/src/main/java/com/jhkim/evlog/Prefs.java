package com.jhkim.evlog;

import android.content.Context;
import android.content.SharedPreferences;

/** 앱 설정값 보관소. */
public final class Prefs {

    private static final String NAME = "evlog_prefs";

    private static final String K_CAPACITY_KWH = "capacity_kwh";
    private static final String K_RATE_AC = "rate_ac";
    private static final String K_RATE_DC = "rate_dc";
    private static final String K_DC_KW = "dc_threshold_kw";
    private static final String K_LOGGING = "logging_enabled";
    private static final String K_AUTOSTART = "auto_start";
    private static final String K_MIN_TRIP_M = "min_trip_m";
    private static final String K_SERVER_URL = "server_url";
    private static final String K_SERVER_TOKEN = "server_token";
    private static final String K_SYNC_AUTO = "sync_auto";
    private static final String K_ROUTE_ON = "route_on";
    private static final String K_LAST_SYNC = "last_sync_ts";
    private static final String K_LAST_SYNC_MSG = "last_sync_msg";

    /** 폴스타4 롱레인지 기준 기본값(kWh). 설정에서 바꿀 수 있습니다. */
    public static final float DEF_CAPACITY_KWH = 100f;
    /** 완속(AC) 기본 단가 원/kWh */
    public static final float DEF_RATE_AC = 324f;
    /** 급속(DC) 기본 단가 원/kWh */
    public static final float DEF_RATE_DC = 347f;
    /** 이 출력 이상이면 급속으로 분류(kW) */
    public static final float DEF_DC_KW = 15f;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static float capacityKwh(Context c) {
        return sp(c).getFloat(K_CAPACITY_KWH, DEF_CAPACITY_KWH);
    }

    public static void setCapacityKwh(Context c, float v) {
        sp(c).edit().putFloat(K_CAPACITY_KWH, v).apply();
    }

    public static float rateAc(Context c) {
        return sp(c).getFloat(K_RATE_AC, DEF_RATE_AC);
    }

    public static void setRateAc(Context c, float v) {
        sp(c).edit().putFloat(K_RATE_AC, v).apply();
    }

    public static float rateDc(Context c) {
        return sp(c).getFloat(K_RATE_DC, DEF_RATE_DC);
    }

    public static void setRateDc(Context c, float v) {
        sp(c).edit().putFloat(K_RATE_DC, v).apply();
    }

    public static float dcThresholdKw(Context c) {
        return sp(c).getFloat(K_DC_KW, DEF_DC_KW);
    }

    public static void setDcThresholdKw(Context c, float v) {
        sp(c).edit().putFloat(K_DC_KW, v).apply();
    }

    public static boolean loggingEnabled(Context c) {
        return sp(c).getBoolean(K_LOGGING, false);
    }

    public static void setLoggingEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_LOGGING, v).apply();
    }

    public static boolean autoStart(Context c) {
        return sp(c).getBoolean(K_AUTOSTART, true);
    }

    public static void setAutoStart(Context c, boolean v) {
        sp(c).edit().putBoolean(K_AUTOSTART, v).apply();
    }

    public static int minTripMeters(Context c) {
        return sp(c).getInt(K_MIN_TRIP_M, 300);
    }

    public static void setMinTripMeters(Context c, int v) {
        sp(c).edit().putInt(K_MIN_TRIP_M, v).apply();
    }

    // ---------------- NAS 서버 연동 ----------------

    /** 예: https://evlog.내도메인 — 끝에 / 는 없어도 됩니다. */
    public static String serverUrl(Context c) {
        return sp(c).getString(K_SERVER_URL, "").trim();
    }

    public static void setServerUrl(Context c, String v) {
        String s = v == null ? "" : v.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        sp(c).edit().putString(K_SERVER_URL, s).apply();
    }

    public static String serverToken(Context c) {
        return sp(c).getString(K_SERVER_TOKEN, "").trim();
    }

    public static void setServerToken(Context c, String v) {
        sp(c).edit().putString(K_SERVER_TOKEN, v == null ? "" : v.trim()).apply();
    }

    /** 서버 주소와 토큰이 모두 있어야 업로드를 시도합니다. */
    public static boolean serverConfigured(Context c) {
        return !serverUrl(c).isEmpty() && !serverToken(c).isEmpty();
    }

    public static boolean syncAuto(Context c) {
        return sp(c).getBoolean(K_SYNC_AUTO, true);
    }

    public static void setSyncAuto(Context c, boolean v) {
        sp(c).edit().putBoolean(K_SYNC_AUTO, v).apply();
    }

    /** 주행 경로(위치 점) 기록 여부. */
    public static boolean routeEnabled(Context c) {
        return sp(c).getBoolean(K_ROUTE_ON, true);
    }

    public static void setRouteEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_ROUTE_ON, v).apply();
    }

    public static long lastSyncTs(Context c) {
        return sp(c).getLong(K_LAST_SYNC, 0);
    }

    public static String lastSyncMessage(Context c) {
        return sp(c).getString(K_LAST_SYNC_MSG, "");
    }

    public static void setLastSync(Context c, long ts, String message) {
        sp(c).edit().putLong(K_LAST_SYNC, ts)
                .putString(K_LAST_SYNC_MSG, message == null ? "" : message).apply();
    }
}
