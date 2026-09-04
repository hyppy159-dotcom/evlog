package com.jhkim.evlog.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 주행·충전 기록 저장소. */
public class Db extends SQLiteOpenHelper {

    private static final String DB_NAME = "evlog.db";
    private static final int DB_VERSION = 1;

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) {
            instance = new Db(c.getApplicationContext());
        }
        return instance;
    }

    private Db(Context c) {
        super(c, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "start_ts INTEGER NOT NULL,"
                + "end_ts INTEGER NOT NULL,"
                + "distance_m REAL NOT NULL,"
                + "moving_s INTEGER NOT NULL,"
                + "total_s INTEGER NOT NULL,"
                + "avg_kmh REAL NOT NULL,"
                + "max_kmh REAL NOT NULL,"
                + "start_soc REAL NOT NULL,"
                + "end_soc REAL NOT NULL,"
                + "used_wh REAL NOT NULL,"
                + "start_lat REAL NOT NULL,"
                + "start_lon REAL NOT NULL,"
                + "end_lat REAL NOT NULL,"
                + "end_lon REAL NOT NULL,"
                + "source TEXT NOT NULL,"
                + "note TEXT)");
        db.execSQL("CREATE INDEX idx_trips_start ON trips(start_ts DESC)");

        db.execSQL("CREATE TABLE charges("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "start_ts INTEGER NOT NULL,"
                + "end_ts INTEGER NOT NULL,"
                + "start_soc REAL NOT NULL,"
                + "end_soc REAL NOT NULL,"
                + "added_wh REAL NOT NULL,"
                + "kind TEXT NOT NULL,"
                + "max_kw REAL NOT NULL,"
                + "cost REAL NOT NULL,"
                + "lat REAL NOT NULL,"
                + "lon REAL NOT NULL,"
                + "manual INTEGER NOT NULL,"
                + "note TEXT)");
        db.execSQL("CREATE INDEX idx_charges_start ON charges(start_ts DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // 아직 스키마 변경 없음. 이후 버전에서 ALTER TABLE 추가.
    }

    // ---------------- 주행 ----------------

    public long insertTrip(Trip t) {
        ContentValues v = new ContentValues();
        v.put("start_ts", t.startTs);
        v.put("end_ts", t.endTs);
        v.put("distance_m", t.distanceM);
        v.put("moving_s", t.movingS);
        v.put("total_s", t.totalS);
        v.put("avg_kmh", t.avgKmh);
        v.put("max_kmh", t.maxKmh);
        v.put("start_soc", t.startSoc);
        v.put("end_soc", t.endSoc);
        v.put("used_wh", t.usedWh);
        v.put("start_lat", t.startLat);
        v.put("start_lon", t.startLon);
        v.put("end_lat", t.endLat);
        v.put("end_lon", t.endLon);
        v.put("source", t.source);
        v.put("note", t.note == null ? "" : t.note);
        return getWritableDatabase().insert("trips", null, v);
    }

    public void deleteTrip(long id) {
        getWritableDatabase().delete("trips", "id=?", new String[]{String.valueOf(id)});
    }

    public List<Trip> listTrips(int limit) {
        List<Trip> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM trips ORDER BY start_ts DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.add(readTrip(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 전비가 계산된 최근 주행을 오래된 것부터 반환(차트용). */
    public List<Trip> recentTripsWithEfficiency(int limit) {
        List<Trip> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM trips WHERE used_wh > 0 AND distance_m > 500 "
                        + "ORDER BY start_ts DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.add(0, readTrip(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    private Trip readTrip(Cursor c) {
        Trip t = new Trip();
        t.id = c.getLong(c.getColumnIndexOrThrow("id"));
        t.startTs = c.getLong(c.getColumnIndexOrThrow("start_ts"));
        t.endTs = c.getLong(c.getColumnIndexOrThrow("end_ts"));
        t.distanceM = c.getDouble(c.getColumnIndexOrThrow("distance_m"));
        t.movingS = c.getLong(c.getColumnIndexOrThrow("moving_s"));
        t.totalS = c.getLong(c.getColumnIndexOrThrow("total_s"));
        t.avgKmh = c.getDouble(c.getColumnIndexOrThrow("avg_kmh"));
        t.maxKmh = c.getDouble(c.getColumnIndexOrThrow("max_kmh"));
        t.startSoc = c.getDouble(c.getColumnIndexOrThrow("start_soc"));
        t.endSoc = c.getDouble(c.getColumnIndexOrThrow("end_soc"));
        t.usedWh = c.getDouble(c.getColumnIndexOrThrow("used_wh"));
        t.startLat = c.getDouble(c.getColumnIndexOrThrow("start_lat"));
        t.startLon = c.getDouble(c.getColumnIndexOrThrow("start_lon"));
        t.endLat = c.getDouble(c.getColumnIndexOrThrow("end_lat"));
        t.endLon = c.getDouble(c.getColumnIndexOrThrow("end_lon"));
        t.source = c.getString(c.getColumnIndexOrThrow("source"));
        t.note = c.getString(c.getColumnIndexOrThrow("note"));
        return t;
    }

    // ---------------- 충전 ----------------

    public long insertCharge(Charge ch) {
        ContentValues v = new ContentValues();
        v.put("start_ts", ch.startTs);
        v.put("end_ts", ch.endTs);
        v.put("start_soc", ch.startSoc);
        v.put("end_soc", ch.endSoc);
        v.put("added_wh", ch.addedWh);
        v.put("kind", ch.kind);
        v.put("max_kw", ch.maxKw);
        v.put("cost", ch.cost);
        v.put("lat", ch.lat);
        v.put("lon", ch.lon);
        v.put("manual", ch.manual ? 1 : 0);
        v.put("note", ch.note == null ? "" : ch.note);
        return getWritableDatabase().insert("charges", null, v);
    }

    public void deleteCharge(long id) {
        getWritableDatabase().delete("charges", "id=?", new String[]{String.valueOf(id)});
    }

    public List<Charge> listCharges(int limit) {
        List<Charge> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM charges ORDER BY start_ts DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                Charge ch = new Charge();
                ch.id = c.getLong(c.getColumnIndexOrThrow("id"));
                ch.startTs = c.getLong(c.getColumnIndexOrThrow("start_ts"));
                ch.endTs = c.getLong(c.getColumnIndexOrThrow("end_ts"));
                ch.startSoc = c.getDouble(c.getColumnIndexOrThrow("start_soc"));
                ch.endSoc = c.getDouble(c.getColumnIndexOrThrow("end_soc"));
                ch.addedWh = c.getDouble(c.getColumnIndexOrThrow("added_wh"));
                ch.kind = c.getString(c.getColumnIndexOrThrow("kind"));
                ch.maxKw = c.getDouble(c.getColumnIndexOrThrow("max_kw"));
                ch.cost = c.getDouble(c.getColumnIndexOrThrow("cost"));
                ch.lat = c.getDouble(c.getColumnIndexOrThrow("lat"));
                ch.lon = c.getDouble(c.getColumnIndexOrThrow("lon"));
                ch.manual = c.getInt(c.getColumnIndexOrThrow("manual")) == 1;
                ch.note = c.getString(c.getColumnIndexOrThrow("note"));
                out.add(ch);
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ---------------- 집계 ----------------

    /** 이번 달 1일 0시의 타임스탬프. */
    public static long monthStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private double scalar(String sql, String[] args) {
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            if (c.moveToFirst() && !c.isNull(0)) return c.getDouble(0);
        } finally {
            c.close();
        }
        return 0;
    }

    public double totalDistanceM() {
        return scalar("SELECT SUM(distance_m) FROM trips", null);
    }

    public double monthDistanceM() {
        return scalar("SELECT SUM(distance_m) FROM trips WHERE start_ts>=?",
                new String[]{String.valueOf(monthStart())});
    }

    public double monthChargeCost() {
        return scalar("SELECT SUM(cost) FROM charges WHERE start_ts>=?",
                new String[]{String.valueOf(monthStart())});
    }

    public double monthChargeKwh() {
        return scalar("SELECT SUM(added_wh) FROM charges WHERE start_ts>=?",
                new String[]{String.valueOf(monthStart())}) / 1000.0;
    }

    /** 전체 평균 전비 km/kWh. 데이터 없으면 -1 */
    public double averageEfficiency() {
        double dist = scalar("SELECT SUM(distance_m) FROM trips WHERE used_wh>0", null);
        double wh = scalar("SELECT SUM(used_wh) FROM trips WHERE used_wh>0", null);
        if (dist <= 0 || wh <= 0) return -1;
        return (dist / 1000.0) / (wh / 1000.0);
    }

    public int tripCount() {
        return (int) scalar("SELECT COUNT(*) FROM trips", null);
    }

    public int chargeCount() {
        return (int) scalar("SELECT COUNT(*) FROM charges", null);
    }

    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM trips");
        db.execSQL("DELETE FROM charges");
    }
}
