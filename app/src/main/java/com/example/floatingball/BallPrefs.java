package com.example.floatingball;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/** Stores the set of resident (kept-alive) target packages. */
final class BallPrefs {

    // Stored as a comma-joined string: SharedPreferences StringSet has the
    // well-known pitfall that in-place mutations silently don't persist.
    private static final String KEY = "pkgs";

    private final SharedPreferences mSp;

    BallPrefs(Context context) {
        mSp = context.getApplicationContext()
                .getSharedPreferences("ball", Context.MODE_PRIVATE);
    }

    Set<String> residents() {
        Set<String> out = new LinkedHashSet<>();
        String raw = mSp.getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String p : raw.split(",")) {
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    void addResident(String pkg) {
        Set<String> set = residents();
        if (set.add(pkg)) persist(set);
    }

    void removeResident(String pkg) {
        Set<String> set = residents();
        if (set.remove(pkg)) persist(set);
    }

    boolean hasResidents() {
        return !residents().isEmpty();
    }

    private void persist(Set<String> set) {
        mSp.edit().putString(KEY, String.join(",", set)).apply();
    }

    /** Usage access lets the ball know whether the resident app is foreground. */
    static boolean hasUsageAccess(Context context) {
        AppOpsManager ao = context.getSystemService(AppOpsManager.class);
        int mode = ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }
}
