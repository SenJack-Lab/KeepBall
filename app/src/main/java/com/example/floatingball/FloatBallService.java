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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Foreground service owning one floating ball per resident app. Each ball
 * shows the app icon with a status badge (green play / red pause) driven by
 * a heartbeat loop: usage events + optional local TCP probe, recorded to a
 * CSV log. Long-press a ball to configure its probe port.
 */
public class FloatBallService extends Service {

    private static final String CHANNEL_ID = "keepball";
    private static final int NOTIF_ID = 1;
    private static final int BALL_DP = 40;
    private static final int BADGE_DP = 17;
    private static final int GAP_DP = 8;
    private static final long BEAT_MS = 60_000L;
    private static final long PROBE_TIMEOUT_MS = 2_000L;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Map<String, View> mBalls = new LinkedHashMap<>();   // pkg -> ball (insertion = slot order)
    private final Map<String, WindowManager.LayoutParams> mLps = new LinkedHashMap<>();
    private final Map<String, Integer> mStates = new HashMap<>();     // pkg -> StatusBadge.*

    private WindowManager mWm;
    private PackageManager mPm;
    private UsageStatsManager mUsage;
    private HeartbeatRecorder mRecorder;
    private float mTouchSlop;
    private int mBallSize, mBadgeSize, mBallGap;
    private boolean mBeating;

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
        float d = getResources().getDisplayMetrics().density;
        mBallSize = Math.round(BALL_DP * d);
        mBadgeSize = Math.round(BADGE_DP * d);
        mBallGap = Math.round(GAP_DP * d);
        startForeground(NOTIF_ID, buildNotification());
        reconcileBalls();
        startHeartbeat();
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
        // Permissions/residents/probe ports may have changed; idempotent.
        reconcileBalls();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mMain.removeCallbacksAndMessages(null);
        for (View v : new LinkedHashMap<>(mBalls).values()) removeBallView(v);
        mBalls.clear();
        mLps.clear();
        super.onDestroy();
    }

    // ---------- balls ----------

    /** Make the on-screen balls match the resident set and slot order. */
    private void reconcileBalls() {
        if (!Settings.canDrawOverlays(this)) return;

        Set<String> residents = new BallPrefs(this).residents();

        for (String pkg : new LinkedHashMap<>(mBalls).keySet()) {
            if (!residents.contains(pkg)) {
                removeBallView(mBalls.remove(pkg));
                mLps.remove(pkg);
                mStates.remove(pkg);
            }
        }

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
    }

    private View createBall(String pkg) {
        android.graphics.drawable.Drawable icon;
        try {
            icon = mPm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        FrameLayout ball = new FrameLayout(this);
        ball.setBackgroundResource(R.drawable.ic_launcher);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = mBallSize / 6;
        iv.setPadding(pad, pad, pad, pad);
        ball.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        StatusBadge badge = new StatusBadge(this);
        badge.setState(StatusBadge.UNKNOWN);
        badge.setTag("badge");
        ball.addView(badge, new FrameLayout.LayoutParams(mBadgeSize, mBadgeSize,
                Gravity.CENTER));

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
            }
        }
    }

    // ---------- interactions ----------

    /**
     * Tap on a ball: if that app is in the foreground, minimize it to the
     * home screen; otherwise restore it. Without usage access: restore.
     */
    private void onBallTapped(String pkg) {
        if (BallPrefs.hasUsageAccess(this) && isResidentForeground(pkg)) {
            goHome();
            return;
        }

        Intent launch = mPm.getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        // Transparent trampoline: an activity born from a user touch (in an
        // overlay-holding process) may start activities from the background.
        Intent via = new Intent(this, RestoreActivity.class);
        via.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        via.putExtra("intent", launch);
        startActivity(via);
    }

    private void onBallLongPressed(String pkg) {
        Intent cfg = new Intent(this, BallConfigActivity.class);
        cfg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        cfg.putExtra("pkg", pkg);
        startActivity(cfg);
    }

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

    // ---------- heartbeat ----------

    private void startHeartbeat() {
        if (mBeating) return;
        mBeating = true;
        mMain.postDelayed(this::beat, BEAT_MS);
    }

    private void beat() {
        Set<String> residents = new BallPrefs(this).residents();
        Map<String, Integer> ports = new HashMap<>();
        for (String pkg : residents) {
            Integer p = new BallPrefs(this).probePort(pkg);
            if (p != null) ports.put(pkg, p);
        }

        // Probes block on connect(): run off the main thread.
        new Thread(() -> {
            Map<String, String> results = new HashMap<>();
            Map<String, Integer> states = new HashMap<>();
            for (String pkg : residents) {
                Integer port = ports.get(pkg);
                if (port == null) continue;
                boolean ok = tcpProbe(port);
                results.put(pkg, ok ? "ok" : "fail");
                states.put(pkg, ok ? StatusBadge.ALIVE : StatusBadge.DEAD);
            }
            try {
                if (mRecorder == null) mRecorder = new HeartbeatRecorder(this);
                mRecorder.beat(mUsage, residents, results);
            } catch (Exception ignored) {
            }
            mMain.post(() -> {
                mStates.putAll(states);
                applyBadges();
            });
        }).start();

        mMain.postDelayed(this::beat, BEAT_MS);
    }

    private void applyBadges() {
        for (Map.Entry<String, View> e : mBalls.entrySet()) {
            StatusBadge badge = e.getValue().findViewWithTag("badge");
            if (badge == null) continue;
            Integer s = mStates.get(e.getKey());
            if (s == null) {
                // no probe configured: infer from usage events
                boolean fg = BallPrefs.hasUsageAccess(this)
                        && isResidentForeground(e.getKey());
                badge.setState(fg ? StatusBadge.ALIVE : StatusBadge.UNKNOWN);
            } else {
                badge.setState(s);
            }
        }
    }

    private static boolean tcpProbe(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), (int) PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- notification ----------

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

    /** Drag (touch-slop) + tap + long-press detection, bound to one ball. */
    private final class BallTouchHandler implements View.OnTouchListener {
        private final String mPkg;
        private final WindowManager.LayoutParams mLp;
        private float mDownRawX, mDownRawY;
        private float mStartX, mStartY;
        private boolean mDragging;
        private boolean mLongFired;
        private final Runnable mLongPress = () -> {
            mLongFired = true;
            onBallLongPressed(mPkg);
        };

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
                    mLongFired = false;
                    mMain.postDelayed(mLongPress, 500L);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - mDownRawX;
                    float dy = event.getRawY() - mDownRawY;
                    if (!mDragging && Math.hypot(dx, dy) > mTouchSlop) {
                        mDragging = true;
                        mMain.removeCallbacks(mLongPress);
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
                    mMain.removeCallbacks(mLongPress);
                    if (!mDragging && !mLongFired) {
                        onBallTapped(mPkg);
                    }
                    mDragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mMain.removeCallbacks(mLongPress);
                    mDragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
