package com.example.floatingball;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Appends one CSV row per resident per beat: time, standby bucket,
 * foreground flag, latest lifecycle event since last beat, TCP probe result.
 * Lives in the app-specific external dir (no permission needed). KeepBall's
 * own freeze/death shows up as gaps in this file — gaps are data too.
 */
final class HeartbeatRecorder {

    private final File mFile;
    private final SimpleDateFormat mFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private long mLastBeat = System.currentTimeMillis();

    HeartbeatRecorder(Context c) {
        File dir = c.getExternalFilesDir(null);
        mFile = new File(dir, "heartbeat.csv");
    }

    File file() {
        return mFile;
    }

    synchronized void beat(UsageStatsManager usm, Set<String> pkgs,
                           Map<String, String> probeResults) {
        long now = System.currentTimeMillis();
        StringBuilder rows = new StringBuilder();

        for (String pkg : pkgs) {
            String lastEvent = "-";
            boolean fg = false;
            try {
                UsageEvents ev = usm.queryEvents(mLastBeat, now);
                UsageEvents.Event e = new UsageEvents.Event();
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e);
                    if (pkg.equals(e.getPackageName())) {
                        lastEvent = eventName(e.getEventType());
                        if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                            fg = true;
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            String bucket = "n/a";
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    bucket = String.valueOf(usm.getAppStandbyBucket(pkg));
                } catch (Exception ignored) {
                }
            }

            String probe = probeResults.get(pkg);
            rows.append(mFmt.format(new Date(now))).append(',')
                    .append(pkg).append(',')
                    .append(bucket).append(',')
                    .append(fg ? "fg" : "bg").append(',')
                    .append(lastEvent).append(',')
                    .append(probe == null ? "off" : probe)
                    .append('\n');
        }
        mLastBeat = now;

        if (rows.length() == 0) return;
        try (FileWriter w = new FileWriter(mFile, true)) {
            if (mFile.length() == 0) {
                w.append("time,pkg,bucket,fg,last_event,probe\n");
            }
            w.append(rows);
        } catch (IOException ignored) {
        }
    }

    private static String eventName(int t) {
        if (t == UsageEvents.Event.ACTIVITY_RESUMED) return "RESUMED";
        if (t == UsageEvents.Event.ACTIVITY_PAUSED) return "PAUSED";
        if (t == UsageEvents.Event.ACTIVITY_STOPPED) return "STOPPED";
        if (t == UsageEvents.Event.ACTIVITY_DESTROYED) return "DESTROYED";
        if (t == UsageEvents.Event.FOREGROUND_SERVICE_START) return "FGS_START";
        if (t == UsageEvents.Event.FOREGROUND_SERVICE_STOP) return "FGS_STOP";
        if (t == UsageEvents.Event.APP_STANDBY_BUCKET_CHANGED) return "BUCKET";
        return "e" + t;
    }
}
