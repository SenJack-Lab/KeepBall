package com.example.floatingball;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/** Foreground service that owns the floating ball overlay. */
public class FloatBallService extends Service {

    private static final String CHANNEL_ID = "keepball";
    private static final int NOTIF_ID = 1;
    private static final int BALL_SIZE_PX = 132; // ~44dp @3x, adjusted on attach

    private WindowManager mWm;
    private View mBall;
    private float mTouchSlop;
    private UsageStatsManager mUsage;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mUsage = getSystemService(UsageStatsManager.class);
        mTouchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        startForeground(NOTIF_ID, buildNotification());
        showBall();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if ("stop".equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("minimize".equals(action)) {
            goHome();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeBall();
        super.onDestroy();
    }

    private void showBall() {
        if (mBall != null
                || !Settings.canDrawOverlays(this)) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(44 * density);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.x = 0;
        lp.y = 0;

        View ball = new View(this);
        ball.setBackgroundResource(R.drawable.ic_launcher);
        ball.setOnTouchListener(new BallTouchHandler(lp));

        mWm.addView(ball, lp);
        mBall = ball;
    }

    private void removeBall() {
        if (mBall != null) {
            try {
                mWm.removeView(mBall);
            } catch (IllegalArgumentException ignored) {
                // view already detached
            }
            mBall = null;
        }
    }

    /**
     * Tap on the ball: if the resident app is in the foreground, minimize it
     * to the home screen; otherwise restore it. Without usage access this
     * always restores (v1.0 behavior).
     */
    private void restoreResident() {
        String pkg = new BallPrefs(this).pkg();
        if (pkg == null) return;

        if (BallPrefs.hasUsageAccess(this) && isResidentForeground(pkg)) {
            goHome();
            return;
        }

        PackageManager pm = getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        // Start through a transparent activity: a background service cannot
        // start activities on modern Android, but an activity created by a
        // user touch may, and SYSTEM_ALERT_WINDOW grants the exemption.
        Intent via = new Intent(this, RestoreActivity.class);
        via.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        via.putExtra("intent", launch);
        startActivity(via);
    }

    /** True if the resident package owned the last activity-resumed event. */
    private boolean isResidentForeground(String pkg) {
        long now = System.currentTimeMillis();
        UsageEvents events;
        try {
            events = mUsage.queryEvents(now - 30_000L, now);
        } catch (Exception e) {
            return false;
        }
        String last = null;
        UsageEvents.Event ev = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(ev);
            if (ev.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                last = ev.getPackageName();
            }
        }
        return pkg.equals(last);
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_home_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);

        Intent stop = new Intent(this, FloatBallService.class);
        stop.setAction("stop");
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent minimize = new Intent(this, FloatBallService.class);
        minimize.setAction("minimize");
        PendingIntent minPi = PendingIntent.getService(this, 3, minimize,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ball)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.notif_action_minimize), minPi).build())
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.notif_action_stop), stopPi).build())
                .setOngoing(true)
                .build();
    }

    /** Drag (with touch-slop) + tap detection on the ball. */
    private final class BallTouchHandler implements View.OnTouchListener {
        private final WindowManager.LayoutParams mLp;
        private float mDownRawX, mDownRawY;
        private float mStartX, mStartY;
        private boolean mDragging;

        BallTouchHandler(WindowManager.LayoutParams lp) {
            mLp = lp;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownRawX = event.getRawX();
                    mDownRawY = event.getRawY();
                    mStartX = mLp.x;
                    mStartY = mLp.y;
                    mDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - mDownRawX;
                    float dy = event.getRawY() - mDownRawY;
                    if (!mDragging
                            && Math.hypot(dx, dy) > mTouchSlop) {
                        mDragging = true;
                    }
                    if (mDragging) {
                        // gravity END: x grows leftwards from the right edge
                        mLp.x = (int) (mStartX - dx);
                        mLp.y = (int) (mStartY + dy);
                        try {
                            mWm.updateViewLayout(mBall, mLp);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!mDragging) {
                        restoreResident();
                    }
                    mDragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
