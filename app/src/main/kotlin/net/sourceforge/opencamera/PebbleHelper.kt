package net.sourceforge.opencamera

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import android.util.Log

class PebbleHelper(private val coroutineScope: CoroutineScope) {
    companion object {
        private const val TAG = "PebbleHelper"
        private val WATCHAPP_UUID = UUID.fromString("c187457d-3067-4062-8b1a-f8fde467b545")
        private const val KEY_CAPTURE = 1
        private const val KEY_PICTURE_TAKEN = 2
    }

    private val pebbleStatusToast = ToastBoxer()
    private var pebbleSender: PebbleSender? = null

    fun onClickPebbleWatchButton(main: MainActivity) {
        if (MyDebug.LOG) {
            Log.i(TAG, "Opening app on pebble")
        }

        main.preview.showToast(pebbleStatusToast, R.string.pebble_app_open)

        if (pebbleSender == null) {
            pebbleSender = DefaultPebbleSender(main)
        }

        coroutineScope.launch {
            try {
                val result = pebbleSender?.startAppOnTheWatch(WATCHAPP_UUID)
                if (MyDebug.LOG) {
                    Log.d(TAG, "App start result: $result")
                }
                if (result == null || result.isEmpty()) {
                    main.preview.showToast(pebbleStatusToast, R.string.pebble_app_open_failed)
                }
            } catch (e: Exception) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "Error starting app on watch", e)
                }
                main.preview.showToast(pebbleStatusToast, R.string.pebble_app_open_failed)
            }
        }
    }

    fun onResume(main: MainActivity) {
        if (pebbleSender == null) {
            pebbleSender = DefaultPebbleSender(main)
        }
    }

    fun onPause(main: MainActivity) {
        // Clean up sender if needed
        pebbleSender?.let {
            coroutineScope.launch {
                try {
                    it.close()
                } catch (e: Exception) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "Error closing sender", e)
                    }
                }
            }
        }
        pebbleSender = null
    }

    fun onPictureTaken(main: MainActivity) {
        if (pebbleSender == null) {
            pebbleSender = DefaultPebbleSender(main)
        }

        coroutineScope.launch {
            try {
                val dictionary = mapOf(
                    KEY_PICTURE_TAKEN.toUInt() to PebbleDictionaryItem.UInt16(1u)
                )
                val result = pebbleSender?.sendDataToPebble(WATCHAPP_UUID, dictionary)
                if (MyDebug.LOG) {
                    Log.d(TAG, "Picture taken notification sent: $result")
                }
            } catch (e: Exception) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "Error sending picture taken notification", e)
                }
            }
        }
    }
}
