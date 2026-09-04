package com.jhkim.evlog.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 화면에 쓰는 숫자·날짜 표기. */
public final class Fmt {

    private static final SimpleDateFormat DATE_TIME =
            new SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREA);
    private static final SimpleDateFormat DATE_ONLY =
            new SimpleDateFormat("M/d", Locale.KOREA);
    private static final SimpleDateFormat FILE_STAMP =
            new SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA);
    private static final SimpleDateFormat ISO =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA);

    private Fmt() {
    }

    public static String dateTime(long ts) {
        return DATE_TIME.format(new Date(ts));
    }

    public static String dateShort(long ts) {
        return DATE_ONLY.format(new Date(ts));
    }

    public static String fileStamp(long ts) {
        return FILE_STAMP.format(new Date(ts));
    }

    public static String iso(long ts) {
        return ISO.format(new Date(ts));
    }

    public static String km(double km) {
        return String.format(Locale.KOREA, "%.1f km", km);
    }

    public static String kwh(double kwh) {
        return String.format(Locale.KOREA, "%.1f kWh", kwh);
    }

    /** 원 단위, 천 단위 쉼표 */
    public static String won(double v) {
        return String.format(Locale.KOREA, "%,d원", Math.round(v));
    }

    public static String pct(double v) {
        return String.format(Locale.KOREA, "%.0f%%", v);
    }

    /** 전비 km/kWh. 음수면 하이픈 */
    public static String eff(double kmPerKwh) {
        if (kmPerKwh <= 0) return "—";
        return String.format(Locale.KOREA, "%.1f km/kWh", kmPerKwh);
    }

    public static String duration(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        if (h > 0) return String.format(Locale.KOREA, "%d시간 %d분", h, m);
        if (m > 0) return String.format(Locale.KOREA, "%d분", m);
        return String.format(Locale.KOREA, "%d초", seconds);
    }
}
