# OW Camera 2 for Pebble

OW Camera 2 for Pebble is a fork of the Open Camera app, which specfically adds support for remote shutter control via Pebble smartwatches. This the successor to my previous OW Camera for Pebble app, which is no longer available on the Google Play Store. It is using the latest Pebblekit SDK that is still a work-in-progress, [pebble-dev/PebbleKitAndroid2](https://github.com/pebble-dev/PebbleKitAndroid2).

The rest of the app is mostly unchanged from Open Camera, with additional background logic for sending preview images and some minor tweaks to the user interface and strings.

There are three branches I am maintaining:
- [master](https://github.com/jamsinclair/ow-camera-android/tree/master): Unchanged and will be kept in sync with Open Camera releases.
- [development](https://github.com/jamsinclair/ow-camera-android/tree/development): Adds support for Pebble remote shutter control without modifying the original namespace.
- [release](https://github.com/jamsinclair/ow-camera-android/tree/release): Renames the namespace to `com.github.jamsinclair.owcamera2` and should be used for any builds intended for release.

## License

This project is licensed under the GNU General Public License v3.0 as per the original Open Camera project. See [gpl-3.0.txt](gpl-3.0.txt) for details.

## References
- [Open Camera Website and Source](https://opencamera.org.uk/)
- [Pebble Smartwatch Developer Resources](https://developer.repebble.com/)
