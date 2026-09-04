package com.jhkim.evlog.logger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.jhkim.evlog.Prefs;
import com.jhkim.evlog.vehicle.GpsSource;

/**
 * 차량/기기 부팅 후 기록을 이어서 시작합니다.
 * 안드로이드 14부터는 부팅 직후 위치형 포그라운드 서비스 시작이 막힐 수 있어,
 * 실패하면 조용히 넘어가고 사용자가 앱을 열 때 다시 시작됩니다.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!Prefs.autoStart(context) || !Prefs.loggingEnabled(context)) return;
        if (!GpsSource.hasPermission(context)) return;
        try {
            LoggerService.start(context);
        } catch (Throwable t) {
            Log.w("BootReceiver", "부팅 자동 시작 실패", t);
        }
    }
}
