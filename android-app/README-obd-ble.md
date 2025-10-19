Bluetooth OBD over BLE: design notes

Overview

- Target device name: "IOS-Vlink"
- Main BLE service UUID: e7810a71-73ae-499d-8c15-faa9aef0c3f2
- Also discover standard services: Device Info (0x180A), GATT (0x1801), GAP (0x1800)
- One writable characteristic (UART-like), one notifying characteristic (in main service)

Transport

- Send ASCII commands terminated with CR ("\r")
- Use write without response if supported
- Enable notifications via CCCD (0x2902)

Init sequence (AT commands)

1. ATZ
2. ATD
3. ATE0
4. ATS0
5. ATH0
6. ATL0

Application queries (polled)

- 22B046 (SOC) every 30s
- 22B056 (Battery temp) after SOC

Response parsing

- Successful response: starts with "62" + same DID, followed by 2 data bytes (A,B)
- Parse A,B as hex -> raw = A\*256+B
- SOC: raw/9.3, raw/9.5, raw/9.7 (% variants); UI uses ~9.5
- Temp: (A/2)-40 (if raw != 0)
- Other (available but not polled):
  - 22B061 -> SOH = raw/100
  - 22B042 -> Voltage = raw/4
  - 22B043 -> Current = (raw-40000)\*0.025

State

- Idle -> Scanning -> Connecting -> Discovering -> Configuring (AT queue) -> Ready
- Auto-reconnect on drop with small backoff

Android notes

- Android 12+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT runtime permissions
- <12: BLUETOOTH, BLUETOOTH_ADMIN (+ sometimes ACCESS_FINE_LOCATION)
- Use BluetoothLeScanner with ScanFilters (name or service UUID)
- Enable notifications: setCharacteristicNotification + write CCCD
