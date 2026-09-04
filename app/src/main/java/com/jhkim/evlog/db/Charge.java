package com.jhkim.evlog.db;

/** 충전 1건. */
public class Charge {
    public static final String AC = "AC";
    public static final String DC = "DC";

    public long id;
    public long startTs;
    public long endTs;
    public double startSoc = -1;
    public double endSoc = -1;
    /** 충전된 전력량(Wh) */
    public double addedWh;
    /** AC(완속) 또는 DC(급속) */
    public String kind = AC;
    /** 세션 중 관측된 최대 출력(kW). 모르면 -1 */
    public double maxKw = -1;
    /** 비용(원) */
    public double cost;
    public double lat, lon;
    /** 사용자가 직접 입력했으면 true */
    public boolean manual;
    public String note = "";

    public double kwh() {
        return addedWh / 1000.0;
    }

    public boolean isDc() {
        return DC.equals(kind);
    }

    /** 충전 시간(분). 모르면 -1 */
    public long minutes() {
        if (endTs <= startTs) return -1;
        return (endTs - startTs) / 60000L;
    }
}
