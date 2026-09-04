package com.jhkim.evlog.vehicle;

/** 특정 시점에 읽어낸 차량/기기 상태 한 묶음. 모르는 값은 has* 가 false 입니다. */
public class VehicleSnapshot {

    public long ts;

    public boolean hasSpeed;
    public float speedKmh;

    /** 배터리 잔량 % */
    public boolean hasSoc;
    public float socPct;

    /** 배터리 잔여 에너지(Wh) */
    public boolean hasBatteryWh;
    public float batteryWh;

    /** 배터리 총 용량(Wh) */
    public boolean hasCapacityWh;
    public float capacityWh;

    /** 충전구 연결 여부 */
    public boolean hasPortConnected;
    public boolean portConnected;

    /** 현재 충전 출력(kW) */
    public boolean hasChargeKw;
    public float chargeKw;

    public boolean hasRangeKm;
    public float rangeKm;

    public boolean hasOutsideTempC;
    public float outsideTempC;

    /** 시동/전원 상태를 읽을 수 있는 경우 */
    public boolean hasIgnition;
    public boolean ignitionOn;

    public boolean hasLocation;
    public double lat;
    public double lon;
    /** 위치 정확도(m) */
    public float accuracyM = 9999f;

    /** 차량 데이터가 하나라도 들어왔는지 */
    public boolean fromCar;

    public boolean movingAtLeast(float kmh) {
        return hasSpeed && speedKmh >= kmh;
    }
}
