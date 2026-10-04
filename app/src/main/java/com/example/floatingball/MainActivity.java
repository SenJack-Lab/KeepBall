package com.example.floatingball;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_NOTIF_PERM = 2;

    private TextView mStatus;
    private TextView mPermOverlay, mPermUsage, mPermNotif, mPermBattery;
    private BallPrefs mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mPrefs = new BallPrefs(this);

        mStatus = findViewById(R.id.status);
        mPermOverlay = findViewById(R.id.perm_overlay);
        mPermUsage = findViewById(R.id.perm_usage);
        mPermNotif = findViewById(R.id.perm_notif);
        mPermBattery = findViewById(R.id.perm_battery);

        mPermOverlay.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                toastSettingsUnavailable();
            }
        });

        mPermUsage.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                toastSettingsUnavailable();
            }
        });

        mPermNotif.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF_PERM);
            }
        });

        mPermBattery.setOnClickListener(v -> requestBatteryExemption());

        findViewById(R.id.btn_pick).setOnClickListener(v ->
                startActivity(new Intent(this, AppPickerActivity.class)));

        findViewById(R.id.btn_minimize).setOnClickListener(v -> {
            if (!mPrefs.hasResidents()) {
                Toast.makeText(this, R.string.toast_pick_first, Toast.LENGTH_SHORT).show();
                return;
            }
            startService(new Intent(this, FloatBallService.class));
            minimizeToBall();
        });

        findViewById(R.id.btn_export).setOnClickListener(v -> exportHeartbeat());

        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            stopService(new Intent(this, FloatBallService.class));
            refreshStatus();
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIF_PERM);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        // Returning from system settings (overlay permission just granted)
        // or from the picker (resident set changed): nudge the service so it
        // redraws/repositions the balls.
        if (mPrefs.hasResidents()) {
            startService(new Intent(this, FloatBallService.class));
        }
    }

    /** Send the launcher home; the ball overlay stays on top of the wallpaper. */
    private void minimizeToBall() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
            Toast.makeText(this, R.string.toast_minimized, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_home_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void requestBatteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())
                && i.resolveActivity(getPackageManager()) != null) {
            startActivity(i);
        } else {
            Toast.makeText(this, R.string.toast_battery_unsupported, Toast.LENGTH_SHORT).show();
        }
    }

    private void exportHeartbeat() {
        java.io.File f = new HeartbeatRecorder(this).file();
        if (!f.exists() || f.length() == 0) {
            Toast.makeText(this, R.string.toast_no_log, Toast.LENGTH_SHORT).show();
            return;
        }
        String csv;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            csv = bos.toString("UTF-8");
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_no_log, Toast.LENGTH_SHORT).show();
            return;
        }
        if (csv.length() > 900_000) csv = csv.substring(csv.length() - 900_000);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "keepball-heartbeat.csv");
        send.putExtra(Intent.EXTRA_TEXT, csv);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.btn_export)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_settings_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private String checkmark(boolean granted) {
        return granted ? "✓ " : "✗ ";
    }

    private void toastSettingsUnavailable() {
        Toast.makeText(this, R.string.toast_settings_unavailable, Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean usage = BallPrefs.hasUsageAccess(this);
        boolean notif = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        boolean battery = getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName());

        mPermOverlay.setText(checkmark(overlay) + getString(R.string.perm_overlay));
        mPermUsage.setText(checkmark(usage) + getString(R.string.perm_usage));
        mPermNotif.setText(checkmark(notif) + getString(R.string.perm_notif));
        mPermBattery.setText(checkmark(battery) + getString(R.string.perm_battery));

        Set<String> residents = mPrefs.residents();
        String text;
        if (!overlay) {
            text = getString(R.string.status_need_overlay);
        } else if (residents.isEmpty()) {
            text = getString(R.string.status_running_empty);
        } else {
            StringBuilder names = new StringBuilder();
            PackageManager pm = getPackageManager();
            for (String pkg : residents) {
                if (names.length() > 0) names.append("、");
                try {
                    names.append(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                } catch (Exception e) {
                    names.append(pkg);
                }
            }
            text = getString(R.string.status_running_multi, residents.size(), names.toString());
        }
        mStatus.setText(text);
    }
}
