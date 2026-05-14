#!/bin/bash
# Downloads the pinned Organic Maps release APK and verifies its checksum.
# Run this once before building: vendor/brcm/organic-maps/fetch_apk.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="$SCRIPT_DIR/OrganicMaps.apk"

TAG="2026.04.07-8-android"
BUILD="26040708"
URL="https://github.com/organicmaps/organicmaps/releases/download/${TAG}/OrganicMaps-${BUILD}-web-release.apk"
SHA256="d54a70b5764154b0663dc6dcd1a0768d24b7801a7d5e24f5503ef9a5e278689b"

if [[ -f "$APK" ]]; then
    echo "Verifying existing OrganicMaps.apk..."
    if echo "${SHA256}  ${APK}" | sha256sum -c --quiet 2>/dev/null; then
        echo "OrganicMaps.apk is up-to-date. Nothing to do."
        exit 0
    fi
    echo "Checksum mismatch — re-downloading."
    rm -f "$APK"
fi

echo "Downloading Organic Maps ${TAG}..."
wget -q --show-progress -O "$APK" "$URL"

echo "Verifying checksum..."
if ! echo "${SHA256}  ${APK}" | sha256sum -c --quiet; then
    echo "ERROR: checksum verification failed. Deleting corrupt download." >&2
    rm -f "$APK"
    exit 1
fi

echo "Done: $(du -h "$APK" | cut -f1) OrganicMaps.apk"

# Pre-extract the native library so the AOSP build can install it to
# /system/app/OrganicMaps/lib/arm64/liborganicmaps.so.
#
# Why: the APK stores liborganicmaps.so with DEFLATE compression to reduce
# download size.  Android's linker cannot mmap a compressed .so directly from
# a ZIP entry (requires ZIP_STORED).  For a system app the legacyNativeLibraryDir
# points into the read-only system partition, so runtime extraction never runs.
# We pre-extract here so the build system can place the .so at the path that
# nativeloader already adds to the linker search path on the device.
LIB_DIR="$SCRIPT_DIR/lib/arm64"
LIB="$LIB_DIR/liborganicmaps.so"
mkdir -p "$LIB_DIR"
echo "Extracting lib/arm64-v8a/liborganicmaps.so ..."
unzip -p "$APK" lib/arm64-v8a/liborganicmaps.so > "$LIB"
echo "Extracted: $(du -h "$LIB" | cut -f1) liborganicmaps.so"
