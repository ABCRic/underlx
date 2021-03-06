package im.tny.segvault.disturbances;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import im.tny.segvault.s2ls.wifi.BSSID;
import im.tny.segvault.s2ls.wifi.WiFiLocator;
import im.tny.segvault.subway.Network;
import im.tny.segvault.subway.Stop;

import static android.content.Context.MODE_PRIVATE;

/**
 * Created by gabriel on 4/12/17.
 */

class WiFiChecker {
    private Map<String, WiFiLocator> locators = new HashMap<>();
    private long scanInterval = 30000;
    private final WifiManager wifiMan;
    private final Handler handler = new Handler();
    private boolean isScanning;
    List<ScanResult> wifiList = new ArrayList<>();

    public WiFiChecker(Context context, WifiManager manager) {
        wifiMan = manager;
        context.registerReceiver(mBroadcastReceiver, new IntentFilter(
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    public void setScanInterval(long scanInterval) {
        this.scanInterval = scanInterval;
    }

    private void doScan() {
        isScanning = true;
        wifiMan.startScan();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                doScan();
            }
        }, scanInterval);
    }

    public void stopScanning() {
        isScanning = false;
        handler.removeCallbacksAndMessages(null);
    }

    public void startScanning() {
        boolean enabled = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            enabled = wifiMan.isScanAlwaysAvailable();
        }
        if (wifiMan.isWifiEnabled() == false && !enabled) {
            wifiMan.setWifiEnabled(true);
        }
        stopScanning();
        doScan();
    }

    public void startScanningIfWiFiEnabled() {
        boolean enabled = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            enabled = wifiMan.isScanAlwaysAvailable();
        }
        if (wifiMan.isWifiEnabled() || enabled) {
            stopScanning();
            doScan();
        }
    }

    public boolean isScanning() {
        return isScanning;
    }

    public long getScanInterval() {
        return scanInterval;
    }

    public List<ScanResult> getLastScanResults() {
        return new ArrayList<>(wifiList);
    }

    // this method is for debugging only, meant to be called in an expression evaluator in a debugger
    public void updateBSSIDsDebug(String listString) {
        String[] ids = listString.split(",");
        List<BSSID> bssids = new ArrayList<>();
        for (String id : ids) {
            bssids.add(new BSSID(id));
        }
        updateBSSIDsDebug(bssids);
    }

    // used by MainService for mocking locations in debug builds
    public void updateBSSIDsDebug(List<BSSID> bssids) {
        for (WiFiLocator w : locators.values()) {
            w.updateCurrentBSSIDs(bssids);
        }
    }

    public void setLocatorForNetwork(Network network, WiFiLocator locator) {
        locators.put(network.getId(), locator);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            Log.d("WiFiChecker", "onReceive");
            SharedPreferences sharedPref = c.getSharedPreferences("settings", MODE_PRIVATE);
            if (!sharedPref.getBoolean("pref_location_enable", true)) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_DENIED) {
                // we don't have permission to getScanResults
                return;
            }
            wifiList = wifiMan.getScanResults();
            List<BSSID> bssids = new ArrayList<>();
            // sort by descending signal strength
            Collections.sort(wifiList, Collections.<ScanResult>reverseOrder(new Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult scanResult, ScanResult t1) {
                    return scanResult.level - t1.level;
                }
            }));
            for (ScanResult s : wifiList) {
                bssids.add(new BSSID(s.BSSID));
                Log.d("WiFiChecker", s.BSSID);
            }
            for (WiFiLocator w : locators.values()) {
                w.updateCurrentBSSIDs(bssids);
            }
        }
    };
}
