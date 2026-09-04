package com.jhkim.evlog.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/** 주행·충전·경로 기록 저장소. */
public class Db extends SQLiteOpenHelper {

    private static final String DB_NAME = "evlog.db";
    /** 2: 서버 연동용 uid·synced 열과 경로점 테이블 추가 */
    private static final int DB_VERSION = 2;

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
                + "uid TEXT NOT NULL DEFAULT '',"
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
                + "note TEXT,"
                + "synced INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_trips_start ON trips(start_ts DESC)");
        db.execSQL("CREATE INDEX idx_trips_synced ON trips(synced)");

        db.execSQL("CREATE TABLE charges("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "uid TEXT NOT NULL DEFAULT '',"
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
                + "note TEXT,"
                + "synced INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_charges_start ON charges(start_ts DESC)");
        db.execSQL("CREATE INDEX idx_charges_synced ON charges(synced)");

        createPoints(db);
    }

    private void createPoints(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS points("
                + "trip_id INTEGER NOT NULL,"
                + "seq INTEGER NOT NULL,"
                + "ts INTEGER NOT NULL,"
                + "lat REAL NOT NULL,"
                + "lon REAL NOT NULL,"
                + "speed_kmh REAL NOT NULL DEFAULT -1,"
                + "soc REAL NOT NULL DEFAULT -1,"
                + "PRIMARY KEY(trip_id, seq))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE trips ADD COLUMN uid TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE trips ADD COLUMN synced INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE charges ADD COLUMN uid TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE charges ADD COLUMN synced INTEGER NOT NULL DEFAULT 0");
            createPoints(db);
            // 이미 쌓인 기록에도 고유 id를 붙여 줍니다.
            db.execSQL("UPDATE trips SET uid = 't' || id || '-' || start_ts WHERE uid = ''");
            db.execSQL("UPDATE charges SET uid = 'c' || id || '-' || start_ts WHERE uid = ''");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_trips_synced ON trips(synced)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_charges_synced ON charges(synced)");
        }
    }

    private static String newUid() {
        return UUID.randomUUID().toString();
    }

    // ---------------- 주행 ----------------

    public long insertTrip(Trip t) {
        return insertTrip(t, null);
    }

    /** 주행과 그 경로를 함께 저장합니다. */
    public long insertTrip(Trip t, List<TripPoint> route) {
        if (t.uid == null || t.uid.isEmpty()) t.uid = newUid();
        ContentValues v = new ContentValues();
        v.put("uid", t.uid);
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
        v.put("synced", 0);

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long id = db.insert("trips", null, v);
            t.id = id;
            if (id > 0 && route != null && !route.isEmpty()) {
                int seq = 0;
                for (TripPoint p : route) {
                    ContentValues pv = new ContentValues();
                    pv.put("trip_id", id);
                    pv.put("seq", seq++);
                    pv.put("ts", p.ts);
                    pv.put("lat", p.lat);
                    pv.put("lon", p.lon);
                    pv.put("speed_kmh", p.speedKmh);
                    pv.put("soc", p.soc);
                    db.insert("points", null, pv);
                }
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public void deleteTrip(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("points", "trip_id=?", new String[]{String.valueOf(id)});
            db.delete("trips", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<Trip> listTrips(int limit) {
        return queryTrips("SELECT * FROM trips ORDER BY start_ts DESC LIMIT ?",
                new String[]{String.valueOf(limit)}, false);
    }

    /** 아직 서버로 안 올라간 주행. */
    public List<Trip> unsyncedTrips(int limit) {
        return queryTrips("SELECT * FROM trips WHERE synced=0 ORDER BY start_ts LIMIT ?",
                new String[]{String.valueOf(limit)}, false);
    }

    /** 전비가 계산된 최근 주행을 오래된 것부터 반환(차트용). */
    public List<Trip> recentTripsWithEfficiency(int limit) {
        return queryTrips("SELECT * FROM trips WHERE used_wh > 0 AND distance_m > 500 "
                + "ORDER BY start_ts DESC LIMIT ?", new String[]{String.valueOf(limit)}, true);
    }

    private List<Trip> queryTrips(String sql, String[] args, boolean reverse) {
        List<Trip> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            while (c.moveToNext()) {
                if (reverse) out.add(0, readTrip(c));
                else out.add(readTrip(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    private Trip readTrip(Cursor c) {
        Trip t = new Trip();
        t.id = c.getLong(c.getColumnIndexOrThrow("id"));
        t.uid = c.getString(c.getColumnIndexOrThrow("uid"));
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
        t.synced = c.getInt(c.getColumnIndexOrThrow("synced")) == 1;
        return t;
    }

    // ---------------- 경로 ----------------

    public List<TripPoint> pointsFor(long tripId) {
        List<TripPoint> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, lat, lon, speed_kmh, soc FROM points WHERE trip_id=? ORDER BY seq",
                new String[]{String.valueOf(tripId)});
        try {
            while (c.moveToNext()) {
                out.add(new TripPoint(c.getLong(0), c.getDouble(1), c.getDouble(2),
                        c.getDouble(3), c.getDouble(4)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public int pointCount(long tripId) {
        return (int) scalar("SELECT COUNT(*) FROM points WHERE trip_id=?",
                new String[]{String.valueOf(tripId)});
    }

    // ---------------- 충전 ----------------

    public long insertCharge(Charge ch) {
        if (ch.uid == null || ch.uid.isEmpty()) ch.uid = newUid();
        ContentValues v = new ContentValues();
        v.put("uid", ch.uid);
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
        v.put("synced", 0);
        long id = getWritableDatabase().insert("charges", null, v);
        ch.id = id;
        return id;
    }

    public void deleteCharge(long id) {
        getWritableDatabase().delete("charges", "id=?", new String[]{String.valueOf(id)});
    }

    public List<Charge> listCharges(int limit) {
        return queryCharges("SELECT * FROM charges ORDER BY start_ts DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
    }

    public List<Charge> unsyncedCharges(int limit) {
        return queryCharges("SELECT * FROM charges WHERE synced=0 ORDER BY start_ts LIMIT ?",
                new String[]{String.valueOf(limit)});
    }

    private List<Charge> queryCharges(String sql, String[] args) {
        List<Charge> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            while (c.moveToNext()) {
                Charge ch = new Charge();
                ch.id = c.getLong(c.getColumnIndexOrThrow("id"));
                ch.uid = c.getString(c.getColumnIndexOrThrow("uid"));
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
                ch.synced = c.getInt(c.getColumnIndexOrThrow("synced")) == 1;
                out.add(ch);
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ---------------- 서버 동기화 ----------------

    public void markTripsSynced(List<Long> ids) {
        mark("trips", ids);
    }

    public void markChargesSynced(List<Long> ids) {
        mark("charges", ids);
    }

    private void mark(String table, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Long id : ids) {
                db.execSQL("UPDATE " + table + " SET synced=1 WHERE id=?", new Object[]{id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public int pendingCount() {
        return (int) scalar("SELECT (SELECT COUNT(*) FROM trips WHERE synced=0)"
                + " + (SELECT COUNT(*) FROM charges WHERE synced=0)", null);
    }

    /** 서버를 새로 연결했을 때 전부 다시 올리도록 표시를 지웁니다. */
    public void markAllUnsynced() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE trips SET synced=0");
        db.execSQL("UPDATE charges SET synced=0");
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
        db.execSQL("DELETE FROM points");
        db.execSQL("DELETE FROM trips");
        db.execSQL("DELETE FROM charges");
    }
}
