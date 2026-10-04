package com.example.floatingball;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the resident (kept-alive) target package. */
final class BallPrefs {

    private final SharedPreferences mSp;

    BallPrefs(Context context) {
        mSp = context.getApplicationContext()
                .getSharedPreferences("ball", Context.MODE_PRIVATE);
    }

    void setResident(String pkg) {
        mSp.edit().putString("pkg", pkg).apply();
    }

    String pkg() {
        return mSp.getString("pkg", null);
    }

    /** Usage access lets the ball know whether the resident app is foreground. */
    static boolean hasUsageAccess(Context context) {
        AppOpsManager ao = context.getSystemService(AppOpsManager.class);
        int mode = ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }
}
