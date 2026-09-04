package com.jhkim.evlog;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class App extends Application {

    public static final String CHANNEL_LOGGING = "evlog_logging";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_LOGGING) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_LOGGING,
                        getString(R.string.channel_logging),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(getString(R.string.channel_logging_desc));
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }
}
