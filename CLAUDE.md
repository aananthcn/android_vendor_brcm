# CLAUDE.md — vendor/brcm

This file is read automatically by the `claude` CLI (Claude Code) whenever you
run it inside this directory. It provides the top-level context for all modules
under `vendor/brcm` in the AOSP tree.

---

## Repository Purpose

This vendor overlay adds automotive features to **Android 16 AAOS** running on
a **Raspberry Pi 5 (Android HU)**, paired with a second RPi5 running
**Raspberry Pi OS (Linux IC)** as an Instrument Cluster.

The modules here are not replacements for AOSP platform code — they extend the
platform with hardware-specific drivers, HAL adapters, and automotive apps.

Refer to `ARCHITECTURE.md` for the full two-node system diagram and data-flow.

---

## Feature Inventory

| Directory | Type | Status | Purpose |
|-----------|------|--------|---------|
| `vhal-core/` | Submodule | Active | gRPC-based VHAL server + AIDL bridge for Android HU; Linux CMake build for IC |
| `rvc-service/` | Native service | Deprecated | Gear monitor via VHAL (superseded by CarEvsService) |
| `rvc-app/` | Native binary | Deprecated | Camera2 NDK + RTP streamer (superseded by rvc-evs-shim) |
| `rvc-evs-shim/` | System priv-app | In progress | EVS-based RVC prototype: zero-copy GPU path, single camera owner |
| `organic-maps/` | System app | **Planned** | Offline navigation app for the Android HU (see below) |
| `rpi4/` | Build overlay | Active | Board-specific `.mk` and `Android.bp` for RPi4 |
| `rpi5/` | Build overlay | Active | Board-specific `.mk` and `Android.bp` for RPi5 |

---

## Planned Feature: Organic Maps

**Goal**: Integrate [Organic Maps](https://organicmaps.app) as a pre-installed
offline navigation app on the Android HU.

Organic Maps is an open-source, offline-first map and navigation app (fork of
MAPS.ME). It works without an active internet connection, which suits the
automotive context where connectivity may be intermittent.

### Integration approach (to be decided)

Two options are under consideration:

1. **Pre-built APK as a system app** — bundle the release APK from the
   Organic Maps GitHub releases into `organic-maps/` and install it via
   `PRODUCT_PACKAGES` + `Android.bp`. Lowest effort; no source build needed.

2. **Source build integrated into AOSP** — clone the Organic Maps Android
   source as a submodule and build it with Soong. More control over signing,
   permissions, and AAOS-specific adjustments (e.g., distraction-optimised UI).

### Expected directory layout (once implemented)

```
organic-maps/
├── CLAUDE.md             ← module-level context for Claude
├── Android.bp            ← Soong build: android_app_import or android_app
├── app/                  ← APK binary (option 1) or source tree (option 2)
└── README.md
```

### Key integration points

- Install partition: `/system/app/` (system) or `/system/priv-app/` (priv-app
  if it needs signature-level permissions such as `INSTALL_PACKAGES`).
- Maps data: large offline map files ship separately; an OTA or sdcard path
  will be documented once the data strategy is decided.
- No dependency on `rvc-*` or `vhal-core` — navigation is independent of the
  camera and vehicle signal stack.

---

## Coding and build conventions

- **Build system**: Soong (`Android.bp`) for everything that lands on the
  Android HU. CMake + Conan for Linux IC components inside `vhal-core/`.
- **Native code**: C++17 with AOSP logging (`ALOGI`/`ALOGE`), no exceptions,
  no `printf`/`std::cout`.
- **Partitions**: respect the system/vendor split. Camera NDK libraries live in
  the system namespace — binaries that use them must NOT carry `vendor: true`.
- **Submodules**: `vhal-core` is a git submodule. Run
  `git submodule update --init` after cloning.
- **Per-module CLAUDE.md**: each non-trivial subdirectory has its own
  `CLAUDE.md` with module-specific context. Read it before editing that module.

---

## Common entry points for Claude

> "Add Organic Maps as a system app"
→ Create `organic-maps/Android.bp` and place the APK under `organic-maps/app/`.
  Add `PRODUCT_PACKAGES += OrganicMaps` to the device `.mk`.

> "Show me the VHAL architecture"
→ Read `vhal-core/CLAUDE.md` and `ARCHITECTURE.md`.

> "What is the rear-view camera status?"
→ Read `ARCHITECTURE.md` §Rear View Camera Pipeline; active work is in
  `rvc-evs-shim/`.

> "Build everything for RPi5"
→ `source build/envsetup.sh && lunch <rpi5_target> && mmm vendor/brcm`
