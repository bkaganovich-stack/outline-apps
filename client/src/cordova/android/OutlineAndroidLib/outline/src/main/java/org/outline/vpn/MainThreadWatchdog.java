package org.outline.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors the main thread for ANR conditions.
 *
 * Posts a flag-flip to the main looper every {@code CHECK_INTERVAL_MS}.
 * If the flag is not flipped within {@code ANR_THRESHOLD_MS}, the main
 * thread is considered blocked.  At that point the watchdog:
 *   1. Captures the main-thread stack trace
 *   2. Writes it to {@code anr_trace.txt} in the app files dir
 *   3. Logs everything at ERROR level to logcat
 *   4. Posts a notification so the user can open the log viewer
 */
public class MainThreadWatchdog {

    private static final String TAG = "ANR-Watchdog";
    private static final long CHECK_INTERVAL_MS = 2_000;
    private static final long ANR_THRESHOLD_MS = 3_000;
    private static final String CHANNEL_ID = "anr-watchdog";
    private static final int NOTIFICATION_ID = 9999;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mainThreadResponded = new AtomicBoolean(true);
    private volatile boolean running = true;
    private int anrCount = 0;

    public MainThreadWatchdog(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Start the watchdog on a daemon thread. Call once from pluginInitialize. */
    public void start() {
        // Pre-create notification channel immediately (safe to do from any thread)
        createNotificationChannel();

        Thread watchdog = new Thread(() -> {
            Log.e(TAG, "Watchdog started — threshold=" + ANR_THRESHOLD_MS + "ms, interval=" + CHECK_INTERVAL_MS + "ms");
            int checkNum = 0;
            while (running) {
                checkNum++;
                // Reset flag and ask main thread to set it
                mainThreadResponded.set(false);
                long postTime = System.currentTimeMillis();
                mainHandler.post(() -> {
                    mainThreadResponded.set(true);
                    long delay = System.currentTimeMillis() - postTime;
                    if (delay > 1000) {
                        Log.e(TAG, "!!! Main thread handler delayed by " + delay + "ms !!!");
                    }
                });

                try {
                    Thread.sleep(ANR_THRESHOLD_MS);
                } catch (InterruptedException e) {
                    break;
                }

                Thread mainThread = Looper.getMainLooper().getThread();
                boolean responded = mainThreadResponded.get();
                // Always log at ERROR level for first 20 checks, then every 10th
                if (checkNum <= 20 || checkNum % 10 == 0 || !responded) {
                    Log.e(TAG, "Check #" + checkNum + ": responded=" + responded
                            + " mainState=" + mainThread.getState()
                            + " queueSize=" + mainHandler.getLooper().getQueue().toString());
                    // Dump main thread stack on every check during first 20 checks
                    if (checkNum <= 20) {
                        StackTraceElement[] stack = mainThread.getStackTrace();
                        StringBuilder sb = new StringBuilder("Main thread stack: ");
                        for (int i = 0; i < Math.min(5, stack.length); i++) {
                            sb.append("\n    at ").append(stack[i].toString());
                        }
                        Log.e(TAG, sb.toString());
                    }
                }

                if (!responded) {
                    // Main thread did NOT respond within threshold -> ANR detected
                    anrCount++;
                    onAnrDetected();
                } else {
                    if (anrCount > 0) {
                        Log.e(TAG, "Main thread recovered after " + anrCount + " ANR detection(s)");
                        anrCount = 0;
                    }
                }

                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "anr-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    public void stop() {
        running = false;
    }

    private void onAnrDetected() {
        // Use Log.e for EVERY line so it always shows in logcat regardless of filtering
        Log.e(TAG, "!!! ANR DETECTED (#" + anrCount + ") — main thread is blocked !!!");

        // -- 1. Capture main-thread stack trace --
        Thread mainThread = Looper.getMainLooper().getThread();
        StackTraceElement[] stack = mainThread.getStackTrace();

        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        sb.append("=== ANR DETECTED (#").append(anrCount).append(") ===\n");
        sb.append("Time: ").append(sdf.format(new Date())).append("\n");
        sb.append("Main thread state: ").append(mainThread.getState()).append("\n\n");
        sb.append("Main thread stack trace:\n");
        for (StackTraceElement el : stack) {
            sb.append("    at ").append(el.toString()).append("\n");
        }

        // Log main thread stack to logcat at ERROR level
        Log.e(TAG, "Main thread state: " + mainThread.getState());
        Log.e(TAG, "Main thread stack trace:");
        for (StackTraceElement el : stack) {
            Log.e(TAG, "    at " + el.toString());
        }

        // -- Also dump key threads for context --
        sb.append("\n=== KEY THREADS ===\n");
        Map<Thread, StackTraceElement[]> allTraces = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> entry : allTraces.entrySet()) {
            Thread t = entry.getKey();
            StackTraceElement[] ts = entry.getValue();
            if (t == mainThread || ts.length == 0) continue;
            // Only log threads that might be relevant
            String name = t.getName().toLowerCase(Locale.US);
            boolean relevant = name.contains("cordova") || name.contains("go")
                    || name.contains("sentry") || name.contains("webview")
                    || name.contains("main") || name.contains("binder")
                    || name.contains("outline") || name.contains("vpn")
                    || name.contains("anr") || name.contains("pool");
            if (!relevant) continue;

            sb.append("\n--- ").append(t.getName())
              .append(" (").append(t.getState()).append(") ---\n");
            for (StackTraceElement el : ts) {
                sb.append("    at ").append(el.toString()).append("\n");
            }
            // Also log to logcat
            Log.e(TAG, "Thread: " + t.getName() + " (" + t.getState() + ")");
            for (StackTraceElement el : ts) {
                Log.e(TAG, "    at " + el.toString());
            }
        }

        String trace = sb.toString();

        // -- 2. Write to file --
        try {
            File file = new File(context.getFilesDir(), "anr_trace.txt");
            // Append so we keep history of multiple ANRs
            PrintWriter pw = new PrintWriter(new FileWriter(file, true));
            pw.println(trace);
            pw.println();
            pw.close();
            Log.e(TAG, "ANR trace written to " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to write ANR trace: " + e.getMessage());
        }

        // -- 3. Show notification --
        showAnrNotification();
    }

    private void createNotificationChannel() {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "ANR Watchdog", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifies when the app becomes unresponsive");
            nm.createNotificationChannel(channel);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create notification channel: " + e.getMessage());
        }
    }

    private void showAnrNotification() {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            // Check notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "POST_NOTIFICATIONS permission not granted — cannot show notification. "
                            + "Check App Logs button or logcat for ANR trace.");
                    return;
                }
            }

            // Intent to open LogViewerActivity
            Intent intent = new Intent(context, LogViewerActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Outline: ANR Detected")
                    .setContentText("Tap to view stack trace")
                    .setStyle(new Notification.BigTextStyle()
                            .bigText("Main thread was blocked for " + (ANR_THRESHOLD_MS/1000)
                                    + "+ seconds. Tap to see the stack trace and share it."))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();

            nm.notify(NOTIFICATION_ID, notification);
            Log.e(TAG, "ANR notification posted");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show ANR notification: " + e.getMessage());
        }
    }
}
