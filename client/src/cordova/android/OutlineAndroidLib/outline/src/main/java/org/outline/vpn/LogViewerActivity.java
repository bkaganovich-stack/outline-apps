package org.outline.vpn;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Displays device logcat filtered to this app's process.
 * Allows copy-to-clipboard and share so users can report ANR/crash details.
 */
public class LogViewerActivity extends Activity {

    private TextView logText;
    private ScrollView scrollView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String capturedLogs = "";

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1E1E); // dark background

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
        title.setText("App Logs");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleBar.addView(title);

        root.addView(titleBar);

        // ─── Button row ───
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        btnRow.setPadding(dp(8), dp(8), dp(8), dp(8));
        btnRow.setBackgroundColor(0xFF2D2D2D);

        Button refreshBtn = createButton("Refresh");
        refreshBtn.setOnClickListener(v -> loadLogs());

        Button copyBtn = createButton("Copy");
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Outline Logs", capturedLogs));
                Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        Button shareBtn = createButton("Share");
        shareBtn.setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Outline VPN Logs");
            shareIntent.putExtra(Intent.EXTRA_TEXT, capturedLogs);
            startActivity(Intent.createChooser(shareIntent, "Share logs via"));
        });

        Button clearBtn = createButton("Clear All");
        clearBtn.setOnClickListener(v -> {
            executor.execute(() -> {
                try {
                    // Clear logcat
                    Runtime.getRuntime().exec(new String[]{"logcat", "-c"});
                    // Clear saved ANR traces
                    java.io.File anrFile = new java.io.File(getFilesDir(), "anr_trace.txt");
                    if (anrFile.exists()) anrFile.delete();
                    Thread.sleep(500);
                } catch (Exception ignored) {}
                loadLogsInBackground();
            });
        });

        btnRow.addView(refreshBtn);
        btnRow.addView(copyBtn);
        btnRow.addView(shareBtn);
        btnRow.addView(clearBtn);
        root.addView(btnRow);

        // ─── Info line ───
        TextView info = new TextView(this);
        info.setText("Showing last 2000 lines of logcat (filtered to this app + ANR)");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        info.setTextColor(0xFF888888);
        info.setPadding(dp(12), dp(4), dp(12), dp(4));
        root.addView(info);

        // ─── Log content ───
        scrollView = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        logText.setTextColor(0xFFCCCCCC);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setPadding(dp(8), dp(8), dp(8), dp(8));
        logText.setText("Loading logs...");
        logText.setTextIsSelectable(true);
        scrollView.addView(logText);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        loadLogs();
    }

    private void loadLogs() {
        logText.setText("Loading logs...");
        executor.execute(this::loadLogsInBackground);
    }

    private void loadLogsInBackground() {
        StringBuilder sb = new StringBuilder();

        // ── Read saved ANR traces first (most important) ──
        try {
            java.io.File anrFile = new java.io.File(getFilesDir(), "anr_trace.txt");
            if (anrFile.exists() && anrFile.length() > 0) {
                sb.append("═══ SAVED ANR STACK TRACES ═══\n\n");
                BufferedReader ar = new BufferedReader(new java.io.FileReader(anrFile));
                String aLine;
                while ((aLine = ar.readLine()) != null) {
                    sb.append(aLine).append("\n");
                }
                ar.close();
                sb.append("\n═══ END ANR TRACES ═══\n\n");
            }
        } catch (Exception e) {
            sb.append("Could not read ANR traces: ").append(e.getMessage()).append("\n\n");
        }

        try {
            // Get this app's PID for filtering
            int pid = android.os.Process.myPid();

            // Capture logcat: last 2000 lines, all priorities
            Process process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-t", "2000", "-v", "threadtime"
            });
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            String pidStr = String.valueOf(pid);
            String pkgName = getPackageName();

            while ((line = reader.readLine()) != null) {
                // Include lines from our process, or ANR-related, or VPN-related
                if (line.contains(pidStr)
                        || line.contains(pkgName)
                        || line.contains("outline")
                        || line.contains("ANR")
                        || line.contains("anr")
                        || line.contains("NOT RESPONDING")
                        || line.contains("InputDispatching")
                        || line.contains("Slow")
                        || line.contains("VpnService")
                        || line.contains("tun2socks")
                        || line.contains("GoLog")
                        || line.contains("cordova")
                        || line.contains("Cordova")
                        || line.contains("ActivityManager")
                        || line.contains("WindowManager")) {
                    sb.append(line).append("\n");
                }
            }
            reader.close();
            process.waitFor();

            // Also try to read ANR traces
            sb.append("\n═══ ANR TRACES ═══\n");
            try {
                Process anrProcess = Runtime.getRuntime().exec(new String[]{
                        "cat", "/data/anr/traces.txt"
                });
                BufferedReader anrReader = new BufferedReader(new InputStreamReader(anrProcess.getInputStream()));
                int anrLines = 0;
                while ((line = anrReader.readLine()) != null && anrLines < 200) {
                    if (line.contains(pkgName) || line.contains("main") || line.contains("DALVIK")
                            || line.contains("at ") || line.contains("tid=")) {
                        sb.append(line).append("\n");
                        anrLines++;
                    }
                }
                anrReader.close();
                if (anrLines == 0) {
                    sb.append("(no ANR traces accessible - this is normal on Android 11+)\n");
                }
            } catch (Exception e) {
                sb.append("Cannot read ANR traces: ").append(e.getMessage()).append("\n");
            }

        } catch (Exception e) {
            sb.append("Error reading logs: ").append(e.getMessage());
        }

        final String logs = sb.toString();
        capturedLogs = logs;

        mainHandler.post(() -> {
            if (isFinishing()) return;
            logText.setText(logs.isEmpty() ? "(no matching log entries)" : logs);
            // Scroll to bottom
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private Button createButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(0xFF444444);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        btn.setLayoutParams(lp);
        btn.setPadding(dp(4), dp(6), dp(4), dp(6));
        return btn;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
