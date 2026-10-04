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

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_NOTIF_PERM = 2;

    private TextView mStatus;
    private BallPrefs mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mPrefs = new BallPrefs(this);

        mStatus = findViewById(R.id.status);
        Button btnOverlay = findViewById(R.id.btn_overlay);
        Button btnPick = findViewById(R.id.btn_pick);
        Button btnBattery = findViewById(R.id.btn_battery);
        Button btnMinimize = findViewById(R.id.btn_minimize);
        Button btnStop = findViewById(R.id.btn_stop);

        btnOverlay.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));

        btnPick.setOnClickListener(v ->
                startActivityForResult(new Intent(this, AppPickerActivity.class), REQ_PICK));

        btnBattery.setOnClickListener(v -> requestBatteryExemption());

        btnMinimize.setOnClickListener(v -> {
            if (mPrefs.pkg() == null) {
                Toast.makeText(this, R.string.toast_pick_first, Toast.LENGTH_SHORT).show();
                return;
            }
            startService(new Intent(this, FloatBallService.class));
            minimizeToBall();
        });

        btnStop.setOnClickListener(v -> {
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
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            String pkg = data.getStringExtra("pkg");
            mPrefs.setResident(pkg);
            startService(new Intent(this, FloatBallService.class));

            PackageManager pm = getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(launch);
            }
            refreshStatus();
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

    private void refreshStatus() {
        String pkg = mPrefs.pkg();
        String label = pkg;
        if (pkg != null) {
            PackageManager pm = getPackageManager();
            try {
                label = String.valueOf(pm.getApplicationLabel(
                        pm.getApplicationInfo(pkg, 0))) + " (" + pkg + ")";
            } catch (Exception ignored) {
            }
        }
        boolean running = Settings.canDrawOverlays(this);
        String text;
        if (!running) {
            text = getString(R.string.status_need_overlay);
        } else if (pkg == null) {
            text = getString(R.string.status_running_empty);
        } else {
            text = getString(R.string.status_running, label);
        }
        mStatus.setText(text);
    }
}
