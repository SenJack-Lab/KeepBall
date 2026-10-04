package com.example.floatingball;

import android.app.ListActivity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Simple list of launchable apps; returns the chosen package name. */
public class AppPickerActivity extends ListActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PackageManager pm = getPackageManager();
        List<Entry> entries = new ArrayList<>();
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            if (getPackageName().equals(pi.packageName)) continue;
            if (pm.getLaunchIntentForPackage(pi.packageName) == null) continue;
            String label;
            try {
                label = String.valueOf(pi.applicationInfo.loadLabel(pm));
            } catch (Exception e) {
                label = pi.packageName;
            }
            entries.add(new Entry(pi.packageName, label));
        }
        Collections.sort(entries);

        setListAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, entries));

        ListView lv = getListView();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                Entry e = (Entry) parent.getItemAtPosition(position);
                setResult(RESULT_OK, new Intent().putExtra("pkg", e.pkg));
                finish();
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
