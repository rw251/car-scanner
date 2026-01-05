# PleaseCharge Android & Android Auto Architecture

This project ships two surfaces on top of one shared core:

- **Phone app (MainActivity)** – full-screen map + stats with controls for viewing/exporting data.
- **Android Auto app (SocDisplayScreen/PleaseChargeSession)** – in-car map renderer with side panel stats.
- **Foreground service (BleForegroundService)** – keeps BLE + GPS alive and logs data even when no UI is visible.

The key idea: a single BLE connection (managed by `BleConnectionManager`/`BleObdManager`) and a shared location tracker feed both UIs and the background logger.

## Core modules (phone + auto + service)

- `BleObdManager.kt` — BLE/OBD state machine: scan → connect → discover → configure → READY. Parses SOC/temp responses, handles retries/backoff, and polls periodically.
- `BleConnectionManager.kt` — Singleton that owns a shared `BleObdManager` and fans out events to multiple listeners (phone UI, Auto UI, service).
- `CommonBleListener.kt` — Shared listener wrapper that normalizes logging/formatting for BLE events while delegating UI/service-specific callbacks.
- `LocationTracker.kt` — Shared fused-location wrapper with smoothing/filtering, dynamic GPS frequency, and cumulative trip metrics.
- `DataCapture.kt` — Background CSV logger for SOC/temp/location metrics with deduplication and log rotation.
- `AppLogger.kt` — Central logging to Logcat + file + in-memory buffer with rotation support.
- `ServiceStatus.kt` — `StateFlow` bridge for service status/timeout countdown between the foreground service and the phone UI.

## Phone app (UI)

- `MainActivity.kt` — Full-screen OSM map, bottom-sheet stats, permission handling, BLE lifecycle start, log export. Subscribes to ViewModel and service status.
- `MainViewModel.kt` — Holds UI `StateFlow`s for BLE state, GPS status, distance/avg speed, service timeout, and SOC display text.
- `LogViewerActivity.kt` — Simple log viewer/clearer for the on-device log file.
- Layouts: `activity_main.xml` (map + bottom sheet), `activity_log_viewer.xml` (log viewer).

## Foreground service

- `BleForegroundService.kt` — Starts BLE manager and `LocationTracker` in the background, logs metrics to CSV, manages auto-shutdown timeout, and preloads map tiles.

## Android Auto app

- `PleaseChargeCarAppService.kt` — Entry point for the car app; resets service state and starts the foreground service so GPS/BLE run even without the phone UI.
- `PleaseChargeSession` (in same file) — Creates the main screen and registers the surface renderer.
- `SocDisplayScreen.kt` — NavigationTemplate-based screen that shows SOC/temp/distance/avg speed; hooks into shared BLE/location streams and drives the renderer panel state.
- `SimpleMapRenderer.kt` — Low-level surface renderer drawing OSM tiles + location dot and panel overlay.
- `TileCache.kt` — Shared in-memory cache/downloader for OSM tiles; supports prefetching and background preload.

## Data flow summary

1. **BLE**: `BleForegroundService` starts → `BleConnectionManager` creates `BleObdManager` → events fan out via `CommonBleListener` to phone UI and car UI; service logs SOC/temp to CSV.
2. **Location**: `BleForegroundService` (and optionally phone UI) start `LocationTracker` → emits metrics → phone UI updates map/stats; car UI updates `SimpleMapRenderer`; service logs to CSV and preloads tiles.
3. **Status sharing**: `ServiceStatus` flows expose service running/timeout info to the phone UI for display.
4. **Logging/Export**: `AppLogger` writes logs; `DataCapture` writes CSV; `MainActivity` shares/export files; `LogViewerActivity` reads logs.

## Notes

- All BLE consumers attach via `BleConnectionManager` so connection state is unified.
- The shared foreground service keeps data collection alive when screens are off; UIs consume the same streams when active.
