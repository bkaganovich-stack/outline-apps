// Copyright 2024 The Outline Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.outline.vpn;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity that displays a list of installed apps and lets the user select
 * which ones should bypass the VPN (split tunneling).
 *
 * All heavy work (package enumeration, icon loading) runs off the UI thread.
 */
public class SplitTunnelActivity extends Activity {

    private SplitTunnelManager splitTunnelManager;
    private Set<String> disallowedApps;
    private AppListAdapter adapter;
    private boolean showSystemApps = true;

    /** Single thread for loading the app list. */
    private final ExecutorService listExecutor = Executors.newSingleThreadExecutor();
    /** Thread pool for loading icons in parallel. */
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ProgressBar loadingSpinner;
    private ListView listView;
    private EditText search;
    private TextView counter;

    @Override
    protected void onPause() {
        super.onPause();
        splitTunnelManager.setDisallowedApps(disallowedApps);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        listExecutor.shutdownNow();
        iconExecutor.shutdownNow();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        splitTunnelManager = new SplitTunnelManager(getApplicationContext());
        disallowedApps = new HashSet<>(splitTunnelManager.getDisallowedApps());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // ─── Title bar ───
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundColor(0xFF00BFA5);
        int tp = dp(16);
        titleBar.setPadding(tp, tp, tp, tp);

        TextView backBtn = new TextView(this);
        backBtn.setText("\u2190");
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        backBtn.setTextColor(Color.WHITE);
        backBtn.setPadding(0, 0, dp(16), 0);
        backBtn.setOnClickListener(v -> finish());
        titleBar.addView(backBtn);

        TextView title = new TextView(this);
        title.setText("Split Tunneling");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleBar.addView(title);
        root.addView(titleBar);

        // ─── Description ───
        TextView desc = new TextView(this);
        desc.setText("Select apps to bypass the VPN. Checked apps will NOT use the VPN tunnel.");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        desc.setTextColor(0xFF666666);
        desc.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.addView(desc);

        // ─── Counter ───
        counter = new TextView(this);
        updateCounter();
        counter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        counter.setTextColor(0xFF999999);
        counter.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(counter);

        // ─── Search ───
        search = new EditText(this);
        search.setHint("Search apps...");
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(dp(16), 0, dp(16), dp(8));
        search.setLayoutParams(sp);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(search);

        // ─── System apps toggle ───
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        toggleRow.setPadding(dp(16), dp(4), dp(16), dp(4));

        CheckBox systemToggle = new CheckBox(this);
        systemToggle.setChecked(showSystemApps);
        systemToggle.setText("Show system apps");
        systemToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        systemToggle.setTextColor(0xFF666666);
        toggleRow.addView(systemToggle);
        root.addView(toggleRow);

        // ─── Select All / Deselect All ───
        LinearLayout bulkRow = new LinearLayout(this);
        bulkRow.setOrientation(LinearLayout.HORIZONTAL);
        bulkRow.setGravity(Gravity.CENTER_VERTICAL);
        bulkRow.setPadding(dp(12), dp(4), dp(12), dp(4));

        Button selectAllBtn = new Button(this);
        selectAllBtn.setText("Select All");
        selectAllBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        selectAllBtn.setAllCaps(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bp.setMargins(dp(4), 0, dp(4), 0);
        selectAllBtn.setLayoutParams(bp);

        Button deselectAllBtn = new Button(this);
        deselectAllBtn.setText("Deselect All");
        deselectAllBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        deselectAllBtn.setAllCaps(false);
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bp2.setMargins(dp(4), 0, dp(4), 0);
        deselectAllBtn.setLayoutParams(bp2);

        bulkRow.addView(selectAllBtn);
        bulkRow.addView(deselectAllBtn);
        root.addView(bulkRow);

        // ─── Loading spinner ───
        loadingSpinner = new ProgressBar(this);
        loadingSpinner.setPadding(0, dp(48), 0, dp(48));
        root.addView(loadingSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ─── App list ───
        listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setVisibility(View.GONE);
        adapter = new AppListAdapter(this, new ArrayList<>(), disallowedApps);
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ─── Listeners ───
        systemToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showSystemApps = isChecked;
            loadAppsAsync();
        });

        selectAllBtn.setOnClickListener(v -> {
            for (AppInfo app : adapter.getFilteredApps()) {
                disallowedApps.add(app.packageName);
            }
            updateCounter();
            adapter.notifyDataSetChanged();
        });

        deselectAllBtn.setOnClickListener(v -> {
            for (AppInfo app : adapter.getFilteredApps()) {
                disallowedApps.remove(app.packageName);
            }
            updateCounter();
            adapter.notifyDataSetChanged();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                adapter.filter(s.toString());
            }
        });

        setContentView(root);
        loadAppsAsync();
    }

    private void loadAppsAsync() {
        loadingSpinner.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);

        final boolean includeSystem = showSystemApps;
        listExecutor.execute(() -> {
            List<AppInfo> apps = loadInstalledApps(includeSystem);
            mainHandler.post(() -> {
                if (isFinishing()) return;
                adapter.setApps(apps);
                adapter.filter(search.getText().toString());
                loadingSpinner.setVisibility(View.GONE);
                listView.setVisibility(View.VISIBLE);
            });
        });
    }

    private void updateCounter() {
        counter.setText(disallowedApps.size() + " app(s) bypassing VPN");
    }

    /**
     * Loads app list in background thread.
     * Does NOT use GET_META_DATA (unnecessary and very slow).
     * Does NOT load icons (loaded asynchronously in getView).
     */
    private List<AppInfo> loadInstalledApps(boolean includeSystem) {
        PackageManager pm = getPackageManager();
        // Use flag 0 instead of GET_META_DATA — much faster
        List<ApplicationInfo> installedApps = pm.getInstalledApplications(0);
        List<AppInfo> appInfos = new ArrayList<>();
        String ownPackage = getPackageName();

        for (ApplicationInfo appInfo : installedApps) {
            if (appInfo.packageName.equals(ownPackage)) continue;
            boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (isSystemApp && !includeSystem) continue;

            String name;
            try {
                name = appInfo.loadLabel(pm).toString();
            } catch (Exception e) {
                name = appInfo.packageName;
            }
            appInfos.add(new AppInfo(name, appInfo.packageName, isSystemApp));
        }

        Collections.sort(appInfos, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return appInfos;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // ═══════════════════════════════════════════
    //  DATA
    // ═══════════════════════════════════════════

    static class AppInfo {
        final String name;
        final String packageName;
        final boolean isSystem;

        AppInfo(String name, String packageName, boolean isSystem) {
            this.name = name;
            this.packageName = packageName;
            this.isSystem = isSystem;
        }
    }

    // ═══════════════════════════════════════════
    //  ADAPTER — fully async icon loading
    // ═══════════════════════════════════════════

    class AppListAdapter extends BaseAdapter {
        private final Context context;
        private List<AppInfo> allApps;
        private List<AppInfo> filteredApps;
        private final Set<String> selected;
        /** In-memory icon cache. Accessed from both UI and icon-loader threads. */
        private final LruCache<String, Drawable> iconCache = new LruCache<>(200);

        AppListAdapter(Context context, List<AppInfo> apps, Set<String> selected) {
            this.context = context;
            this.allApps = new ArrayList<>(apps);
            this.filteredApps = new ArrayList<>(apps);
            this.selected = selected;
        }

        List<AppInfo> getFilteredApps() { return filteredApps; }

        void setApps(List<AppInfo> apps) {
            this.allApps = new ArrayList<>(apps);
            this.filteredApps = new ArrayList<>(apps);
            iconCache.evictAll();
            notifyDataSetChanged();
        }

        void filter(String query) {
            if (query == null || query.isEmpty()) {
                filteredApps = new ArrayList<>(allApps);
            } else {
                String lower = query.toLowerCase();
                filteredApps = new ArrayList<>();
                for (AppInfo app : allApps) {
                    if (app.name.toLowerCase().contains(lower)
                            || app.packageName.toLowerCase().contains(lower)) {
                        filteredApps.add(app);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return filteredApps.size(); }
        @Override public AppInfo getItem(int position) { return filteredApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView != null && convertView.getTag() instanceof ViewHolder) {
                holder = (ViewHolder) convertView.getTag();
            } else {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(8), dp(16), dp(8));

                holder = new ViewHolder();

                holder.icon = new ImageView(context);
                int iconSize = dp(40);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize);
                ip.setMargins(0, 0, dp(12), 0);
                holder.icon.setLayoutParams(ip);
                row.addView(holder.icon);

                LinearLayout textCol = new LinearLayout(context);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                holder.nameView = new TextView(context);
                holder.nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                holder.nameView.setTextColor(Color.BLACK);
                holder.nameView.setMaxLines(1);
                textCol.addView(holder.nameView);

                holder.pkgView = new TextView(context);
                holder.pkgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                holder.pkgView.setMaxLines(1);
                textCol.addView(holder.pkgView);

                row.addView(textCol);

                holder.checkBox = new CheckBox(context);
                row.addView(holder.checkBox);

                row.setTag(holder);
                convertView = row;
            }

            AppInfo app = getItem(position);

            // ── Icon: async loading with placeholder ──
            Drawable cached = iconCache.get(app.packageName);
            if (cached != null) {
                holder.icon.setImageDrawable(cached);
            } else {
                // Show default placeholder immediately (zero UI blocking)
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
                // Tag the view so we can check for recycling
                holder.icon.setTag(app.packageName);
                // Load icon in background thread pool
                final ImageView iconView = holder.icon;
                final String pkg = app.packageName;
                iconExecutor.execute(() -> {
                    try {
                        final Drawable icon = context.getPackageManager().getApplicationIcon(pkg);
                        iconCache.put(pkg, icon);
                        mainHandler.post(() -> {
                            // Only update if this ImageView still shows the same package
                            if (pkg.equals(iconView.getTag())) {
                                iconView.setImageDrawable(icon);
                            }
                        });
                    } catch (Exception e) {
                        // Keep placeholder
                    }
                });
            }

            // ── Text ──
            holder.nameView.setText(app.name);
            String pkgLabel = app.packageName;
            if (app.isSystem) pkgLabel += "  [system]";
            holder.pkgView.setText(pkgLabel);
            holder.pkgView.setTextColor(app.isSystem ? 0xFFAA8800 : 0xFF888888);

            // ── Checkbox: remove listener before setting to avoid unwanted triggers ──
            holder.checkBox.setOnCheckedChangeListener(null);
            holder.checkBox.setChecked(selected.contains(app.packageName));
            holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) selected.add(app.packageName);
                else selected.remove(app.packageName);
                updateCounter();
            });

            final CheckBox cb = holder.checkBox;
            convertView.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));

            return convertView;
        }

        class ViewHolder {
            ImageView icon;
            TextView nameView;
            TextView pkgView;
            CheckBox checkBox;
        }
    }
}
