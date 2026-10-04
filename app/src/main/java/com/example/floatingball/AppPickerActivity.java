package com.example.floatingball;

import android.app.ListActivity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-select list of launchable apps. Checking/unchecking writes straight
 * into BallPrefs; back gesture/button exits when done.
 */
public class AppPickerActivity extends ListActivity {

    private BallPrefs mPrefs;
    private List<Entry> mEntries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = new BallPrefs(this);

        PackageManager pm = getPackageManager();
        mEntries = new ArrayList<>();
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            if (getPackageName().equals(pi.packageName)) continue;
            if (pm.getLaunchIntentForPackage(pi.packageName) == null) continue;
            String label;
            try {
                label = String.valueOf(pi.applicationInfo.loadLabel(pm));
            } catch (Exception e) {
                label = pi.packageName;
            }
            mEntries.add(new Entry(pi.packageName, label));
        }
        Collections.sort(mEntries);

        setListAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, mEntries));

        ListView lv = getListView();
        lv.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        for (int i = 0; i < mEntries.size(); i++) {
            if (mPrefs.residents().contains(mEntries.get(i).pkg)) {
                lv.setItemChecked(i, true);
            }
        }

        lv.setOnItemClickListener((parent, view, position, id) -> {
            Entry e = mEntries.get(position);
            if (((ListView) parent).isItemChecked(position)) {
                mPrefs.addResident(e.pkg);
            } else {
                mPrefs.removeResident(e.pkg);
            }
        });
    }

    private static final class Entry implements Comparable<Entry> {
        final String pkg;
        final String label;

        Entry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public int compareTo(Entry o) {
            return label.compareToIgnoreCase(o.label);
        }
    }
}
