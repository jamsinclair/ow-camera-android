package net.sourceforge.opencamera

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import android.content.Context
import android.util.Log
import android.graphics.Bitmap

class PebbleHelper(private val coroutineScope: CoroutineScope, private val context: Context? = null) {
    companion object {
        private const val TAG = "PebbleHelper"
        private val WATCHAPP_UUID = UUID.fromString("c187457d-3067-4062-8b1a-f8fde467b545")
        private const val KEY_CAPTURE = 1
        private const val KEY_PICTURE_TAKEN = 2
        private const val KEY_PREVIEW_DATA = 3
        private const val KEY_REQUEST_NEXT_FRAME = 4
    }

    private val pebbleStatusToast = ToastBoxer()
    private var pebbleSender: PebbleSender? = null
    private val imageConverter = PebbleImageConverter(context)

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

    fun sendPreviewFrame(bitmap: Bitmap, model: PebbleModel) {
        sendPreviewFrame(bitmap, model, 0, 0)  // Default to BW 1-bit with Floyd-Steinberg
    }

    fun sendPreviewFrame(bitmap: Bitmap, model: PebbleModel, format: Int, ditheringAlgorithm: Int) {
        if (pebbleSender == null) {
            return  // No sender available, skip sending
        }

        // Make a copy of the bitmap because the preview reuses bitmap instances
        // If we don't copy it, by the time the coroutine runs, the bitmap may have been replaced
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val bitmapCopy = bitmap.copy(config, false)

        coroutineScope.launch {
            try {

                val pixelData = imageConverter.convertBitmapToPixelData(bitmapCopy, model, ditheringAlgorithm)

                val dictionary: Map<UInt, PebbleDictionaryItem> = mapOf(
                    KEY_PREVIEW_DATA.toUInt() to PebbleDictionaryItem.ByteArray(pixelData)
                )

                pebbleSender?.sendDataToPebble(WATCHAPP_UUID, dictionary)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending preview frame", e)
            } finally {

                // Clean up the bitmap copy
                bitmapCopy.recycle()
            }
        }
    }
}
