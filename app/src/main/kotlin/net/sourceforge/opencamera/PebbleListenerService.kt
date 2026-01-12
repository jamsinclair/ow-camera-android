package net.sourceforge.opencamera

import java.util.UUID
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.common.model.ReceiveResult
import android.content.Intent
import android.util.Log

class PebbleListenerService : BasePebbleListenerService() {
    companion object {
        private const val TAG = "PebbleListenerService"
        private const val KEY_CAPTURE = 1
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier
    ): ReceiveResult {
        if (MyDebug.LOG) {
            Log.d(TAG, "Message received from watch app: $watchappUUID")
        }

        val captureItem = data[KEY_CAPTURE.toUInt()]
        if (captureItem != null) {
            try {
                val timerDuration = when (captureItem) {
                    is PebbleDictionaryItem.UInt8 -> captureItem.value.toInt()
                    is PebbleDictionaryItem.UInt16 -> captureItem.value.toInt()
                    is PebbleDictionaryItem.UInt32 -> captureItem.value.toInt()
                    is PebbleDictionaryItem.Int8 -> captureItem.value.toInt()
                    is PebbleDictionaryItem.Int16 -> captureItem.value.toInt()
                    is PebbleDictionaryItem.Int32 -> captureItem.value
                    else -> 0
                }

                if (MyDebug.LOG) {
                    Log.d(TAG, "App Message Received (KEY_CAPTURE): $timerDuration")
                }

                // Send broadcast to MainActivity with the timer duration
                val intent = Intent(MainActivity.ACTION_PEBBLE_CAPTURE).apply {
                    putExtra(MainActivity.EXTRA_TIMER_DURATION, timerDuration)
                    setPackage(baseContext.packageName)
                }
                if (MyDebug.LOG) {
                    Log.d(TAG, "Sending broadcast with action: ${intent.action}, timerDuration: $timerDuration")
                }
                sendBroadcast(intent)

            } catch (e: Exception) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "Error processing capture message", e)
                }
            }
        }

        return ReceiveResult.Ack
    }
}
