# Architecture — vendor/brcm

Full-system view of the two-node automotive platform built on a pair of
Raspberry Pi 5 boards: one running **Raspberry Pi OS (Linux IC)** and one
running **Android 16 AAOS (Android HU)**.

---

## System Overview

```
 ╔══════════════════════════════════════════════════╗
 ║        Linux IC  (RPi5 — Raspberry Pi OS)        ║
 ║                                                  ║
 ║  /opt/car-ui/bin/vhal-core  ←── gRPC :50051      ║
 ║         ↑ provides vehicle property store        ║
 ║         │                                        ║
 ║  /opt/car-ui/bin/vhal-gateway                    ║
 ║         │ forwards selected props to Android HU  ║
 ║         │ via SetValues gRPC calls               ║
 ║         │                                        ║
 ║  /opt/car-ui/bin/cluster-ui  (Qt6/QML)           ║
 ║         │ gRPC client → vhal-core                ║
 ║         │ renders gauges (speed, rpm, gear …)    ║
 ║         │                                        ║
 ║         │ on GEAR_REVERSE:                       ║
 ║         │   GStreamer RTP/UDP :5004               ║
 ║         │     ←─────────────────────────────┐   ║
 ║         │     H.264 RTP stream               │   ║
 ║         └──── PiP overlay (VideoOutput QML)  │   ║
 ║                                              │   ║
 ╚══════════════════════════════════════════════│═══╝
          Ethernet  192.168.10.x               │
 ╔════════════════════════════════════════════  │  ═╗
 ║       Android HU  (RPi5 — Android 16 AAOS)  │   ║
 ║                                             │   ║
 ║  vhal-core-server  (:50051, 0.0.0.0)        │   ║
 ║    ← receives gateway pushes from Linux IC  │   ║
 ║    ← serves vhal-bridge locally             │   ║
 ║                                             │   ║
 ║  vhal-bridge  (AIDL IVehicle frontend)      │   ║
 ║    connects Android CarService → vhal-core  │   ║
 ║    address: 127.0.0.1:50051                 │   ║
 ║                                             │   ║
 ║  CarEvsService  (AAOS system service)       │   ║
 ║    monitors GEAR_SELECTION via              │   ║
 ║      CarPropertyManager (→ vhal-bridge)     │   ║
 ║    on REVERSE → activates REARVIEW stream   │   ║
 ║    displays via CarEvsCameraPreviewActivity │   ║
 ║                                             │   ║
 ║  rvc-evs-proto  (system priv-app)           │   ║
 ║    CarEvsManager.startVideoStream()  ───────┘   ║
 ║    HardwareBuffer → MediaCodec H.264             ║
 ║    → RTP/UDP → 192.168.10.10:5004               ║
 ║                                                  ║
 ╚══════════════════════════════════════════════════╝
```

---

## Vehicle Signal Flow

```
 [Linux IC]                                  [Android HU]
 vhal-core (:50051)                          vhal-core-server (:50051)
     │                                            ▲
     │  StartPropertyValuesStream (gRPC)          │
     │  (watches GEAR_SELECTION, PERF_VEHICLE_    │
     │   SPEED, ENGINE_RPM, …)                    │
     │                                            │  SetValues (gRPC)
     └───► vhal-gateway ────────────────────────►─┘
              reads gateway-configs.json
              /opt/car-ui/etc/vhal/
              forwards only configured property set

 cluster-ui ──► GetValues / StartPropertyValuesStream ──► vhal-core (IC-local)
```

**Domain isolation**: the only cross-domain channel is `vhal-gateway` running
on the Linux IC. Android VHAL clients (CarService etc.) connect only to the
local `vhal-core-server` — they are invisible to the Linux IC domain and vice
versa.

---

## Rear View Camera Pipeline

### Current state (rvc-service + rvc-app) — to be replaced

```
 Android HU:
   rvc_service  (vendor/bin)
     │  subscribes to GEAR_SELECTION via AIDL IVehicle
     │  on REVERSE: setprop vendor.rvc.camera.active 1
     ▼
   rvc_app  (system/bin)
     │  watches vendor.rvc.camera.active
     │  Camera2 NDK → AImageReader (YUV_420_888)
     │  AMediaCodec H.264 (buffer-input, CPU copy)
     │  RtpStreamer UDP → 192.168.10.10:5004
     ▼
 Linux IC:
   cluster-ui  GStreamer RTP receiver → PiP overlay
```

**Problem**: `rvc_service` duplicates what `CarEvsService` already does.
When `CarEvsService` auto-activates the camera on REVERSE, it conflicts
with `rvc_app`'s Camera2 NDK session — only one can own the camera.

### Target state (rvc-evs-proto) — in progress

```
 Android HU:
   CarEvsService  (system — already present)
     │  monitors GEAR_SELECTION via CarPropertyManager
     │  on REVERSE: opens EVS HAL camera
     │  CarEvsCameraPreviewActivity shows full-screen feed
     │
     ├──► rvc-evs-proto  (system priv-app)
     │      CarEvsManager.startVideoStream(REARVIEW)
     │      HardwareBuffer frames (GPU memory)
     │      MediaCodec (surface-input mode, zero-copy)
     │      H.264 encoded stream
     │      RtpStreamer UDP → 192.168.10.10:5004
     ▼
 Linux IC:
   cluster-ui  GStreamer RTP receiver → PiP overlay
```

**Benefits of target state**:
- No camera conflict: one EVS HAL owner, multiple consumers via CarEvsService
- `rvc_service` deleted: CarEvsService already handles gear detection
- Zero-copy GPU path: HardwareBuffer → MediaCodec surface input (no CPU YUV copy)
- 1 project instead of 3 (`rvc-service` + `rvc-app` → `rvc-evs-proto`)

---

## Module Inventory

| Module | Partition | Status | Purpose |
|--------|-----------|--------|---------|
| `vhal-core` | vendor → `/vendor/bin/` | Active | gRPC VHAL server on Android HU |
| `vhal-bridge` | vendor → `/vendor/bin/hw/` | Active | AIDL IVehicle frontend for Android |
| `rvc-service` | vendor → `/vendor/bin/` | **Deprecated** | Gear monitor (replaced by CarEvsService) |
| `rvc-app` | system → `/system/bin/` | **Deprecated** | Camera2 NDK streamer (replaced by rvc-evs-proto) |
| `rvc-evs-proto` | system → `/system/priv-app/` | **In progress** | EVS-based prototype / future replacement |

---

## Network Map

| Link | Protocol | Address | Port |
|------|----------|---------|------|
| vhal-gateway → Android HU vhal-core-server | gRPC | 192.168.10.20:50051 | 50051 |
| cluster-ui → Linux IC vhal-core | gRPC | 127.0.0.1:50051 | 50051 |
| rvc-evs-proto → cluster-ui RVC | RTP/UDP | 192.168.10.10 | 5004 |
| vhal-bridge → local vhal-core-server | gRPC | 127.0.0.1:50051 | 50051 |

---

## Key Design Decisions

1. **Domain isolation**: each physical node runs its own `vhal-core` server.
   Cross-domain property sharing is only via `vhal-gateway` on the Linux IC —
   Android clients never connect to the Linux IC's VHAL directly.

2. **EVS as the single camera owner**: `CarEvsService` owns the camera HAL.
   No Camera2 NDK client should open the same camera independently — that
   causes session preemption. All camera consumers go through EVS.

3. **RTP for IC→HU video**: the Linux IC receives the camera stream as H.264
   over RTP/UDP. GStreamer on the IC side decodes it into a QML `VideoOutput`
   PiP overlay. No RTSP or WebRTC — plain RTP keeps latency minimal.

4. **vhal-gateway as the sole cross-domain channel**: property forwarding is
   explicit and configured (gateway-configs.json). Only listed properties
   cross the domain boundary — no implicit mirroring.
