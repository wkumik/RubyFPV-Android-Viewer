package com.rubyfpv.viewer.recordings;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * Joins the drone's Wi-Fi AP ("phone-transfer mode"). On API 29+ this uses a
 * {@link WifiNetworkSpecifier} peer-to-peer request and binds the process to the
 * resulting network so SSH traffic is routed to the drone (not cellular). On older
 * devices, or when no SSID is configured yet, callers fall back to {@link #openWifiPanel}.
 */
public class WifiJoiner {

    private static final String TAG = "WifiJoiner";

    public interface Callback {
        void onBound();
        void onFailed(String reason);
    }

    private final Context appCtx;
    private final ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;

    public WifiJoiner(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** Request and bind to the drone AP. Holds the binding until {@link #release()}. */
    public void join(String ssid, String psk, Callback cb) {
        if (!isSupported()) {
            cb.onFailed("Android version too old for in-app join");
            return;
        }
        release();
        WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (psk != null && !psk.isEmpty()) b.setWpa2Passphrase(psk);

        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(b.build())
                .build();

        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cm.bindProcessToNetwork(network);
                Log.i(TAG, "Bound to drone AP " + ssid);
                cb.onBound();
            }

            @Override
            public void onUnavailable() {
                Log.w(TAG, "AP " + ssid + " unavailable");
                cb.onFailed("Drone Wi-Fi not found");
            }
        };
        cm.requestNetwork(req, callback);
    }

    public void release() {
        if (callback != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
            callback = null;
        }
        try { cm.bindProcessToNetwork(null); } catch (Exception ignored) {}
    }

    /** Open the system Wi-Fi picker so the user can join the AP manually. */
    public static void openWifiPanel(Context ctx) {
        Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? new Intent(Settings.Panel.ACTION_WIFI)
                : new Intent(Settings.ACTION_WIFI_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }
}
