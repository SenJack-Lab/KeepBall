package com.example.floatingball;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Transparent trampoline: the ball tap lands here, and because this is an
 * activity created by a user touch (from an overlay-exempt process) it is
 * allowed to launch the resident app from the background.
 */
public class RestoreActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent target = getIntent().getParcelableExtra("intent");
        if (target != null) {
            try {
                startActivity(target);
            } catch (Exception e) {
                android.widget.Toast.makeText(this, R.string.toast_restore_failed,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }
}
