# Clean TV Home

A small Apple-TV-style launcher for Android TV. Plain Android views, no external libraries, about 1 GB RAM friendly.

## Change the dock

Edit `app/src/main/res/values/config.xml`. `dock_apps` takes 4 to 7 package names, in order. The grid below uses the same number of columns. Packages that are not installed are skipped. `hidden_apps` removes apps from the grid.

After editing, commit the change on GitHub; the Actions tab rebuilds the APK, then install it again with `adb install -r app-debug.apk`.

## Keys and pills

- Up from the dock goes to the pills (Inputs, Wi-Fi, Settings); the ends of the pill row do not fall through.
- Inputs lists Live TV and the HDMI/AV ports through the TV input framework. If Live TV does nothing on your set, tell me.
- The Wi-Fi pill shows the network name only if you allow the one-time location prompt (Android hides the name without it). Otherwise it shows "Wi-Fi".

## Wallpaper

Background is pure black on purpose. To change it, edit `windowBackground` in `res/values/styles.xml` and `Color.BLACK` in `MainActivity.java`.

## Undo

Run `undo_launcher.bat` (re-enables the stock launcher and makes it Home), or in Settings pick the stock launcher. `adb uninstall com.cleantv.launcher` removes this app.
