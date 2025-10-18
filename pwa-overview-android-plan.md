# Car Scanner PWA Overview and Android Implementation Plan

## Current PWA Functionality

### User Interface and Workflow

- The UI is delivered as a single page (`index.html:1-51`) with a status banner, connect button, diagnostic command shortcuts, a free-form message input, and a scrolling log window.
- SOC telemetry is visualised in an inline SVG graph; the live clock, last reading time, and latest SOC raw value are surfaced in the banner (`index.html:17-48`).

### BLE Connection Lifecycle

- `BLEManager` orchestrates device discovery, GATT connection, and service enumeration (`script.js:332-429`). Discovery is restricted to devices advertising the name `IOS-Vlink`, and optional services include the vendor-specific primary service plus standard Device Information, Generic Attribute, Generic Access, and an undocumented service (`script.js:338-356`).
- After service enumeration the code aggregates every characteristic and selects the first write-capable characteristic as the active writer (`script.js:392-420`). Characteristics from the primary service that expose notifications are subscribed to so incoming frames trigger `handleCharacteristicValueChanged` (`script.js:401-410`).
- Connection progress and failure modes are surfaced via the `connectionStatus` label; the connect button is hidden once the link is marked ready (`script.js:263-266`).

### Command Queue, Polling, and Responses

- A reset queue issues a fixed sequence of ELM327-style AT commands (`resetQueue` in `script.js:252`) to normalise the dongle. `executeMessageAwaitResponse` sends each command sequentially, waiting for a callback before advancing (`script.js:260-276`).
- Once initialisation completes the app polls SOC (`22B046`) immediately and then every 10 seconds (`script.js:263-270`). Button shortcuts dispatch additional PIDs on demand (`script.js:77-93`), and the text box allows arbitrary frames with basic validation (`script.js:288-309`).
- Incoming values are decoded as ASCII, stripped of carriage returns, and routed through `parseMessage` (`script.js:318-328`). The handler suppresses empty prompts (`>`), advances the reset queue after a short delay, and logs each payload (`script.js:319-327`).

### Data Processing and Visualisation

- SOC responses are coerced from hex into raw counts, converted into multiple percentage estimates, and rendered on the graph, while also feeding a predictive linear regression that estimates time-to-empty (`script.js:151-170` and `script.js:21-55`).
- Additional PID handlers compute state-of-health, voltage, current, and battery temperature using calibration formulas (`script.js:174-197`).
- The log window retains the latest 100 entries via the helper logger `l` (`script.js:310-313`).

### PWA Capabilities

- A service worker precaches the core assets and provides cache-first networking with online/offline notifications broadcast to open clients (`service-worker.js:1-123`).
- New service-worker versions aggressively purge stale caches during activation (`service-worker.js:41-55`) and skip the waiting phase to take control immediately (`service-worker.js:31-38`).

### Utilities and Test Harness

- A developer-only `test()` shim can stub the Web Bluetooth API, inject synthetic characteristics, and emit mock SOC data for UI testing (`script.js:489-554`).
- Version metadata is surfaced in the UI banner (`script.js:13-68`) to help track deployed builds.

## OBD Command Set in Use

- Initialisation: `ATZ`, `ATD`, `ATE0`, `ATS0`, `ATH0`, `ATL0` (`script.js:252`).
- Polling: `22B046` for SOC (`script.js:263-270`).
- On-demand diagnostics: `22B061` (SOH), `22B042` (voltage), `22B043` (current), `22B056` (battery temperature) (`script.js:77-93`).
- Additional PIDs and ECU routing hints are documented in inline comments for future expansion (`script.js:198-487`).

## Current Limitations and Observations

- The device discovery filter is hard-coded to a single advertised name, blocking alternative dongles without code changes (`script.js:338-339`).
- Writer characteristic selection assumes the first write-capable characteristic is correct; no UI exists to resolve multiple possibilities or handle missing permissions (`script.js:392-420`).
- SOC history and predictions live only in memory—refreshing the page or going offline discards accumulated data (`script.js:21-170`).
- Service-worker connectivity messages are broadcast but the main UI does not surface them to users (`service-worker.js:16-28`).
- Error handling is console-oriented; there is no retry back-off, connection watchdog, or explicit teardown when the device disconnects (`script.js:332-429`).

## Android Implementation Plan

### Goals

- Deliver feature parity with the PWA: connect to the `IOS-Vlink` dongle over BLE, run the same AT/PID command sequences, stream and parse responses, visualise SOC history with depletion forecasting, expose quick diagnostic queries, and provide a manual command console.
- Preserve offline resilience by caching essential assets/data locally and informing users of connectivity issues.

### Architectural Outline

- **Presentation layer:** Jetpack Compose (or native Views) for screens covering connection, live telemetry, historical chart, diagnostics, and console.
- **Domain layer:** Use Kotlin coroutines/Flow to model connection state, command queue, telemetry stream, and prediction outputs.
- **Data layer:** Repository encapsulating BLE I/O, response parsing, and local persistence (Room or DataStore) for SOC history and logs.
- **BLE stack:** Leverage `BluetoothLeScanner` for discovery and `BluetoothGatt` for connections, encapsulated in a service or foreground `ViewModel`-hosted component. Map services/characteristics by UUID to mirror `services.main` and related groups (`script.js:278-297`, `script.js:332-420`).
- **Background execution:** Foreground service or WorkManager task to maintain polling when the app is backgrounded, with user notification for ongoing BLE access.

### Implementation Phases

1. **Project foundation:** Configure Android project (min SDK, permissions, dependency graph), set up dependency injection, and add basic navigation scaffolding.
2. **BLE discovery and connection:** Implement permission handling (Bluetooth, location on pre-Android 12, `BLUETOOTH_CONNECT/SCAN` on Android 12+), run filtered scans for `IOS-Vlink`, connect, and enumerate services/characteristics. Surface connection states in UI.
3. **Command queue engine:** Port the reset queue and polling cadence, ensuring writes use `BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE` where supported. Implement response buffering to accumulate full frames similar to `handleCharacteristicValueChanged` (`script.js:318-329`).
4. **Parser and telemetry models:** Recreate `parseMessage`, conversion helpers, and prediction logic in Kotlin, storing structured readings and computed percentages (`script.js:95-197`).
5. **Data storage and logging:** Persist SOC samples, diagnostic readings, and console history locally for replay after app restarts. Consider bounded history with pruning similar to the web log’s 100-entry cap.
6. **UI build-out:** Compose-based charts for SOC history/prediction, control buttons, and console. Integrate state flows for real-time updates.
7. **Offline and lifecycle management:** Handle OS-level Bluetooth disconnects, app backgrounding, and airplane mode. Provide user notifications mirroring service-worker status changes.
8. **Testing and QA:** Create instrumentation tests with mocked `BluetoothGatt` layers, plus unit tests for parsing, prediction, and repositories. Add end-to-end session recording for regression coverage.

### Feature Parity Checklist

- Command library aligns with existing AT/PID sequence.
- Polling interval is configurable and defaults to 10 seconds.
- SOC prediction renders in UI and shows estimated depletion time.
- Manual command console writes to the active characteristic and reports parsed responses.
- Offline/connection status changes are clearly surfaced to the user.

### Additional Considerations

- Consider exporting log/SOC history for diagnostics and supporting speech output (commented in the web app, `script.js:171`).
- Plan for Play Store requirements: background location (if used), Bluetooth permissions justification, privacy policy detailing on-vehicle data handling.

## Open Questions

Q: Should the Android app support additional PIDs or custom command profiles beyond the current hard-coded set?
A: NOT REQUIRED for MVP.
Q: What persistence window is required for SOC history (session-only vs. multi-day)?
A: Session-only is sufficient for MVP.
Q: Is background polling necessary when the app is not foregrounded, or can it be user-triggered?
A: Background polling is preferred for continuity, with user notification.
