package com.example.floatingball;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Transparent dialog activity (launched by long-pressing a ball): sets an
 * optional local TCP probe port for that resident app. With a probe set,
 * the ball badge reflects REAL liveness instead of usage-event inference.
 */
public class BallConfigActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) {
            finish();
            return;
        }
        BallPrefs prefs = new BallPrefs(this);

        String label;
        try {
            label = String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            label = pkg;
        }

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("8080");
        Integer current = prefs.probePort(pkg);
        if (current != null) input.setText(String.valueOf(current));

        float d = getResources().getDisplayMetrics().density;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.probe_title, label))
                .setMessage(R.string.probe_hint)
                .setView(input, (int) (24 * d), 0, (int) (24 * d), 0)
                .setPositiveButton(R.string.probe_save, (d1, w) -> {
                    String t = input.getText().toString().trim();
                    try {
                        Integer port = t.isEmpty() ? null : Integer.valueOf(t);
                        prefs.setProbePort(pkg, port);
                        // nudge the service so the next beat applies immediately
                        startService(new Intent(this, FloatBallService.class));
                    } catch (NumberFormatException nfe) {
                        Toast.makeText(this, R.string.toast_bad_port,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d2 -> finish())
                .show();
    }
}
