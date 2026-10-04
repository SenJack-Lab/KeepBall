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
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Foreground service that owns one floating ball per resident app. */
public class FloatBallService extends Service {

    private static final String CHANNEL_ID = "keepball";
    private static final int NOTIF_ID = 1;
    private static final int BALL_DP = 40;
    private static final int GAP_DP = 8;

    private WindowManager mWm;
    private PackageManager mPm;
    private UsageStatsManager mUsage;
    private float mTouchSlop;
    private int mBallSize, mBallGap;

    /** package name -> attached ball view; insertion order = slot order. */
    private final Map<String, View> mBalls = new LinkedHashMap<>();
    private final Map<String, WindowManager.LayoutParams> mLps = new LinkedHashMap<>();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mPm = getPackageManager();
        mUsage = getSystemService(UsageStatsManager.class);
        mTouchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        float density = getResources().getDisplayMetrics().density;
        mBallSize = Math.round(BALL_DP * density);
        mBallGap = Math.round(GAP_DP * density);
        startForeground(NOTIF_ID, buildNotification());
        reconcileBalls();
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
        // Permissions or the resident set may have changed since onCreate;
        // reconcile is idempotent, so retry on every start.
        reconcileBalls();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        for (View v : new LinkedHashMap<>(mBalls).values()) removeBallView(v);
        mBalls.clear();
        mLps.clear();
        super.onDestroy();
    }

    /** Make the on-screen balls match the resident set and slot order. */
    private void reconcileBalls() {
        if (!Settings.canDrawOverlays(this)) return;

        Set<String> residents = new BallPrefs(this).residents();

        // remove balls whose app was unchecked
        for (String pkg : new LinkedHashMap<>(mBalls).keySet()) {
            if (!residents.contains(pkg)) {
                removeBallView(mBalls.remove(pkg));
                mLps.remove(pkg);
            }
        }

        // add balls for new residents, then reposition every ball to its slot
        int slot = 0;
        for (String pkg : residents) {
            View ball = mBalls.get(pkg);
            if (ball == null) {
                ball = createBall(pkg);
                if (ball == null) continue; // package vanished
                mBalls.put(pkg, ball);
            }
            WindowManager.LayoutParams lp = mLps.get(pkg);
            lp.y = slot * (mBallSize + mBallGap);
            try {
                mWm.updateViewLayout(ball, lp);
            } catch (IllegalArgumentException ignored) {
            }
            slot++;
        }

        if (mBalls.isEmpty()) {
            // nothing to guard: keep the service (notification) but no balls
        }
    }

    private View createBall(String pkg) {
        android.graphics.drawable.Drawable icon;
        try {
            icon = mPm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        ImageView ball = new ImageView(this);
        ball.setImageDrawable(icon);
        ball.setPadding(mBallSize / 6, mBallSize / 6, mBallSize / 6, mBallSize / 6);
        ball.setBackgroundResource(R.drawable.ic_launcher);
        ball.setScaleType(ImageView.ScaleType.FIT_CENTER);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                mBallSize,
                mBallSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.x = 0;
        lp.y = 0;

        ball.setOnTouchListener(new BallTouchHandler(pkg, lp));
        try {
            mWm.addView(ball, lp);
        } catch (Exception e) {
            return null;
        }
        mLps.put(pkg, lp);
        return ball;
    }

    private void removeBallView(View ball) {
        if (ball != null) {
            try {
                mWm.removeView(ball);
            } catch (IllegalArgumentException ignored) {
                // view already detached
            }
        }
    }

    /**
     * Tap on a ball: if that app is in the foreground, minimize it to the
     * home screen; otherwise restore it. Without usage access this always
     * restores (v1.0 behavior).
     */
    private void onBallTapped(String pkg) {
        if (BallPrefs.hasUsageAccess(this) && isResidentForeground(pkg)) {
            goHome();
            return;
        }

        Intent launch = mPm.getLaunchIntentForPackage(pkg);
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

    /** True if the package owned the last activity-resumed event. */
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

    /** Drag (with touch-slop) + tap detection, bound to one ball/package. */
    private final class BallTouchHandler implements View.OnTouchListener {
        private final String mPkg;
        private final WindowManager.LayoutParams mLp;
        private float mDownRawX, mDownRawY;
        private float mStartX, mStartY;
        private boolean mDragging;

        BallTouchHandler(String pkg, WindowManager.LayoutParams lp) {
            mPkg = pkg;
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
                    if (!mDragging && Math.hypot(dx, dy) > mTouchSlop) {
                        mDragging = true;
                    }
                    if (mDragging) {
                        mLp.x = (int) (mStartX - dx);
                        mLp.y = (int) (mStartY + dy);
                        try {
                            mWm.updateViewLayout(v, mLp);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!mDragging) {
                        onBallTapped(mPkg);
                    }
                    mDragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
