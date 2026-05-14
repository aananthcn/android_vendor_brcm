# CLAUDE.md — organic-maps

This file is read automatically by the `claude` CLI (Claude Code) whenever you
run it inside this directory.

---

## Purpose

Pre-built system app that bundles [Organic Maps](https://organicmaps.app) into
the Android 16 AAOS image for the Raspberry Pi 5 (Android HU).

Organic Maps is an open-source, offline-first map and navigation app (fork of
MAPS.ME). It supports Android Auto and works without an active internet
connection — suited for automotive use where connectivity may be intermittent.

---

## AOSP tree placement

```
<AOSP_ROOT>/vendor/brcm/organic-maps/
```

- APK installs to `/system/app/OrganicMaps/OrganicMaps.apk`
- Activated via `PRODUCT_PACKAGES += OrganicMaps` in `device/brcm/rpi5/device.mk`

---

## File map

```
organic-maps/
├── CLAUDE.md                   ← you are here
├── Android.bp                  ← Soong: android_app_import with preprocessed: true
├── fetch_apk.sh                ← downloads APK + extracts liborganicmaps.so (run before building)
├── .gitignore                  ← excludes *.apk and lib/ from git
├── OrganicMaps.apk             ← NOT in git; fetched by fetch_apk.sh
└── lib/arm64/liborganicmaps.so ← NOT in git; extracted from APK by fetch_apk.sh
```

The native library is installed via `cc_prebuilt_library_shared { name: "liborganicmaps" }`
in `Android.bp`, which puts it in `/system/lib64/` — the last entry in the
nativeloader search path for system apps.  `PRODUCT_PACKAGES += liborganicmaps`
in `device/brcm/rpi5/device.mk` pulls it into the build.

**Why `android_app_import` with `preprocessed: true`, not `Android.mk`:**
The old `BUILD_PREBUILT` (Android.mk) path runs compact-dex rewriting on the
APK's embedded dex files even when `LOCAL_NO_ZIPALIGN := true`.  That rewrite
strips the APK Signature Scheme v2 block, causing PackageManager to reject the
package at install time (`INSTALL_PARSE_FAILED_NO_CERTIFICATES`).
`preprocessed: true` in Soong's `android_app_import` copies the APK
byte-for-byte — no zipalign, no dex rewriting, no re-signing — so the v2
block is preserved.

**Missing APK at build time:** if `OrganicMaps.apk` is absent, Soong will
report "OrganicMaps.apk: no such file or directory".  Run `fetch_apk.sh`
before building.

**Why the native library must be pre-extracted:**
The APK stores `liborganicmaps.so` with DEFLATE compression to reduce download
size.  Android's linker cannot mmap a compressed ZIP entry, so it cannot load
the `.so` directly from the APK.  For a system app the `legacyNativeLibraryDir`
points to `/system/app/OrganicMaps/lib` (read-only at runtime), so runtime
extraction via `installd` also never runs.  Result: `UnsatisfiedLinkError` on
every launch.

`fetch_apk.sh` extracts `liborganicmaps.so` from the APK.  The
`cc_prebuilt_library_shared` module in `Android.bp` installs it to
`/system/lib64/liborganicmaps.so`, which is the third entry in the nativeloader
search path for the app.  `PRODUCT_COPY_FILES` was tried first but is banned
by the build system for ELF files ("use cc_prebuilt_library_shared instead").

**Missing native lib at build time:** if `lib/arm64/liborganicmaps.so` is
absent, Soong will fail on the `cc_prebuilt_library_shared` module.  Run
`fetch_apk.sh` — it extracts the lib after downloading and verifying the APK.

---

## First-time setup (after clone)

```bash
vendor/brcm/organic-maps/fetch_apk.sh
```

The script is idempotent — it skips the download if the correct APK is
already present.

## APK update procedure

1. Edit `fetch_apk.sh`: update `TAG`, `BUILD`, and `SHA256` to the new release
   values from https://github.com/organicmaps/organicmaps/releases
2. Run `fetch_apk.sh` to download and verify the new APK.
3. Commit the updated `fetch_apk.sh` and push to `aananthcn/organic-maps`.
4. Update the submodule pointer in `aananthcn/android_vendor_brcm`:
   ```bash
   cd vendor/brcm
   git submodule update --remote organic-maps
   git add organic-maps
   git commit -m "organic-maps: update APK to <version>"
   git push
   ```

---

## Build

```bash
# From AOSP root:
source build/envsetup.sh
lunch <rpi5_car_target>

# Build both OrganicMaps (prebuilt APK) and OrganicMapsShim (source) together:
mmm vendor/brcm/organic-maps

# Output APK location after full build:
# out/target/product/<device>/system/app/OrganicMaps/OrganicMaps.apk
```

## Verify installation on device

```bash
adb shell pm list packages | grep organic
# expected: package:app.organicmaps.web  (web-release APK uses this package ID)

# Correct launch command (package ID differs from the app's namespace):
adb shell am start -n app.organicmaps.web/app.organicmaps.DownloadResourcesActivity
```

---

## Key design decisions

- **PRESIGNED certificate**: the APK keeps the original Organic Maps developer
  signature. Do not re-sign with the platform key — the app is not a platform
  component and does not need signature-level permissions.
- **Not privileged**: installed to `/system/app/`, not `/system/priv-app/`.
  Promote to priv-app only if a specific `signatureOrSystem` permission is
  needed (none identified so far).
- **dex_preopt disabled**: AOT compilation is off until the APK is confirmed
  to run correctly on AAOS/RPi5. Re-enable (`enabled: true`) for production
  builds to reduce app cold-start time.
- **arm64-v8a APK**: RPi5 is arm64. Use the architecture-specific APK (not
  universal) to keep image size smaller.
- **No dependency on rvc-* or vhal-core**: navigation is fully independent of
  the camera pipeline and vehicle signal stack.

---

## Android Auto / AAOS notes

Organic Maps declares Android Auto support in its manifest. On AAOS the app
should appear in the launcher. If it does not:

- Check that `android.hardware.type.automotive` is NOT listed as a required
  feature in the APK manifest (Organic Maps should not require it).
- Verify the app appears under `adb shell dumpsys package app.organicmaps`.
- Check logcat for install or compatibility errors:
  `adb logcat -s PackageManager PackageInstaller`

---

## Map data

Organic Maps downloads map data in-app from its own servers. No map files are
bundled in this APK. Ensure the device has internet access on first launch for
the user to download their region's map. Subsequent use is fully offline.

---

## Common tasks (examples for Claude)

> "Update Organic Maps to the latest version"
→ Follow the APK update procedure above.

> "Enable AOT compilation for production"
→ Set `dex_preopt { enabled: true }` in `Android.bp`.

> "Move to priv-app"
→ Add `privileged: true` to `Android.bp`; move the install path by adding
  `system_ext_specific: true` or keep default and add to
  `PRODUCT_PRIVILEGED_APPS` in `device.mk`.

> "Build Organic Maps from source instead of pre-built"
→ Replace `android_app_import` with `android_app` and add the source tree.
  Organic Maps uses CMake for native code — significant Soong integration work
  required. Create a new CLAUDE.md section documenting the source layout.
