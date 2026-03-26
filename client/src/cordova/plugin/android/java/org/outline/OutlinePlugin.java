// Copyright 2018 The Outline Authors
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

package org.outline;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import androidx.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.outline.log.OutlineLogger;
import org.outline.log.SentryErrorReporter;
import org.outline.vpn.Errors;
import org.outline.vpn.LogViewerActivity;
import org.outline.vpn.MainThreadWatchdog;
import org.outline.vpn.SplitTunnelActivity;
import org.outline.vpn.VpnServiceStarter;
import org.outline.vpn.VpnTunnelService;

import outline.GoBackendConfig;
import outline.Outline;
import outline.InvokeMethodResult;
import platerrors.Platerrors;
import platerrors.PlatformError;

import static org.outline.vpn.VpnTunnelService.MessageData;
import static org.outline.vpn.VpnTunnelService.TunnelStatus;

public class OutlinePlugin extends CordovaPlugin {
  private static final Logger LOG = Logger.getLogger(OutlinePlugin.class.getName());

  // Actions supported by this plugin.
  public enum Action {
    INVOKE_METHOD("invokeMethod"),
    START("start"),
    STOP("stop"),
    ON_STATUS_CHANGE("onStatusChange"),
    IS_RUNNING("isRunning"),
    INIT_ERROR_REPORTING("initializeErrorReporting"),
    REPORT_EVENTS("reportEvents"),
    SPLIT_TUNNEL("splitTunnel"),
    VIEW_LOGS("viewLogs"),
    QUIT("quitApplication");

    private final static Map<String, Action> actions = new HashMap<>();
    static {
      for (Action action : Action.values()) {
        actions.put(action.value, action);
      }
    }

    // Returns whether |value| is a defined action.
    public static boolean hasValue(final String value) {
      return actions.containsKey(value);
    }

    public final String value;
    Action(final String value) {
      this.value = value;
    }

    // Returns whether |action| is the underlying value of this instance.
    public boolean is(final String action) {
      return this.value.equals(action);
    }
  }

  // Encapsulates parameters to start the VPN asynchronously after requesting user permission.
  private static class StartVpnRequest {
    public final JSONArray args;
    public final CallbackContext callback;
    public StartVpnRequest(JSONArray args, CallbackContext callback) {
      this.args = args;
      this.callback = callback;
    }
  }

  private static final int REQUEST_CODE_PREPARE_VPN = 100;
  private static final int REQUEST_CODE_NOTIFICATIONS = 101;

  // AIDL interface for VpnTunnelService, which is bound for the lifetime of this class.
  private IVpnTunnelService vpnTunnelService;
  private String errorReportingApiKey;
  private StartVpnRequest startVpnRequest;
  private CallbackContext statusCallback;

  // Latch that signals when the Go backend has finished initializing off the main thread.
  private final CountDownLatch goBackendReady = new CountDownLatch(1);
  private MainThreadWatchdog watchdog;

  // Connection to the VPN service.
  private final ServiceConnection vpnServiceConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder binder) {
      android.util.Log.e("OutlineInit", "onServiceConnected START on thread=" + Thread.currentThread().getName());
      vpnTunnelService = IVpnTunnelService.Stub.asInterface(binder);
      android.util.Log.e("OutlineInit", "onServiceConnected END — VPN service bound");
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
      LOG.warning("VPN service disconnected");
      vpnTunnelService = null;
      // Rebind the service after a short delay to give the :vpn process time to restart.
      // This prevents blocking the main thread if the service is in a transitional state
      // (e.g., after app update with active VPN connection).
      new Thread(() -> {
        try {
          Thread.sleep(1000); // Wait 1 second before rebinding
        } catch (InterruptedException ignored) {}
        try {
          Context context = getBaseContext();
          Intent rebind = new Intent(context, VpnTunnelService.class);
          rebind.putExtra(VpnServiceStarter.AUTOSTART_EXTRA, true);
          rebind.putExtra(MessageData.ERROR_REPORTING_API_KEY.value, errorReportingApiKey);
          context.bindService(rebind, vpnServiceConnection, Context.BIND_AUTO_CREATE);
          LOG.info("VPN service rebind requested");
        } catch (Exception e) {
          LOG.log(Level.WARNING, "Failed to rebind VPN service", e);
        }
      }, "vpn-rebind").start();
    }
  };

  @Override
  protected void pluginInitialize() {
    long t0 = System.currentTimeMillis();
    android.util.Log.e("OutlineInit", "pluginInitialize START");

    OutlineLogger.registerLogHandler(SentryErrorReporter.BREADCRUMB_LOG_HANDLER);
    final Context context = getBaseContext();
    android.util.Log.e("OutlineInit", "registerLogHandler done +" + (System.currentTimeMillis()-t0) + "ms");

    // Register broadcast receiver (lightweight, safe on main thread).
    IntentFilter broadcastFilter = new IntentFilter();
    broadcastFilter.addAction(VpnTunnelService.STATUS_BROADCAST_KEY);
    broadcastFilter.addCategory(context.getPackageName());
    context.registerReceiver(vpnTunnelBroadcastReceiver, broadcastFilter, context.RECEIVER_NOT_EXPORTED);
    android.util.Log.e("OutlineInit", "registerReceiver done +" + (System.currentTimeMillis()-t0) + "ms");

    // Bind VPN service (async, doesn't block)
    context.bindService(new Intent(context, VpnTunnelService.class), vpnServiceConnection,
        Context.BIND_AUTO_CREATE);
    android.util.Log.e("OutlineInit", "bindService done +" + (System.currentTimeMillis()-t0) + "ms");

    // Start ANR watchdog — detects main-thread blocks, writes stack trace to file,
    // and shows a notification so the user can view logs even when UI is frozen.
    watchdog = new MainThreadWatchdog(context);
    watchdog.start();
    android.util.Log.e("OutlineInit", "watchdog started +" + (System.currentTimeMillis()-t0) + "ms");

    // Initialize the Go backend OFF the main thread to prevent ANR.
    new Thread(() -> {
      long gt0 = System.currentTimeMillis();
      try {
        final GoBackendConfig goConfig = Outline.getBackendConfig();
        goConfig.setDataDir(context.getFilesDir().getAbsolutePath());
        android.util.Log.e("OutlineInit", "Go backend init OK in " + (System.currentTimeMillis()-gt0) + "ms");
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Failed to initialize Go backend", e);
      } finally {
        goBackendReady.countDown();
      }
    }, "go-backend-init").start();

    android.util.Log.e("OutlineInit", "pluginInitialize END +" + (System.currentTimeMillis()-t0) + "ms");
  }

  @Override
  public void onDestroy() {
    if (watchdog != null) watchdog.stop();
    Context context = getBaseContext();
    context.unregisterReceiver(vpnTunnelBroadcastReceiver);
    context.unbindService(vpnServiceConnection);
  }

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
    long execStart = System.currentTimeMillis();
    if (!Action.hasValue(action)) {
      return false;
    }
    android.util.Log.e("OutlineExec", "execute() action=" + action + " on thread=" + Thread.currentThread().getName());

    if (Action.QUIT.is(action)) {
      this.cordova.getActivity().finish();
      return true;
    }

    if (Action.ON_STATUS_CHANGE.is(action)) {
      this.statusCallback = callbackContext;
      android.util.Log.e("OutlineExec", "onStatusChange registered in " + (System.currentTimeMillis()-execStart) + "ms");
      return true;
    }

    if (Action.SPLIT_TUNNEL.is(action)) {
      Intent splitTunnelIntent = new Intent(getBaseContext(), SplitTunnelActivity.class);
      this.cordova.getActivity().startActivity(splitTunnelIntent);
      callbackContext.success();
      return true;
    }

    if (Action.VIEW_LOGS.is(action)) {
      // Request notification permission on first log view (deferred from init to avoid ANR)
      requestNotificationPermission();
      Intent logIntent = new Intent(getBaseContext(), LogViewerActivity.class);
      this.cordova.getActivity().startActivity(logIntent);
      callbackContext.success();
      return true;
    }

    if (Action.START.is(action)) {
      // Prepare the VPN before spawning a new thread. Fall through if it's already prepared.
      try {
        if (!prepareVpnService()) {
          startVpnRequest = new StartVpnRequest(args, callbackContext);
          return true;
        }
      } catch (ActivityNotFoundException e) {
        sendActionResult(callbackContext, new PlatformError(Platerrors.InternalError, e.toString()));
        return true;
      }
    }

    executeAsync(action, args, callbackContext);
    android.util.Log.e("OutlineExec", "execute() action=" + action + " dispatched in " + (System.currentTimeMillis()-execStart) + "ms");
    return true;
  }

  /** Requests POST_NOTIFICATIONS permission on Android 13+. */
  private void requestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= 33) { // TIRAMISU
      Activity activity = this.cordova.getActivity();
      if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
              != PackageManager.PERMISSION_GRANTED) {
        LOG.info("Requesting POST_NOTIFICATIONS permission");
        activity.requestPermissions(
                new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_CODE_NOTIFICATIONS);
      }
    }
  }

  /** Waits for the Go backend to be ready (called from background threads only). */
  private void awaitGoBackend() {
    try {
      if (!goBackendReady.await(30, TimeUnit.SECONDS)) {
        LOG.severe("Timed out waiting for Go backend initialization");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // Executes an action asynchronously through the Cordova thread pool.
  private void executeAsync(
      final String action, final JSONArray args, final CallbackContext callback) {
    cordova.getThreadPool().execute(() -> {
      long asyncStart = System.currentTimeMillis();
      android.util.Log.e("OutlineAsync", "executeAsync START action=" + action + " on thread=" + Thread.currentThread().getName());
      try {
        // ── Actions that do NOT need the Go backend ──
        if (Action.IS_RUNNING.is(action)) {
          final String tunnelId = args.getString(0);
          android.util.Log.e("OutlineAsync", "isRunning: calling isTunnelActive for " + tunnelId);
          boolean isActive = isTunnelActive(tunnelId);
          android.util.Log.e("OutlineAsync", "isRunning: result=" + isActive + " in " + (System.currentTimeMillis()-asyncStart) + "ms");
          callback.sendPluginResult(new PluginResult(PluginResult.Status.OK, isActive));
          return;
        }

        if (Action.INIT_ERROR_REPORTING.is(action)) {
          android.util.Log.e("OutlineAsync", "initErrorReporting: spawning sentry-init thread");
          errorReportingApiKey = args.getString(0);
          new Thread(() -> {
            long st0 = System.currentTimeMillis();
            try {
              SentryErrorReporter.init(getBaseContext(), errorReportingApiKey);
              android.util.Log.e("OutlineAsync", "Sentry init done in " + (System.currentTimeMillis()-st0) + "ms");
              if (vpnTunnelService != null) {
                vpnTunnelService.initErrorReporting(errorReportingApiKey);
                android.util.Log.e("OutlineAsync", "Sentry VPN init done in " + (System.currentTimeMillis()-st0) + "ms");
              }
            } catch (Exception e) {
              LOG.log(Level.WARNING, "Error reporting init failed", e);
            }
          }, "sentry-init").start();
          callback.success();
          return;
        }

        if (Action.REPORT_EVENTS.is(action)) {
          final String uuid = args.getString(0);
          SentryErrorReporter.send(uuid);
          callback.success();
          return;
        }

        // ── Actions that NEED the Go backend — wait only here ──
        awaitGoBackend();

        // Wait for VPN service to be bound (may take a moment after app update)
        if (vpnTunnelService == null) {
          LOG.info("Waiting for VPN service to bind...");
          for (int i = 0; i < 50 && vpnTunnelService == null; i++) {
            Thread.sleep(100);
          }
          if (vpnTunnelService == null) {
            LOG.severe("VPN service not bound after 5 seconds");
            sendActionResult(callback, new PlatformError(
                Platerrors.InternalError, "VPN service not available"));
            return;
          }
        }

        if (Action.INVOKE_METHOD.is(action)) {
          final String methodName = args.getString(0);
          final String input = args.getString(1);
          LOG.fine(String.format(Locale.ROOT, "Calling InvokeMethod(%s, %s)", methodName, input));
          final InvokeMethodResult result = Outline.invokeMethod(methodName, input);
          if (result.getError() != null) {
            LOG.warning(String.format(Locale.ROOT, "InvokeMethod(%s) failed: %s", methodName, result.getError()));
            sendActionResult(callback, result.getError());
          } else {
            LOG.fine(String.format(Locale.ROOT, "InvokeMethod(%s) result: %s", methodName, result.getValue()));
            callback.success(result.getValue());
          }
        } else if (Action.START.is(action)) {
          final String tunnelId = args.getString(0);
          final String serverName = args.getString(1);
          final String transportConfig = args.getString(2);
          sendActionResult(callback, startVpnTunnel(tunnelId, transportConfig, serverName));
        } else if (Action.STOP.is(action)) {
          final String tunnelId = args.getString(0);
          LOG.info(String.format(Locale.ROOT, "Stopping VPN tunnel %s", tunnelId));
          sendActionResult(callback, vpnTunnelService.stopTunnel(tunnelId));
        } else {
          throw new IllegalArgumentException(
              String.format(Locale.ROOT, "Unexpected action %s", action));
        }
      } catch (Exception e) {
        LOG.log(Level.SEVERE,
            String.format(Locale.ROOT, "Unexpected error while executing action: %s", action), e);
        sendActionResult(callback, new PlatformError(Platerrors.InternalError, e.toString()));
      }
    });
  }

  // Requests user permission to connect the VPN. Returns true if permission was previously granted,
  // and false if the OS prompt will be displayed.
  private boolean prepareVpnService() throws ActivityNotFoundException {
    LOG.fine("Preparing VPN.");
    Intent prepareVpnIntent = VpnService.prepare(getBaseContext());
    if (prepareVpnIntent == null) {
      return true;
    }
    LOG.info("Prepare VPN with activity");
    cordova.startActivityForResult(this, prepareVpnIntent, REQUEST_CODE_PREPARE_VPN);
    return false;
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    if (request != REQUEST_CODE_PREPARE_VPN) {
      LOG.warning("Received non-requested activity result.");
      return;
    }
    if (result != Activity.RESULT_OK) {
      LOG.warning("Failed to prepare VPN.");
      sendActionResult(startVpnRequest.callback, new PlatformError(
          Platerrors.VPNPermissionNotGranted, "failed to grant the VPN permission"));
      return;
    }
    executeAsync(Action.START.value, startVpnRequest.args, startVpnRequest.callback);
    startVpnRequest = null;
  }

  private DetailedJsonError startVpnTunnel(
      final String tunnelId, final String transportConfig, final String serverName
  ) throws RemoteException {
    LOG.info(String.format(Locale.ROOT, "Starting VPN tunnel %s for server %s", tunnelId, serverName));
    final TunnelConfig tunnelConfig = new TunnelConfig();
    tunnelConfig.id = tunnelId;
    tunnelConfig.name = serverName;
    tunnelConfig.transportConfig = transportConfig;
    return vpnTunnelService.startTunnel(tunnelConfig);
  }

  // Returns whether the VPN service is running a particular tunnel instance.
  private boolean isTunnelActive(final String tunnelId) {
    if (vpnTunnelService == null) {
      LOG.warning("VPN service not yet bound, assuming tunnel is not active");
      return false;
    }
    try {
      return vpnTunnelService.isTunnelActive(tunnelId);
    } catch (Exception e) {
      LOG.log(Level.SEVERE,
          String.format(Locale.ROOT, "Failed to determine if tunnel is active: %s", tunnelId), e);
    }
    return false;
  }

  // Broadcasts

  private VpnTunnelBroadcastReceiver vpnTunnelBroadcastReceiver =
      new VpnTunnelBroadcastReceiver(OutlinePlugin.this);

  // Receiver to forward VPN service broadcasts to the WebView when the tunnel status changes.
  private static class VpnTunnelBroadcastReceiver extends BroadcastReceiver {
    private final OutlinePlugin outlinePlugin;

    public VpnTunnelBroadcastReceiver(OutlinePlugin outlinePlugin) {
      this.outlinePlugin = outlinePlugin;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      android.util.Log.e("OutlineExec", "BroadcastReceiver.onReceive on thread=" + Thread.currentThread().getName());
      final String tunnelId = intent.getStringExtra(MessageData.TUNNEL_ID.value);
      if (tunnelId == null) {
        LOG.warning("Tunnel status broadcast missing tunnel ID");
        return;
      }
      if (outlinePlugin.statusCallback == null) {
        LOG.warning(String.format(
            Locale.ROOT, "No status callback registered with the Android OutlinePlugin"));
        return;
      }
      int status = intent.getIntExtra(MessageData.PAYLOAD.value, TunnelStatus.INVALID.value);
      LOG.fine(String.format(Locale.ROOT, "VPN connectivity changed: %s, %d", tunnelId, status));

      JSONObject jsonResponse = new JSONObject();
      try {
        jsonResponse.put("id", tunnelId);
        jsonResponse.put("status", status);
      } catch (JSONException e) {
        LOG.warning("Failed to build JSON response");
        return;
      }

      PluginResult result = new PluginResult(PluginResult.Status.OK, jsonResponse);
      // Keep the tunnel status callback so it can be called multiple times.
      result.setKeepCallback(true);
      outlinePlugin.statusCallback.sendPluginResult(result);
    }
  };

  // Helpers

  private Context getBaseContext() {
    return this.cordova.getActivity().getApplicationContext();
  }

  private void sendActionResult(final CallbackContext callback, @Nullable PlatformError error) {
    sendActionResult(callback, Errors.toDetailedJsonError(error));
  }

  private void sendActionResult(final CallbackContext callback, @Nullable DetailedJsonError error) {
    if (error == null) {
      callback.success();
    } else {
      callback.error(error.errorJson);
    }
  }
}
