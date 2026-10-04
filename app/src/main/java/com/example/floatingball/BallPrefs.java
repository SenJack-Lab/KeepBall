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
}
