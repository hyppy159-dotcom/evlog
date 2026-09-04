package com.jhkim.evlog.sync;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.jhkim.evlog.Prefs;
import com.jhkim.evlog.db.Charge;
import com.jhkim.evlog.db.Db;
import com.jhkim.evlog.db.Trip;
import com.jhkim.evlog.db.TripPoint;
import com.jhkim.evlog.logger.LiveState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/**
 * 기록을 NAS 서버로 올립니다.
 * <p>
 * 한 건마다 고유 id(uid)를 붙여 보내기 때문에, 같은 것을 여러 번 올려도
 * 서버에서 중복되지 않습니다. 실패하면 그대로 두었다가 다음에 다시 시도합니다.
 */
public final class SyncClient {

    private static final String TAG = "SyncClient";
    /** 경로점 때문에 한 번에 보내는 양이 커서 조금씩 나눠 올립니다. */
    private static final int TRIPS_PER_BATCH = 4;
    private static final int CHARGES_PER_BATCH = 50;
    private static final int MAX_BATCHES = 25;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private static final AtomicBoolean running = new AtomicBoolean(false);

    private SyncClient() {
    }

    public static class Result {
        public boolean ok;
        public int trips;
        public int charges;
        public String message = "";
    }

    public static boolean isRunning() {
        return running.get();
    }

    /** 백그라운드에서 업로드하고 결과를 콜백으로 알려 줍니다. */
    public static void syncInBackground(final Context ctx, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Result r = sync(ctx);
                if (cb != null) cb.onDone(r);
            }
        }, "evlog-sync").start();
    }

    public interface Callback {
        void onDone(Result result);
    }

    /** 실제 업로드. 백그라운드 스레드에서 부르세요. */
    public static Result sync(Context ctx) {
        Result result = new Result();
        if (!Prefs.serverConfigured(ctx)) {
            result.message = "서버 주소와 토큰을 먼저 설정하세요.";
            return result;
        }
        if (!running.compareAndSet(false, true)) {
            result.message = "이미 업로드 중입니다.";
            return result;
        }
        try {
            Db db = Db.get(ctx);
            String base = Prefs.serverUrl(ctx);
            String token = Prefs.serverToken(ctx);
            String device = Build.MANUFACTURER + " " + Build.MODEL;

            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                List<Trip> trips = db.unsyncedTrips(TRIPS_PER_BATCH);
                List<Charge> charges = db.unsyncedCharges(CHARGES_PER_BATCH);
                if (trips.isEmpty() && charges.isEmpty()) {
                    result.ok = true;
                    break;
                }

                JSONObject payload = new JSONObject();
                payload.put("device", device);
                payload.put("trips", tripsJson(db, trips));
                payload.put("charges", chargesJson(charges));

                String error = post(base + "/api/v1/sync", token, payload.toString());
                if (error != null) {
                    result.message = error;
                    Prefs.setLastSync(ctx, System.currentTimeMillis(), "실패: " + error);
                    return result;
                }

                List<Long> tripIds = new ArrayList<>();
                for (Trip t : trips) tripIds.add(t.id);
                List<Long> chargeIds = new ArrayList<>();
                for (Charge c : charges) chargeIds.add(c.id);
                db.markTripsSynced(tripIds);
                db.markChargesSynced(chargeIds);
                result.trips += trips.size();
                result.charges += charges.size();
                result.ok = true;
            }

            int left = db.pendingCount();
            result.message = String.format(Locale.KOREA, "주행 %d건 · 충전 %d건 올림%s",
                    result.trips, result.charges, left > 0 ? " (남은 " + left + "건)" : "");
            Prefs.setLastSync(ctx, System.currentTimeMillis(), result.message);
            LiveState.notifyChanged();
            return result;
        } catch (Throwable t) {
            Log.w(TAG, "업로드 실패", t);
            result.ok = false;
            result.message = shortError(t);
            Prefs.setLastSync(ctx, System.currentTimeMillis(), "실패: " + result.message);
            return result;
        } finally {
            running.set(false);
        }
    }

    /** 서버에 닿는지만 확인합니다. 문제가 있으면 사람이 읽을 수 있는 사유를 돌려줍니다. */
    public static String testConnection(Context ctx) {
        if (!Prefs.serverConfigured(ctx)) return "서버 주소와 토큰을 먼저 입력하세요.";
        try {
            JSONObject empty = new JSONObject();
            empty.put("device", Build.MODEL);
            empty.put("trips", new JSONArray());
            empty.put("charges", new JSONArray());
            String err = post(Prefs.serverUrl(ctx) + "/api/v1/sync",
                    Prefs.serverToken(ctx), empty.toString());
            return err;   // null 이면 성공
        } catch (Throwable t) {
            return shortError(t);
        }
    }

    // ---------------------------------------------------------------- 내부

    private static JSONArray tripsJson(Db db, List<Trip> trips) throws Exception {
        JSONArray arr = new JSONArray();
        for (Trip t : trips) {
            JSONObject o = new JSONObject();
            o.put("uid", t.uid);
            o.put("start_ts", t.startTs);
            o.put("end_ts", t.endTs);
            o.put("distance_m", t.distanceM);
            o.put("moving_s", t.movingS);
            o.put("total_s", t.totalS);
            o.put("avg_kmh", round(t.avgKmh));
            o.put("max_kmh", round(t.maxKmh));
            o.put("start_soc", round(t.startSoc));
            o.put("end_soc", round(t.endSoc));
            o.put("used_wh", round(t.usedWh));
            o.put("start_lat", t.startLat);
            o.put("start_lon", t.startLon);
            o.put("end_lat", t.endLat);
            o.put("end_lon", t.endLon);
            o.put("source", t.source);
            o.put("note", t.note == null ? "" : t.note);

            JSONArray pts = new JSONArray();
            for (TripPoint p : db.pointsFor(t.id)) {
                JSONArray one = new JSONArray();
                one.put(p.ts);
                one.put(round6(p.lat));
                one.put(round6(p.lon));
                one.put(round(p.speedKmh));
                one.put(round(p.soc));
                pts.put(one);
            }
            o.put("points", pts);
            arr.put(o);
        }
        return arr;
    }

    private static JSONArray chargesJson(List<Charge> charges) throws Exception {
        JSONArray arr = new JSONArray();
        for (Charge c : charges) {
            JSONObject o = new JSONObject();
            o.put("uid", c.uid);
            o.put("start_ts", c.startTs);
            o.put("end_ts", c.endTs);
            o.put("start_soc", round(c.startSoc));
            o.put("end_soc", round(c.endSoc));
            o.put("added_wh", round(c.addedWh));
            o.put("kind", c.kind);
            o.put("max_kw", round(c.maxKw));
            o.put("cost", round(c.cost));
            o.put("lat", c.lat);
            o.put("lon", c.lon);
            o.put("manual", c.manual);
            o.put("note", c.note == null ? "" : c.note);
            arr.put(o);
        }
        return arr;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1000000.0) / 1000000.0;
    }

    /** 보내고 성공하면 null, 실패하면 사유 문자열. */
    private static String post(String url, String token, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.setRequestProperty("Accept", "application/json");

            byte[] raw = body.getBytes("UTF-8");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            GZIPOutputStream gz = new GZIPOutputStream(buf);
            gz.write(raw);
            gz.close();
            byte[] gzipped = buf.toByteArray();

            conn.setFixedLengthStreamingMode(gzipped.length);
            OutputStream os = conn.getOutputStream();
            os.write(gzipped);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code == 200) return null;
            if (code == 401) return "토큰이 맞지 않습니다 (401)";
            if (code == 404) return "서버 주소가 맞지 않습니다 (404)";
            String detail = readAll(conn.getErrorStream());
            return "서버 오류 " + code + (detail.isEmpty() ? "" : ": " + detail);
        } catch (Throwable t) {
            return shortError(t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0 && out.size() < 2048) out.write(b, 0, n);
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String shortError(Throwable t) {
        String name = t.getClass().getSimpleName();
        String msg = t.getMessage();
        if (name.contains("UnknownHost")) return "서버 주소를 찾을 수 없습니다";
        if (name.contains("Timeout") || name.contains("SocketTimeout")) return "서버 응답이 없습니다 (시간 초과)";
        if (name.contains("SSL") || name.contains("Certificate")) return "인증서 문제: " + msg;
        if (name.contains("Cleartext")) return "http 주소는 막혀 있습니다. https 를 쓰세요";
        return name + (msg != null ? ": " + msg : "");
    }
}
