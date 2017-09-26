package com.helpfromabove.helpfromabove;

import android.app.IntentService;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.content.ServiceConnection;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.cloudrail.si.servicecode.Command;

public class UasCommunicationService extends Service {
    private static final String TAG = "UasCommunicationService";

    private final IBinder mBinder = new UasCommunicationServiceBinder();

    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel wifiP2pChannel;
    private WifiP2pManager.ActionListener wifiP2pScanListener = new WifiP2ScanPActionListener();
    private WifiP2pManager.ActionListener wifiP2pConnectionListener = new WifiP2ConnectPActionListener();

    //debugging variable. Remove before final testing.
    private boolean canConnect = false;

    public UasCommunicationService() {
        super();

        Log.d(TAG, "UasCommunicationService");
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        super.onCreate();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    protected void startScanning() {
        Log.d(TAG, "startScanning");

        wifiP2pManager = (WifiP2pManager) this.getSystemService(Context.WIFI_P2P_SERVICE);
        wifiP2pChannel = wifiP2pManager.initialize(this, getMainLooper(), null);
        wifiP2pManager.discoverPeers(wifiP2pChannel, wifiP2pScanListener);
    }

    protected void connectToDevice(WifiP2pDevice device) {
        Log.d(TAG, "connectToDevice: device.toString()=" + device.toString());

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;

        wifiP2pManager.connect(wifiP2pChannel, config, wifiP2pConnectionListener);

        //debugging boolean. Remove during testing
        canConnect = true;
    }

    public class UasCommunicationServiceBinder extends Binder {
        UasCommunicationService getService() {
            return UasCommunicationService.this;
        }
    }

    private class WifiP2ScanPActionListener implements WifiP2pManager.ActionListener {
        private static final String TAG = "WifiP2ScanPActionLis...";

        @Override
        public void onSuccess() {
            Log.d(TAG, "onSuccess");
        }

        @Override
        public void onFailure(int reasonCode) {
            Log.d(TAG, "onFailure: reasonCode=" + reasonCode);
            switch (reasonCode) {
                case WifiP2pManager.P2P_UNSUPPORTED:
                    Log.w(TAG, "onFailure: P2P_UNSUPPORTED");
                    break;
                case WifiP2pManager.BUSY:
                    Log.w(TAG, "onFailure: BUSY");
                    break;
                case WifiP2pManager.ERROR:
                    Log.w(TAG, "onFailure: ERROR");
                    break;
                default:
                    Log.w(TAG, "onFailure: default");
            }
        }
    }

    private class WifiP2ConnectPActionListener implements WifiP2pManager.ActionListener {
        private static final String TAG = "WifiP2ConnectPAction...";

        @Override
        public void onSuccess() {
            Log.d(TAG, "onSuccess");
            CommandService.notifyUiWifiP2pConnected(getApplicationContext());
        }

        @Override
        public void onFailure(int reasonCode) {
            Log.d(TAG, "onFailure: reasonCode=" + reasonCode);
            switch (reasonCode) {
                case WifiP2pManager.P2P_UNSUPPORTED:
                    Log.w(TAG, "onFailure: P2P_UNSUPPORTED");
                    if (canConnect) {
                        CommandService.notifyUiWifiP2pConnected(getApplicationContext());
                    }
                    break;
                case WifiP2pManager.BUSY:
                    Log.w(TAG, "onFailure: BUSY");
                    break;
                case WifiP2pManager.ERROR:
                    Log.w(TAG, "onFailure: ERROR");
                    break;
                default:
                    Log.w(TAG, "onFailure: default");
            }
        }
    }

}
