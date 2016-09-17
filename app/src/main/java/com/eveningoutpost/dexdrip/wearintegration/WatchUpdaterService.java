package com.eveningoutpost.dexdrip.wearintegration;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.Calibration;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.Sensor;
import com.eveningoutpost.dexdrip.UtilityModels.BgGraphBuilder;
import com.eveningoutpost.dexdrip.UtilityModels.BgSendQueue;
import com.eveningoutpost.dexdrip.UtilityModels.Constants;
import com.eveningoutpost.dexdrip.xdrip;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import com.google.gson.Gson;//KS
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.google.gson.internal.bind.DateTypeAdapter;

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class WatchUpdaterService extends WearableListenerService implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {
    public static final String ACTION_RESEND = WatchUpdaterService.class.getName().concat(".Resend");
    public static final String ACTION_OPEN_SETTINGS = WatchUpdaterService.class.getName().concat(".OpenSettings");
    public static final String ACTION_SYNC_DB = WatchUpdaterService.class.getName().concat(".SyncDB");//KS
    public static final String ACTION_SYNC_SENSOR = WatchUpdaterService.class.getName().concat(".SyncSensor");//KS
    public static final String ACTION_SYNC_CALIBRATION = WatchUpdaterService.class.getName().concat(".SyncCalibration");//KS
    public static final String SYNC_DB_PATH = "/syncweardb";//KS
    public static final String WEARABLE_CALIBRATION_DATA_PATH = "/nightscout_watch_cal_data";//KS
    public static final String WEARABLE_SENSOR_DATA_PATH = "/nightscout_watch_sensor_data";//KS
    public static final String WEARABLE_DATA_PATH = "/nightscout_watch_data";
    public static final String WEARABLE_RESEND_PATH = "/nightscout_watch_data_resend";
    public static final String WEARABLE_VOICE_PAYLOAD = "/xdrip_plus_voice_payload";
    public static final String WEARABLE_APPROVE_TREATMENT = "/xdrip_plus_approve_treatment";
    public static final String WEARABLE_CANCEL_TREATMENT = "/xdrip_plus_cancel_treatment";
    private static final String WEARABLE_TREATMENT_PAYLOAD = "/xdrip_plus_treatment_payload";
    private static final String WEARABLE_TOAST_NOTIFICATON = "/xdrip_plus_toast";
    private static final String OPEN_SETTINGS_PATH = "/openwearsettings";

    private static final String TAG = "jamorham watchupdater";
    private static GoogleApiClient googleApiClient;
    boolean wear_integration = false;
    boolean pebble_integration = false;
    SharedPreferences mPrefs;
    SharedPreferences.OnSharedPreferenceChangeListener mPreferencesListener;

    public static void receivedText(Context context, String text) {
        startHomeWithExtra(context, WEARABLE_VOICE_PAYLOAD, text);
    }

    private static void approveTreatment(Context context, String text) {
        startHomeWithExtra(context, WEARABLE_APPROVE_TREATMENT, text);
    }

    private static void cancelTreatment(Context context, String text) {
        startHomeWithExtra(context, WEARABLE_CANCEL_TREATMENT, text);
    }

    private static void startHomeWithExtra(Context context, String extra, String text) {
        Intent intent = new Intent(context, Home.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(extra, text);
        context.startActivity(intent);
    }

    public void syncBGData(DataMap dataMap) {//KS
        Log.d(TAG, "syncBGData");
        java.text.DateFormat df = new SimpleDateFormat("MM.dd.yyyy HH:mm:ss");
        Date date = new Date();

        ArrayList<DataMap> entries = dataMap.getDataMapArrayList("entries");
        Log.d(TAG, "syncBGData add BgReading Table" );
        if (entries != null) {
            //Gson gson = new GsonBuilder().create();

            Gson gson = new GsonBuilder()
                    .excludeFieldsWithoutExposeAnnotation()
                    .registerTypeAdapter(Date.class, new DateTypeAdapter())
                    .serializeSpecialFloatingPointValues()
                    .create();

            Log.d(TAG, "syncBGData add BgReading Table entries count=" + entries.size());
            for (DataMap entry : entries) {
                if (entry != null) {
                    Log.d(TAG, "syncBGData add BgReading Table entry=" + entry);
                    BgReading bgReading = new BgReading();
                    String bgrecord = entry.getString("bgs");
                    if (bgrecord != null) {
                        Log.d(TAG, "syncBGData add BgReading Table bgrecord=" + bgrecord);
                        BgReading bgData = gson.fromJson(bgrecord, BgReading.class);
                        BgReading exists = BgReading.getForTimestamp(bgData.timestamp);
                        BgReading uuidexists = BgReading.findByUuid(bgData.uuid);
                        date.setTime(bgData.timestamp);
                        if (exists != null || uuidexists != null) {
                            Log.d(TAG, "syncBGData BG already exists for uuid=" + bgData.uuid + " timestamp=" + bgData.timestamp + " timeString=" + df.format(date));
                        }
                        else {
                            Log.d(TAG, "syncBGData add BG; does NOT exist for uuid=" + bgData.uuid + " timestamp=" + bgData.timestamp + " timeString=" + df.format(date));
                            bgData.save();
                            exists = BgReading.findByUuid(bgData.uuid);
                            if (exists != null)
                                Log.d(TAG, "BG GSON saved BG: " + exists.toS());
                            else
                                Log.d(TAG, "BG GSON NOT saved Calibration");
                        }
                    }
                }
            }
        }
    }

    public static void sendWearToast(String msg, int length)
    {
        if ((googleApiClient != null) && (googleApiClient.isConnected())) {
            PutDataMapRequest dataMapRequest = PutDataMapRequest.create(WEARABLE_TOAST_NOTIFICATON);
            dataMapRequest.setUrgent();
            dataMapRequest.getDataMap().putDouble("timestamp", System.currentTimeMillis());
            dataMapRequest.getDataMap().putInt("length", length);
            dataMapRequest.getDataMap().putString("msg", msg);
            PutDataRequest putDataRequest = dataMapRequest.asPutDataRequest();
            Wearable.DataApi.putDataItem(googleApiClient, putDataRequest);
        } else {
            Log.e(TAG, "No connection to wearable available for toast! "+msg);
        }
    }

    public static void sendTreatment(double carbs, double insulin, double bloodtest, double timeoffset, String timestring) {
        if ((googleApiClient != null) && (googleApiClient.isConnected())) {
            PutDataMapRequest dataMapRequest = PutDataMapRequest.create(WEARABLE_TREATMENT_PAYLOAD);
            //unique content
            dataMapRequest.setUrgent();
            dataMapRequest.getDataMap().putDouble("timestamp", System.currentTimeMillis());
            dataMapRequest.getDataMap().putDouble("carbs", carbs);
            dataMapRequest.getDataMap().putDouble("insulin", insulin);
            dataMapRequest.getDataMap().putDouble("bloodtest", bloodtest);
            dataMapRequest.getDataMap().putDouble("timeoffset", timeoffset);
            dataMapRequest.getDataMap().putString("timestring", timestring);
            dataMapRequest.getDataMap().putBoolean("ismgdl", doMgdl(PreferenceManager.getDefaultSharedPreferences(xdrip.getAppContext())));
            PutDataRequest putDataRequest = dataMapRequest.asPutDataRequest();
            Wearable.DataApi.putDataItem(googleApiClient, putDataRequest);
        } else {
            Log.e(TAG, "No connection to wearable available for send treatment!");
        }
    }

    public static boolean doMgdl(SharedPreferences sPrefs) {
        String unit = sPrefs.getString("units", "mgdl");
        if (unit.compareTo("mgdl") == 0) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onCreate() {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        listenForChangeInSettings();
        setSettings();
        if (wear_integration) {
            googleApiConnect();
        }
    }

    public void listenForChangeInSettings() {
        mPreferencesListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                setSettings();
            }
        };
        mPrefs.registerOnSharedPreferenceChangeListener(mPreferencesListener);
    }

    public void setSettings() {
        wear_integration = mPrefs.getBoolean("wear_sync", false);
        pebble_integration = mPrefs.getBoolean("pebble_sync", false);
        if (wear_integration) {
            googleApiConnect();
            Log.d(TAG, "setSettings wear_sync changed to True. Action=" + ACTION_SYNC_DB + " Path=" + SYNC_DB_PATH);//KS
            sendNotification(SYNC_DB_PATH, "syncDB");//KS TODO Clear/Reset Wear DB tables
            sendSensorData();//KS
            sendWearCalibrationData();//KS
        } else {
            this.stopService(new Intent(this, WatchUpdaterService.class));
        }
    }

    public void googleApiConnect() {
        if (googleApiClient != null && (googleApiClient.isConnected() || googleApiClient.isConnecting())) {
            googleApiClient.disconnect();
        }
        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
        Wearable.MessageApi.addListener(googleApiClient, this);
        if (googleApiClient.isConnected()) {
            Log.d("WatchUpdater", "API client is connected");
        } else {
            googleApiClient.connect();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final PowerManager.WakeLock wl = JoH.getWakeLock("watchupdate-onstart",60000);
        double timestamp = 0;
        if (intent != null) {
            timestamp = intent.getDoubleExtra("timestamp", 0);
        }

        String action = null;
        if (intent != null) {
            action = intent.getAction();
        }

        if (wear_integration) {
            if (googleApiClient.isConnected()) {
                if (ACTION_RESEND.equals(action)) {
                    resendData();
                } else if (ACTION_OPEN_SETTINGS.equals(action)) {
                    sendNotification(OPEN_SETTINGS_PATH, "openSettings");//KS add args
                } else if (ACTION_SYNC_DB.equals(action)) {//KS
                    Log.d(TAG, "onStartCommand Action=" + ACTION_SYNC_DB + " Path=" + SYNC_DB_PATH);
                    sendNotification(SYNC_DB_PATH, "syncDB");//KS TODO Clear/Reset Wear DB tables
                    sendSensorData();
                    sendWearCalibrationData();
                } else if (ACTION_SYNC_SENSOR.equals(action)) {//KS
                    Log.d(TAG, "onStartCommand Action=" + ACTION_SYNC_SENSOR + " Path=" + WEARABLE_SENSOR_DATA_PATH);
                    sendSensorData();
                } else if (ACTION_SYNC_CALIBRATION.equals(action)) {//KS
                    Log.d(TAG, "onStartCommand Action=" + ACTION_SYNC_CALIBRATION + " Path=" + WEARABLE_CALIBRATION_DATA_PATH);
                    sendWearCalibrationData();
                } else {
                    sendData();
                }
            } else {
                googleApiClient.connect();
            }
        }

        if (pebble_integration) {
            sendData();
        }

        //if ((!wear_integration)&&(!pebble_integration))
        if (!wear_integration)    // only wear sync starts this service, pebble features are not used?
        {
            Log.i(TAG,"Stopping service");
            stopSelf();
            JoH.releaseWakeLock(wl);
            return START_NOT_STICKY;
        }

        JoH.releaseWakeLock(wl);
        return START_STICKY;
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        sendData();
    }

    // incoming messages from wear device
    @Override
    public void onMessageReceived(MessageEvent event) {
        if (wear_integration) {
            final PowerManager.WakeLock wl = JoH.getWakeLock("watchupdate-msgrec", 60000);
            if (event != null) {
                Log.d(TAG, "wearable event path: " + event.getPath());
                switch (event.getPath()) {
                    case WEARABLE_RESEND_PATH:
                        resendData();
                        break;
                    case WEARABLE_VOICE_PAYLOAD:
                        String eventData = "";
                        try {
                            eventData = new String(event.getData(), "UTF-8");
                        } catch (UnsupportedEncodingException e) {
                            eventData = "error";
                        }
                        Log.d(TAG, "Received wearable: voice payload: " + eventData);
                        if (eventData.length() > 1)
                            receivedText(getApplicationContext(), eventData);
                        break;
                    case WEARABLE_APPROVE_TREATMENT:
                        approveTreatment(getApplicationContext(), "");
                        break;
                    case WEARABLE_CANCEL_TREATMENT:
                        cancelTreatment(getApplicationContext(), "");
                        break;
                    case SYNC_DB_PATH://KS
                        String message = new String(event.getData());
                        DataMap dataMap = DataMap.fromByteArray(event.getData());
                        if (dataMap != null) {
                            Log.d(TAG, "onMessageReceived dataMap=" + dataMap);
                            syncBGData(dataMap);
                        }
                        break;
                    default:
                        Log.d(TAG, "Unknown wearable path: " + event.getPath());
                        break;
                }
            }
            JoH.releaseWakeLock(wl);
        }
    }

    public void sendData() {
        BgReading bg = BgReading.last();
        if (bg != null) {
            if (googleApiClient != null && !googleApiClient.isConnected() && !googleApiClient.isConnecting()) {
                googleApiConnect();
            }
            if (wear_integration) {
                new SendToDataLayerThread(WEARABLE_DATA_PATH, googleApiClient).executeOnExecutor(xdrip.executor, dataMap(bg, mPrefs, new BgGraphBuilder(getApplicationContext())));
            }
        }
    }

    private void resendData() {
        if (googleApiClient != null && !googleApiClient.isConnected() && !googleApiClient.isConnecting()) {
            googleApiConnect();
        }
        long startTime = new Date().getTime() - (60000 * 60 * 24);
        BgReading last_bg = BgReading.last();
        if (last_bg != null) {
            List<BgReading> graph_bgs = BgReading.latestForGraph(60, startTime);
            BgGraphBuilder bgGraphBuilder = new BgGraphBuilder(getApplicationContext());
            if (!graph_bgs.isEmpty()) {
                DataMap entries = dataMap(last_bg, mPrefs, bgGraphBuilder);
                final ArrayList<DataMap> dataMaps = new ArrayList<>(graph_bgs.size());
                for (BgReading bg : graph_bgs) {
                    dataMaps.add(dataMap(bg, mPrefs, bgGraphBuilder));
                }
                entries.putDataMapArrayList("entries", dataMaps);

                new SendToDataLayerThread(WEARABLE_DATA_PATH, googleApiClient).executeOnExecutor(xdrip.executor, entries);
            }
        }
    }

    private void sendNotification(String path, String notification) {//KS add args
        if (googleApiClient.isConnected()) {
            Log.d(TAG, "sendNotification Notification=" + notification + " Path=" + path);
            PutDataMapRequest dataMapRequest = PutDataMapRequest.create(path);
            //unique content
            dataMapRequest.setUrgent();
            dataMapRequest.getDataMap().putDouble("timestamp", System.currentTimeMillis());
            dataMapRequest.getDataMap().putString(notification, notification);
            PutDataRequest putDataRequest = dataMapRequest.asPutDataRequest();
            Wearable.DataApi.putDataItem(googleApiClient, putDataRequest);
        } else {
            Log.e(notification, "No connection to wearable available!");
        }
    }


    private DataMap dataMap(BgReading bg, SharedPreferences sPrefs, BgGraphBuilder bgGraphBuilder) {
        Double highMark = Double.parseDouble(sPrefs.getString("highValue", "170"));
        Double lowMark = Double.parseDouble(sPrefs.getString("lowValue", "70"));
        DataMap dataMap = new DataMap();

        int battery = BgSendQueue.getBatteryLevel(getApplicationContext());

        dataMap.putString("sgvString", bgGraphBuilder.unitized_string(bg.calculated_value));
        dataMap.putString("slopeArrow", bg.slopeArrow());
        dataMap.putDouble("timestamp", bg.timestamp); //TODO: change that to long (was like that in NW)
        dataMap.putString("delta", bgGraphBuilder.unitizedDeltaString(true, true, true));
        dataMap.putString("battery", "" + battery);
        dataMap.putLong("sgvLevel", sgvLevel(bg.calculated_value, sPrefs, bgGraphBuilder));
        dataMap.putInt("batteryLevel", (battery >= 30) ? 1 : 0);
        dataMap.putDouble("sgvDouble", bg.calculated_value);
        dataMap.putDouble("high", inMgdl(highMark, sPrefs));
        dataMap.putDouble("low", inMgdl(lowMark, sPrefs));
        dataMap.putString("dex_txid", sPrefs.getString("dex_txid", "ABCDEF"));//KS
        dataMap.putString("units", sPrefs.getString("units", "mgdl"));//KS
        //TODO: Add raw again
        //dataMap.putString("rawString", threeRaw((prefs.getString("units", "mgdl").equals("mgdl"))));
        return dataMap;
    }

    public void sendSensorData() {//KS
        if(googleApiClient != null && !googleApiClient.isConnected() && !googleApiClient.isConnecting()) { googleApiConnect(); }
        Sensor sensor = Sensor.currentSensor();
        if (sensor != null) {
            if (wear_integration) {
                DataMap dataMap = new DataMap();
                Log.d(TAG, "Sensor sendSensorData uuid=" + sensor.uuid + " started_at=" + sensor.started_at + " active=" + sensor.isActive() + " battery=" + sensor.latest_battery_level + " location=" + sensor.sensor_location + " stopped_at=" + sensor.stopped_at);
                String json = sensor.toS();
                Log.d(TAG, "dataMap sendSensorData GSON: " + json);
                //dataMap.putString("data", json);

                dataMap.putLong("time", new Date().getTime()); // MOST IMPORTANT LINE FOR TIMESTAMP

                dataMap.putString("dex_txid", mPrefs.getString("dex_txid", "ABCDEF"));//KS
                dataMap.putLong("started_at", sensor.started_at);
                dataMap.putString("uuid", sensor.uuid);
                dataMap.putInt("latest_battery_level", sensor.latest_battery_level);
                dataMap.putString("sensor_location", sensor.sensor_location);

                new SendToDataLayerThread(WEARABLE_SENSOR_DATA_PATH, googleApiClient).executeOnExecutor(xdrip.executor, dataMap);
            }
        }
    }

    private void sendWearCalibrationData() {//KS
        if(googleApiClient != null && !googleApiClient.isConnected() && !googleApiClient.isConnecting()) { googleApiConnect(); }
        Log.d(TAG, "sendWearCalibrationData");
        Calibration last = Calibration.last();
        List<Calibration> lastest = Calibration.latest(3);
        if (lastest != null && !lastest.isEmpty()) {
            Log.d(TAG, "sendWearCalibrationData lastest count = " + lastest.size());
            DataMap entries = dataMap(last);
            final ArrayList<DataMap> dataMaps = new ArrayList<>(lastest.size());
            for (Calibration cal : lastest) {
                dataMaps.add(dataMap(cal));
            }
            entries.putLong("time", new Date().getTime()); // MOST IMPORTANT LINE FOR TIMESTAMP
            entries.putDataMapArrayList("entries", dataMaps);
            new SendToDataLayerThread(WEARABLE_CALIBRATION_DATA_PATH, googleApiClient).executeOnExecutor(xdrip.executor, entries);
        }
        else
            Log.d(TAG, "sendWearCalibrationData lastest count = 0");
    }

    private DataMap dataMap(Calibration cal) {//KS

        DataMap dataMap = new DataMap();
        String json = cal.toS();
        Log.d(TAG, "dataMap Calibration GSON: " + json);

        Sensor sensor = Sensor.currentSensor();
        if (sensor != null) {
            dataMap.putLong("timestamp", cal.timestamp);////Long.valueOf("1472590518540").longValue()
            dataMap.putDouble("sensor_age_at_time_of_estimation", cal.sensor_age_at_time_of_estimation);
            //dataMap.putString("sensor", sensor.uuid);//Sensor.currentSensor()
            dataMap.putDouble("bg", cal.bg);
            dataMap.putDouble("raw_value", cal.raw_value);
            dataMap.putDouble("adjusted_raw_value", cal.adjusted_raw_value);
            dataMap.putDouble("sensor_confidence", cal.sensor_confidence);
            dataMap.putDouble("slope_confidence", cal.slope_confidence);
            dataMap.putLong("raw_timestamp", cal.raw_timestamp);//Long.valueOf("1472590518540").longValue()
            dataMap.putDouble("slope", cal.slope);
            dataMap.putDouble("intercept", cal.intercept);
            dataMap.putDouble("distance_from_estimate", cal.distance_from_estimate);
            dataMap.putDouble("estimate_raw_at_time_of_calibration", cal.estimate_raw_at_time_of_calibration);
            dataMap.putDouble("estimate_bg_at_time_of_calibration", cal.estimate_bg_at_time_of_calibration);
            dataMap.putString("uuid", cal.uuid);
            dataMap.putString("sensor_uuid", sensor.uuid);
            dataMap.putBoolean("possible_bad", (cal.possible_bad != null ? cal.possible_bad : true));
            dataMap.putBoolean("check_in", cal.check_in);
            dataMap.putDouble("first_decay", cal.first_decay);
            dataMap.putDouble("second_decay", cal.second_decay);
            dataMap.putDouble("first_slope", cal.first_slope);
            dataMap.putDouble("second_slope", cal.second_slope);
            dataMap.putDouble("first_intercept", cal.first_intercept);
            dataMap.putDouble("second_intercept", cal.second_intercept);
            dataMap.putDouble("first_scale", cal.first_scale);
            dataMap.putDouble("second_scale", cal.second_scale);
        }
        else
            Log.d(TAG, "dataMap Calibration sensor does not exist.");
        return dataMap;
    }

    public long sgvLevel(double sgv_double, SharedPreferences prefs, BgGraphBuilder bgGB) {
        Double highMark = Double.parseDouble(prefs.getString("highValue", "170"));
        Double lowMark = Double.parseDouble(prefs.getString("lowValue", "70"));
        if (bgGB.unitized(sgv_double) >= highMark) {
            return 1;
        } else if (bgGB.unitized(sgv_double) >= lowMark) {
            return 0;
        } else {
            return -1;
        }
    }

    public double inMgdl(double value, SharedPreferences sPrefs) {
        if (!doMgdl(sPrefs)) {
            return value * Constants.MMOLL_TO_MGDL;
        } else {
            return value;
        }

    }

    @Override
    public void onDestroy() {
        if (googleApiClient != null && googleApiClient.isConnected()) {
            googleApiClient.disconnect();
        }
        if (mPrefs != null && mPreferencesListener != null) {
            mPrefs.unregisterOnSharedPreferenceChangeListener(mPreferencesListener);
        }
    }

    @Override
    public void onConnectionSuspended(int cause) {
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
    }
}
