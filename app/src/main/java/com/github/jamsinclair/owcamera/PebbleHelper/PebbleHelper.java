package com.github.jamsinclair.owcamera.PebbleHelper;

import java.util.UUID;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.PebbleKit.PebbleDataReceiver;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.github.jamsinclair.owcamera.MainActivity;
import com.github.jamsinclair.owcamera.MyDebug;
import com.github.jamsinclair.owcamera.ToastBoxer;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.github.jamsinclair.owcamera.R;

public class PebbleHelper {
    private static final String TAG = "PebbleHelper";
    private static final UUID WATCHAPP_UUID = UUID.fromString("c187457d-3067-4062-8b1a-f8fde467b545");

    private static final int KEY_CAPTURE = 1;
    private static final int KEY_PICTURE_TAKEN = 2;


    private Handler handler = new Handler();
    private PebbleDataReceiver appMessageReciever;

    private ToastBoxer pebbleStatusToast = new ToastBoxer();

    public boolean isConnected(android.content.Context context) {
        return PebbleKit.isWatchConnected(context);
    }

    public void onClickPebbleWatchButton(MainActivity main) {
        if( MyDebug.LOG )
            Log.i(TAG, "Opening app on pebble");

        main.getPreview().showToast(pebbleStatusToast, R.string.pebble_app_open);
        PebbleKit.closeAppOnPebble(main, WATCHAPP_UUID);
        PebbleKit.startAppOnPebble(main, WATCHAPP_UUID);
    }

    public void onResume(final MainActivity main) {
        if(appMessageReciever == null) {
            appMessageReciever = new PebbleDataReceiver(WATCHAPP_UUID) {

                @Override
                public void receiveData(Context context, int transactionId, PebbleDictionary data) {
                    // Always ACK
                    PebbleKit.sendAckToPebble(context, transactionId);

                    if(data.getInteger(KEY_CAPTURE) != null) {
                        final int timerDuration = data.getInteger(KEY_CAPTURE).intValue();

                        if( MyDebug.LOG )
                            Log.d(TAG, "App Message Received (KEY_CAPTURE): " + timerDuration);

                        // Update UI on correct thread
                        handler.post(new Runnable() {

                            @Override
                            public void run() {
                                main.takePictureFromRemote(timerDuration);
                            }
                        });
                    }
                }
            };

            // Add AppMessage capabilities
            PebbleKit.registerReceivedDataHandler(main, appMessageReciever);
        }
    }

    public void onPause(MainActivity main) {
        if(appMessageReciever != null) {
            main.unregisterReceiver(appMessageReciever);
            appMessageReciever = null;
        }
    }

    public void onPictureTaken(MainActivity main) {
        PebbleDictionary dict = new PebbleDictionary();

        dict.addInt32(KEY_PICTURE_TAKEN, 1);
        PebbleKit.sendDataToPebble(main, WATCHAPP_UUID, dict);
    }
}
