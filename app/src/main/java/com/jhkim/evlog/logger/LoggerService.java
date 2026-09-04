package com.jhkim.evlog.logger;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.jhkim.evlog.App;
import com.jhkim.evlog.DriveActivity;
import com.jhkim.evlog.MainActivity;
import com.jhkim.evlog.Prefs;
import com.jhkim.evlog.R;
import com.jhkim.evlog.sync.SyncClient;
import com.jhkim.evlog.vehicle.GpsSource;
import com.jhkim.evlog.vehicle.SourceManager;
import com.jhkim.evlog.vehicle.VehicleSnapshot;

import java.util.Locale;

/** 백그라운드에서 1초마다 상태를 읽어 주행·충전을 자동으로 기록합니다. */
public class LoggerService extends Service {

    private static final String TAG = "LoggerService";
    private static final int NOTI_ID = 1001;
    private static final long TICK_MS = 1000L;
    private static final long NOTI_INTERVAL_MS = 5000L;

    public static final String ACTION_START = "com.jhkim.evlog.START";
    public static final String ACTION_STOP = "com.jhkim.evlog.STOP";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SourceManager sources;
    private TripRecorder tripRecorder;
    private ChargeRecorder chargeRecorder;
    private boolean running;
    private long lastNotiAt;
    /** 마지막 업로드 시각과, 그때의 기록 개수 */
    private long lastSyncAt;
    private int lastSyncRevision = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                step();
            } catch (Throwable t) {
                Log.e(TAG, "기록 루프 오류", t);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, LoggerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, LoggerService.class).setAction(ACTION_STOP));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        if (!running) {
            startForegroundSafely(buildNotification("기록을 시작하는 중", ""));
            sources = new SourceManager();
            sources.start(this);
            tripRecorder = new TripRecorder(this);
            chargeRecorder = new ChargeRecorder(this);
            running = true;
            LiveState.serviceRunning = true;
            LiveState.sourceLabel = sources.sourceLabel();
            Prefs.setLoggingEnabled(this, true);
            LiveState.notifyChanged();
            handler.post(tick);
        }
        return START_STICKY;
    }

    private void step() {
        // 끊긴 GPS·차량 연결을 되살리고, 현재 출처를 화면에 반영합니다.
        sources.tick();
        LiveState.sourceLabel = sources.sourceLabel();
        LiveState.carConnected = sources.carConnected();
        LiveState.carBatteryStatus = sources.carBatteryStatus();

        VehicleSnapshot s = sources.read();

        chargeRecorder.update(s);
        if (chargeRecorder.isActive()) {
            // 충전이 시작되면 진행 중이던 주행은 그 자리에서 마무리합니다.
            tripRecorder.finishIfActive();
        } else {
            tripRecorder.update(s);
        }

        LiveState.speedKmh = s.hasSpeed ? s.speedKmh : -1;
        LiveState.socPct = s.hasSoc ? s.socPct : -1;
        LiveState.rangeKm = s.hasRangeKm ? s.rangeKm : -1;
        LiveState.outsideTempC = s.hasOutsideTempC ? s.outsideTempC : -999;
        LiveState.tripActive = tripRecorder.isActive();
        LiveState.tripKm = tripRecorder.currentKm();
        LiveState.tripElapsedS = tripRecorder.elapsedS();
        LiveState.tripEfficiency = tripRecorder.currentEfficiency();
        LiveState.chargeActive = chargeRecorder.isActive();

        long now = System.currentTimeMillis();
        if (now - lastNotiAt >= NOTI_INTERVAL_MS) {
            lastNotiAt = now;
            updateNotification();
            LiveState.notifyChanged();
        }
        maybeSync(now);
    }

    /**
     * 새 기록이 저장됐거나 마지막 시도로부터 15분이 지났으면 서버로 올립니다.
     * 주행 중에는 올리지 않습니다 — 끝난 뒤에 한 번에 보내는 편이 낫습니다.
     */
    private void maybeSync(long now) {
        if (!Prefs.syncAuto(this) || !Prefs.serverConfigured(this)) return;
        if (LiveState.tripActive || SyncClient.isRunning()) return;

        boolean newData = LiveState.dataRevision != lastSyncRevision;
        boolean overdue = now - lastSyncAt >= 15 * 60_000L;
        if (!newData && !overdue) return;

        lastSyncRevision = LiveState.dataRevision;
        lastSyncAt = now;
        SyncClient.syncInBackground(this, null);
    }

    private void updateNotification() {
        String title;
        StringBuilder sub = new StringBuilder();

        if (LiveState.chargeActive) {
            title = String.format(Locale.KOREA, "충전 중 · %.1f kWh", LiveState.chargeKwh);
            if (LiveState.chargeKw > 0) {
                sub.append(String.format(Locale.KOREA, "%.1f kW", LiveState.chargeKw));
            }
        } else if (LiveState.tripActive) {
            title = String.format(Locale.KOREA, "주행 기록 중 · %.1f km", LiveState.tripKm);
            if (LiveState.speedKmh >= 0) {
                sub.append(String.format(Locale.KOREA, "%.0f km/h", LiveState.speedKmh));
            }
        } else {
            title = "대기 중";
            sub.append(LiveState.sourceLabel);
        }

        if (LiveState.socPct >= 0) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append(String.format(Locale.KOREA, "배터리 %.0f%%", LiveState.socPct));
        }

        try {
            androidx.core.app.NotificationManagerCompat.from(this)
                    .notify(NOTI_ID, buildNotification(title, sub.toString()));
        } catch (SecurityException ignored) {
            // 알림 권한 미허용
        }
    }

    private Notification buildNotification(String title, String text) {
        // 주행·충전 중에는 알림을 눌렀을 때 주행 중에도 볼 수 있는 모니터 화면으로 갑니다.
        Class<?> target = (LiveState.tripActive || LiveState.chargeActive)
                ? DriveActivity.class : MainActivity.class;
        Intent open = new Intent(this, target)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, LoggerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, App.CHANNEL_LOGGING)
                .setSmallIcon(R.drawable.ic_stat_ev)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, "기록 중지", stopPi)
                .build();
    }

    private void startForegroundSafely(Notification n) {
        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && GpsSource.hasPermission(this)) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        try {
            ServiceCompat.startForeground(this, NOTI_ID, n, type);
        } catch (Throwable t) {
            Log.w(TAG, "포그라운드 전환 실패, 타입 없이 재시도", t);
            try {
                ServiceCompat.startForeground(this, NOTI_ID, n, 0);
            } catch (Throwable t2) {
                Log.e(TAG, "포그라운드 전환 불가", t2);
            }
        }
    }

    private void shutdown() {
        running = false;
        handler.removeCallbacks(tick);
        if (tripRecorder != null) tripRecorder.finishIfActive();
        if (chargeRecorder != null) chargeRecorder.finishIfActive();
        if (sources != null) sources.stop();
        Prefs.setLoggingEnabled(this, false);
        LiveState.serviceRunning = false;
        LiveState.sourceLabel = "대기 중";
        LiveState.reset();
        LiveState.notifyChanged();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running) {
            running = false;
            handler.removeCallbacks(tick);
            if (tripRecorder != null) tripRecorder.finishIfActive();
            if (chargeRecorder != null) chargeRecorder.finishIfActive();
            if (sources != null) sources.stop();
            LiveState.serviceRunning = false;
            LiveState.reset();
            LiveState.notifyChanged();
        }
        super.onDestroy();
    }
}
