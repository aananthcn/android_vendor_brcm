# organic-maps — Offline Navigation for Android HU

Pre-built system app that bundles [Organic Maps](https://organicmaps.app) into
the Android 16 AAOS image for the Raspberry Pi 5 (Android HU). Organic Maps
is an open-source, offline-first map and navigation app with Android Auto
support, suited for automotive use where connectivity may be intermittent.

- **Package**: `app.organicmaps`
- **Shim package**: `com.brcm.mapsshim` — registers `APP_MAPS` intent, forwards to Organic Maps
- **Install paths**: `/system/app/OrganicMaps/` and `/system/app/OrganicMapsShim/`
- **APK**: pre-built upstream release — not stored in git (see setup below)

---

## First-time setup

The APK is excluded from git. Fetch it once after cloning:

```bash
vendor/brcm/organic-maps/fetch_apk.sh
```

The script downloads the pinned release, verifies its SHA-256 checksum, and
is idempotent — safe to run again if already present.

To upgrade to a newer release, update the three variables at the top of
`fetch_apk.sh` (`TAG`, `BUILD`, `SHA256`) and re-run the script.

---

## Build

```bash
# From the AOSP root:
source build/envsetup.sh
lunch aosp_rpi5_car-bp4a-userdebug

# Build this module only (fast iteration):
mmm vendor/brcm/organic-maps

# Output:
# out/target/product/<device>/system/app/OrganicMaps/OrganicMaps.apk
# out/target/product/<device>/system/app/OrganicMapsShim/OrganicMapsShim.apk
```

If the APK is missing, the build will stop immediately with:
```
error: OrganicMaps.apk not found. Run: vendor/brcm/organic-maps/fetch_apk.sh
```

---

## Deploy without reflashing

Both `OrganicMaps` (prebuilt APK) and `OrganicMapsShim` (AAOS launcher shim)
must be pushed together. The shim is what makes the car launcher show Organic
Maps instead of the "No maps application installed" placeholder.

```bash
adb root
adb shell mount -o remount,rw /

# OrganicMaps prebuilt APK
adb shell mkdir -p /system/app/OrganicMaps
adb push out/target/product/rpi5/system/app/OrganicMaps/OrganicMaps.apk /system/app/OrganicMaps/OrganicMaps.apk

# OrganicMapsShim — push the APK file only
adb shell mkdir -p /system/app/OrganicMapsShim
adb push out/target/product/rpi5/system/app/OrganicMapsShim/OrganicMapsShim.apk /system/app/OrganicMapsShim/OrganicMapsShim.apk

# Reboot so PackageManager picks up both new apps
adb reboot
```

A reboot is required after pushing — PackageManager scans `/system/app` at
boot and will not recognise newly pushed APKs at runtime without it.

---

## Verify installation

```bash
# Confirm both packages are installed
adb shell pm list packages | grep -E "organic|mapsshim"
# expected:
#   package:app.organicmaps
#   package:com.brcm.mapsshim

# Check install paths
adb shell pm path app.organicmaps
# expected: package:/system/app/OrganicMaps/OrganicMaps.apk
adb shell pm path com.brcm.mapsshim
# expected: package:/system/app/OrganicMapsShim/OrganicMapsShim.apk

# Confirm the shim wins the APP_MAPS intent (shim priority 0 beats placeholder -1000)
adb shell cmd package query-activities \
    -a android.intent.action.MAIN -c android.intent.category.APP_MAPS
# expected: com.brcm.mapsshim/.MapsShimActivity at priority=0

# Launch Organic Maps directly from the shell
adb shell am start -n app.organicmaps/.MwmActivity
```

---

## Debug

### Logcat

```bash
# All Organic Maps output
adb logcat -s MapsApp JNIJNI omim drape_engine routing

# PackageManager install / compatibility errors
adb logcat -s PackageManager PackageInstaller

# App crash (captures app process logs)
adb logcat --pid=$(adb shell pidof app.organicmaps)
```

### App does not appear in the launcher

```bash
# Check if AAOS declared the package as disabled
adb shell pm list packages -d | grep organic

# Check for missing feature declarations blocking install
adb shell dumpsys package app.organicmaps | grep -A5 "requested permissions"

# Verify the APK architecture matches the device (arm64)
adb shell dumpsys package app.organicmaps | grep primaryCpuAbi
# expected: primaryCpuAbi=arm64-v8a
```

### Map data

Organic Maps downloads map tiles in-app on first launch. Ensure the device
has internet access when the user first opens the app to select and download
their region. All subsequent navigation is fully offline.

To pre-seed maps without in-app download, place `.mwm` map files in:
```
/sdcard/Android/data/app.organicmaps/files/
```

---

## Troubleshooting

| Symptom | Likely cause and fix |
|---------|----------------------|
| "No maps application installed" after push | `OrganicMapsShim` was not pushed — push both dirs and reboot |
| `pm list packages` missing `app.organicmaps` | APK rejected by PM — check logcat: `adb logcat -d -s PackageManager \| grep -i organic` |
| `pm list packages` missing `com.brcm.mapsshim` | Shim dir not pushed — run the full deploy block above |
| `APP_MAPS` query still returns placeholder | Shim not installed; verify with `pm path com.brcm.mapsshim` |
| App not in launcher after push | Reboot missing — PackageManager needs boot scan to register `/system/app` installs |
| `primaryCpuAbi=null` in dumpsys | APK does not contain arm64 native libs — verify the APK is the correct variant |
| App crashes on start | Check logcat for missing permissions; location permission may need a grant: `adb shell pm grant app.organicmaps android.permission.ACCESS_FINE_LOCATION` |
| Build error: APK not found | Run `vendor/brcm/organic-maps/fetch_apk.sh` |
| SHA256 mismatch in fetch script | Upstream release file changed — re-check the checksum on the GitHub releases page and update `fetch_apk.sh` |
| App visible but Android Auto not working | Organic Maps requires Android Auto companion app on the phone; on native AAOS it appears directly in the car launcher |
