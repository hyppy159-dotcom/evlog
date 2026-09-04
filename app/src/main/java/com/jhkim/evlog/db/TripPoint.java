package com.jhkim.evlog.db;

/** 주행 경로의 점 하나. */
public class TripPoint {
    public long ts;
    public double lat;
    public double lon;
    /** 그 지점의 속도(km/h). 모르면 -1 */
    public double speedKmh = -1;
    /** 그 지점의 배터리 잔량(%). 모르면 -1 */
    public double soc = -1;

    public TripPoint() {
    }

    public TripPoint(long ts, double lat, double lon, double speedKmh, double soc) {
        this.ts = ts;
        this.lat = lat;
        this.lon = lon;
        this.speedKmh = speedKmh;
        this.soc = soc;
    }
}
