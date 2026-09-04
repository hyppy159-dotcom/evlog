package com.jhkim.evlog.db;

/** 주행 1건. */
public class Trip {
    public long id;
    /** 서버와 맞추는 고유 id. 저장할 때 자동으로 붙습니다. */
    public String uid = "";
    public long startTs;
    public long endTs;
    /** 주행거리(m) */
    public double distanceM;
    /** 실제 움직인 시간(초) */
    public long movingS;
    /** 시작~종료 전체 시간(초) */
    public long totalS;
    public double avgKmh;
    public double maxKmh;
    /** 배터리 잔량 %. 모르면 -1 */
    public double startSoc = -1;
    public double endSoc = -1;
    /** 소비 전력량(Wh). 모르면 -1 */
    public double usedWh = -1;
    public double startLat, startLon, endLat, endLon;
    /** "car" 또는 "gps" */
    public String source = "gps";
    public String note = "";
    /** 서버로 올라갔는지 */
    public boolean synced;

    public double km() {
        return distanceM / 1000.0;
    }

    /** 전비 km/kWh. 계산 불가면 -1 */
    public double efficiencyKmPerKwh() {
        if (usedWh <= 0 || distanceM <= 0) return -1;
        return (distanceM / 1000.0) / (usedWh / 1000.0);
    }

    /** 전력 소비율 Wh/km. 계산 불가면 -1 */
    public double whPerKm() {
        if (usedWh <= 0 || distanceM <= 0) return -1;
        return usedWh / (distanceM / 1000.0);
    }
}
