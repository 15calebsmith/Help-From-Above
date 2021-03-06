package com.helpfromabove.helpfromabove;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.location.Location;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

public class UasCommunicationService extends Service {
    private static final String TAG = "UasCommunicationService";

    private final IBinder mBinder = new UasCommunicationServiceBinder();

    private UasCommunicationServiceBroadcastReceiver broadcastReceiver;

    private WifiManager wifiManager;
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel wifiP2pChannel;
    private WifiP2pManager.ActionListener wifiP2pScanListener = new WifiP2pScanActionListener();
    private WifiP2pManager.ActionListener wifiP2pConnectionListener = new WifiP2pConnectActionListener();

    private WifiP2pInfo wifiP2pInfo;
    private NetworkInfo networkInfo;
    private WifiP2pGroup wifiP2pGroup;

    //Used for connection to the UASC
    private boolean connectedOnce = false;

    private UASCClient uascClient;
    private final String uascIP = "192.168.49.187";
    private final String port = "5000";
    private final String imageEndpoint = "static/img/img.jpeg";
    private final String gpsReceiveEndpoint = "request_location";
    private final String gpsSendEndpoint = "update_location";
    private final String startEndpoint = "start_session";
    private final String endEndpoint = "end_session";
    private final String lightEndpoint = "toggle_light";
    private final String emergencyEndpoint = "emergency";


    public UasCommunicationService() {
        super();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(CommandService.ACTION_SKIPPED_WIFI_CONNECTION);

        broadcastReceiver = new UasCommunicationServiceBroadcastReceiver();
        registerReceiver(broadcastReceiver, intentFilter);

        setWifiManager();
        turnOnWifi();
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(broadcastReceiver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void setWifiManager() {
        wifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
    }

    private void turnOnWifi() {
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }
    }

    protected void startScanning() {
        wifiP2pManager = (WifiP2pManager) this.getSystemService(Context.WIFI_P2P_SERVICE);
        wifiP2pChannel = wifiP2pManager.initialize(this, getMainLooper(), null);
        wifiP2pManager.discoverPeers(wifiP2pChannel, wifiP2pScanListener);
    }

    protected void connectToDevice(WifiP2pDevice device) {
        CommandService.notifyWifiP2pConnectingToUasc(getApplicationContext());

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.groupOwnerIntent = 0;
        config.wps.setup = WpsInfo.PBC;

        wifiP2pManager.connect(wifiP2pChannel, config, wifiP2pConnectionListener);

        //Check if its the UASC and if we haven't already tried to connected before
        //This check also allows for testing with other test servers so we don't
        //always need the UASC for testing.
        if (device.toString().contains("HFA") && !connectedOnce) {
            CommandService.notifyWifiP2pWaitingForUasc(getApplicationContext());

            //Waiting to do this after 3 seconds to make sure the UASC has received our information.
            //***The amount of time needed to wait might need to be higher***
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    wifiP2pManager.cancelConnect(wifiP2pChannel, wifiP2pConnectionListener);
                    connectedOnce = true;
                    startScanning();
                }
            }, 3000);
        }
    }

    private void handleWifiP2pConnectionChangedAction(WifiP2pInfo wifiP2pInfo, NetworkInfo networkInfo, WifiP2pGroup wifiP2pGroup) {
        if (networkInfo.getState() == NetworkInfo.State.CONNECTING && connectedOnce) {
            CommandService.notifyWifiP2pConnectingFromUasc(getApplicationContext());
        } else if (networkInfo.getState() == NetworkInfo.State.CONNECTED) {
            this.wifiP2pInfo = wifiP2pInfo;
            this.networkInfo = networkInfo;
            this.wifiP2pGroup = wifiP2pGroup;

            //If connecting to the UASC use its IP
            if (connectedOnce) {
                uascClient = new UASCClient(getApplicationContext(), "192.168.49.187", port);
                CommandService.notifyWifiP2pConnected(getApplicationContext());
            } else {
                uascClient = new UASCClient(getApplicationContext(), wifiP2pInfo.groupOwnerAddress.getHostAddress(), port);
                CommandService.notifyWifiP2pConnected(getApplicationContext());
            }

            Log.i(TAG, "----------------------------------------");
            Log.i(TAG, "HHMD and UASC Connected");
            Log.i(TAG, "----------------------------------------");
            WifiP2pDevice owner = wifiP2pGroup.getOwner();
            Log.i(TAG, "Owner                    = " + owner.deviceName + " : " + owner.deviceAddress);
            Log.i(TAG, "----------------------------------------");
            for (WifiP2pDevice client : wifiP2pGroup.getClientList()) {
                Log.i(TAG, "Client                   = " + client.deviceName + " : " + client.deviceAddress);
            }
            Log.i(TAG, "----------------------------------------");


            startHeartbeat();
        } else {
            if (uascClient != null) {
                uascClient.stopHeartbeat();
                uascClient.stopImageAccess();
            }
        }
    }

    private void startHeartbeat() {
        uascClient.startHeartbeat(10000);
    }

    private void initUascClient() {
        WifiP2pDevice groupOwner = wifiP2pGroup.getOwner();

        if (wifiP2pInfo.isGroupOwner) {
            //If the users device is the group owner connections will not work properly

            //This most likely means they are trying to connect to the wrong device
            //TODO: Notify the user they are connected to the device incorrectly and to reconnect the correct way.
        } else if (groupOwner.toString().contains("HFA")) {
            uascClient = new UASCClient(getApplicationContext(), uascIP, port);
            CommandService.notifyWifiP2pConnected(getApplicationContext());
        }
        //Used for testing server connection
        else {
            uascClient = new UASCClient(getApplicationContext(), wifiP2pInfo.groupOwnerAddress.getHostAddress(), port);
            CommandService.notifyWifiP2pConnected(getApplicationContext());
        }

    }

    protected void sendStartSession() {
        uascClient.sendStartSession(startEndpoint);
    }

    protected void onLocationCalibrationComplete() {
        if (uascClient != null) {
            uascClient.startImageAccess(imageEndpoint, 1000);
            uascClient.startGPSAccess(gpsReceiveEndpoint, 5000);
        }
    }

    protected void stopSession() {
        if (uascClient != null) {
            uascClient.stopGPSAccess();
            uascClient.stopImageAccess();
            uascClient.sendEndSession(endEndpoint);
        }
    }

    protected void setLightOnOff(boolean lightOnOff) {
        uascClient.toogleLight(lightEndpoint, lightOnOff);
    }

    protected void sendWaypoint(Location waypoint) {
        if (waypoint != null)
            uascClient.sendNewWaypoint(gpsSendEndpoint, waypoint);
    }

    public Bitmap getNewImage() {
        if (uascClient != null) {
            return uascClient.getImageBitmap();
        } else {
            return null;
        }
    }

    public Location getNewUasLocation() {
        if (uascClient != null) {
            return uascClient.getNewUasLocation();
        } else {
            return null;
        }
    }

    class UasCommunicationServiceBinder extends Binder {
        UasCommunicationService getService() {
            return UasCommunicationService.this;
        }
    }

    private class UasCommunicationServiceBroadcastReceiver extends BroadcastReceiver {
        private static final String TAG = "Uas...BroadcastReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                        WifiP2pInfo wifiP2pInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                        NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                        WifiP2pGroup wifiP2pGroup = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                        handleWifiP2pConnectionChangedAction(wifiP2pInfo, networkInfo, wifiP2pGroup);
                        break;
                    case CommandService.ACTION_SKIPPED_WIFI_CONNECTION:
                        initUascClient();
                    default:
                        Log.w(TAG, "onReceive: default: action=" + action);
                        break;
                }
            }
        }
    }

    private class WifiP2pScanActionListener implements WifiP2pManager.ActionListener {
        private static final String TAG = "WifiP2ScanPActionLis...";

        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(int reasonCode) {
            switch (reasonCode) {
                case WifiP2pManager.P2P_UNSUPPORTED:
                    Log.w(TAG, "onFailure: P2P_UNSUPPORTED");
                    break;
                case WifiP2pManager.BUSY:
                    Log.w(TAG, "onFailure: BUSY");
                    //This is needed because trying to start scanning right after doing
                    //an action causes a busy message so this will loop here every second
                    //until it can start scanning again.
                    if (connectedOnce) {
                        Handler handler = new Handler();
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                startScanning();
                            }
                        }, 1000);
                    }
                    break;
                case WifiP2pManager.ERROR:
                    Log.w(TAG, "onFailure: ERROR");
                    break;
                default:
                    Log.w(TAG, "onFailure: default");
            }
        }
    }

    private class WifiP2pConnectActionListener implements WifiP2pManager.ActionListener {
        private static final String TAG = "WifiP2ConnectPAction...";

        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(int reasonCode) {
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
}
