package net.sourceforge.opencamera.PebbleHelper;

import com.getpebble.android.kit.PebbleKit;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.ToastBoxer;

import net.sourceforge.opencamera.R;

public class PebbleHelper {
	private ToastBoxer pebbleStatusToast = new ToastBoxer();

	public boolean isConnected(android.content.Context context) {
		return PebbleKit.isWatchConnected(context);
	}

	public void onClickPebbleWatchButton(MainActivity main) {
		main.getPreview().showToast(pebbleStatusToast, R.string.pebble_app_open);
	}
}
