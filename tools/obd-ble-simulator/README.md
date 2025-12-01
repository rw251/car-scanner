# OBD BLE Simulator

A Bluetooth Low Energy OBD-II simulator that emulates an IOS-Vlink (ELM327-compatible) device for testing the car-scanner PWA and Android app. Uses BlueZ D-Bus API to provide a BLE GATT server that simulates a 57kWh MG electric vehicle battery with realistic driving physics.

## Quick start

1. Switch to su then:

```bash
cd tools/obd-ble-simulator
npm start
```

Should end with green ticks and "Advertisement registered" message.

2. Navigate to https://car.rw251.com/ on your phone

3. Click "Connect BLE"

4. Select "IOS-Vlink" from the device list and click "Pair"

5. The PWA will show a connection sequence. You'll very likely also see a pairing request from Android - click cancel - then refresh the PWA and connect again. I think the simulator is also showing a non-BLE bluetooth thing and you need to cancel this first time, then refresh so that the next connection is pure BLE and not paired.

## Features

- **D-Bus Integration**: Works alongside system BlueZ daemon (no need to stop Bluetooth)
- **Realistic Vehicle Physics**: 57kWh battery with consumption based on speed (base rolling resistance + aerodynamic drag)
- **Complete BLE Services**: Main OBD service, Device Information, and MG custom service matching real IOS-Vlink
- **Auto-reconnect**: Automatically re-registers with BlueZ if daemon restarts
- **Interactive Console**: Adjust SOC, simulate driving/charging in real-time
- **ELM327 Compatible**: Responds to standard AT commands and Mode 22 PIDs

## Prerequisites

### Linux (Ubuntu/Debian)

```bash
# Install BlueZ and D-Bus
sudo apt-get install bluetooth bluez dbus

# Make sure Bluetooth is enabled
sudo systemctl start bluetooth
sudo systemctl enable bluetooth
```

### Node.js

Node.js 14+ required. The simulator uses `dbus-next` for D-Bus integration.

## Installation

```bash
cd tools/obd-ble-simulator
npm install
```

## Usage

**Important:** Root privileges required for BLE peripheral mode and D-Bus system bus access.

### Quick Start (Recommended)

```bash
# Use the startup script - handles all setup automatically
sudo ./start-simulator.sh

# Or via npm (same thing)
npm start
```

The startup script is **recommended** because it:

- ✅ Installs D-Bus policy for BlueZ communication (one-time)
- ✅ Only restarts BlueZ if policy changed
- ✅ Loads correct Node.js version (if using nvm)
- ✅ Provides helpful error messages

### Direct Start (Manual)

If you've already run the startup script once and don't need policy setup:

```bash
# Run directly
npm run start:direct

# Or
su -c 'node simulator-dbus.js'
```

### D-Bus Policy Setup (One-time)

If you skip the startup script, you may need to manually install the D-Bus policy so BlueZ can communicate with the simulator:

```bash
sudo cp docs/com.obdsimulator.conf /etc/dbus-1/system.d/
sudo systemctl restart bluetooth
```

### First-time Setup

If you get `RegisterApplication` errors:

1. The D-Bus policy may not be loaded - run `start-simulator.sh` or manually install policy and restart `dbus` or reboot
2. Ensure Bluetooth adapter is up: `sudo hciconfig hci0 up`
3. Check BlueZ is running: `systemctl status bluetooth`

## Interactive Commands

Once running, you can control the simulated vehicle in real-time:

| Command        | Description                       | Example     |
| -------------- | --------------------------------- | ----------- |
| `drive <km/h>` | Start driving at specified speed  | `drive 80`  |
| `speed <km/h>` | Change speed while driving        | `speed 100` |
| `charge`       | Start charging simulation         | `charge`    |
| `stop`         | Stop driving/charging (idle mode) | `stop`      |
| `soc <0-100>`  | Set State of Charge percentage    | `soc 75`    |
| `status`       | Show current vehicle state (JSON) | `status`    |
| `help`         | Show command help                 | `help`      |
| `exit`/`quit`  | Stop simulator                    | `exit`      |

### Battery Simulation

The simulator models a **57 kWh battery** with realistic physics:

- **Random starting SOC**: 40-95% at startup
- **State of Health (SOH)**: 97-99% (slight degradation from new)
- **Voltage range**: 350-400V (varies with SOC)
- **Consumption model**:
  - Base: 0.15 kWh/km (rolling resistance)
  - Aerodynamic: 0.00003 × speed² kWh/km (air resistance)
  - At 100 km/h: ~15 kWh/100km realistic consumption
- **Battery temperature**: Increases while driving, cools when idle
- **Odometer**: Increments realistically based on speed and time

### Simulation Modes

The vehicle automatically switches modes based on commands:

- **static** - Idle, no consumption (default after `stop`)
- **driving** - Active consumption based on speed (set via `drive` or `speed`)
- **charging** - SOC increases at ~50kW rate (set via `charge`)

## Connecting from PWA

1. Start the simulator: `sudo ./start-simulator.sh`
2. Wait for: `📱 Device is now discoverable as "IOS-Vlink" (BLE only)`
3. Open the PWA in Chrome (Web Bluetooth required - won't work in Firefox)
4. Click "Connect" - "IOS-Vlink" should appear in the device list
5. Select and connect - PWA will automatically run AT command initialization
6. Watch the simulator console for real-time command/response logging

The PWA will automatically:

- Send AT reset sequence (ATZ, ATD, ATE0, ATS0, ATH0, ATL0)
- Poll for SOC (22B046) every 10 seconds
- Parse responses and update the UI

**Tip**: Run `status` in the simulator console to see current battery state while testing.

## Supported Commands

The simulator responds to standard ELM327 AT commands and MG-specific Mode 22 PIDs.

### AT Commands (ELM327 Protocol)

All standard AT commands return "OK" or appropriate responses:

| Command     | Response        | Description            |
| ----------- | --------------- | ---------------------- |
| ATZ         | ELM327 v1.5     | Reset device           |
| ATD         | OK              | Set defaults           |
| ATE0 / ATE1 | OK              | Echo off/on            |
| ATS0 / ATS1 | OK              | Spaces off/on          |
| ATH0 / ATH1 | OK              | Headers off/on         |
| ATL0 / ATL1 | OK              | Linefeeds off/on       |
| ATAT2       | OK              | Adaptive timing        |
| ATSP0       | OK              | Set protocol (auto)    |
| ATI         | ELM327 v1.5     | Device identifier      |
| AT@1        | OBDII to RS2... | Device description     |
| ATRV        | 12.5V           | Read voltage           |
| ATSHxxx     | OK              | Set header (any value) |
| ATFCSHxxx   | OK              | Flow control header    |

### OBD PIDs (Mode 22 - MG ZS EV)

Responses follow format: `62<PID><DATA>\r` followed by `>` prompt in separate notification.

| PID    | Description          | Response Format        | Conversion Formula           |
| ------ | -------------------- | ---------------------- | ---------------------------- |
| 22B046 | State of Charge      | 62B046XXXX             | value / 10 = % (0-100)       |
| 22B061 | State of Health      | 62B061XXXX             | value / 100 = % (0-100)      |
| 22B042 | Battery Voltage      | 62B042XXXX             | value × 0.25 = V             |
| 22B043 | Battery Current      | 62B043XXXX             | (value - 40000) × 0.025 = A  |
| 22B056 | Battery Temperature  | 62B056XXXX             | (byte1 / 2) - 40 = °C        |
| 22B048 | Unknown (always 0)   | 62B0480000             | -                            |
| 22B101 | Odometer             | 62B101XXXXXX (3 bytes) | A×65536 + B×256 + C = km     |
| 22BA00 | Vehicle Speed        | 62BA00XXXX             | (value - 20000) / 100 = km/h |
| 22BB05 | External Temperature | 62BB05XX               | value - 40 = °C              |

**Unsupported PIDs** return: `NO DATA`

## BLE Architecture

### Service UUIDs (Matching Real IOS-Vlink)

The simulator exposes 5 BLE GATT services to match the real device:

| Service                     | UUID                                   | Purpose                    |
| --------------------------- | -------------------------------------- | -------------------------- |
| Main OBD Service            | `e7810a71-73ae-499d-8c15-faa9aef0c3f2` | Read/Write/Notify OBD data |
| Device Information (0x180A) | `0000180a-0000-1000-8000-00805f9b34fb` | Manufacturer, model, FW    |
| MG Custom Service (0x18F0)  | `000018f0-0000-1000-8000-00805f9b34fb` | Unknown MG-specific        |
| Generic Attribute (0x1801)  | `00001801-0000-1000-8000-00805f9b34fb` | BLE standard service       |
| Generic Access (0x1800)     | `00001800-0000-1000-8000-00805f9b34fb` | Device name, appearance    |

### Main OBD Characteristic

**UUID**: `bef8d6c9-9c21-4c9e-b632-bd58c1009f9f`

**Properties**: Read, Write, Write-Without-Response, Notify, Indicate

**Protocol**:

1. Client writes command (e.g., `ATZ\r` or `22B046\r`)
2. Server sends response via notification (e.g., `62B046024E\r`)
3. Server sends prompt via separate notification (`>`)
4. Client proceeds with next command after receiving `>`

**Notification Strategy**: Split mode - response and prompt sent as separate notifications to match PWA parser expectations.

### D-Bus Implementation

The simulator uses BlueZ's D-Bus API instead of raw HCI:

- **Advantages**: Works alongside system Bluetooth, auto-reconnects on BlueZ restart, cleaner API
- **Interfaces Used**: `org.bluez.GattManager1`, `org.bluez.LEAdvertisingManager1`, `org.bluez.GattService1`, `org.bluez.GattCharacteristic1`
- **Signal Emission**: Uses `DBusInterface.emitPropertiesChanged()` for BLE notifications
- **Object Manager**: Exports full GATT hierarchy via `GetManagedObjects()`

## Troubleshooting

### "Bluetooth adapter not found" / "hci0 not available"

```bash
# Check adapter status
hciconfig -a

# Bring up adapter
sudo hciconfig hci0 up

# Restart Bluetooth
sudo systemctl restart bluetooth
```

### "RegisterApplication failed" / D-Bus errors

The D-Bus policy may not be installed:

```bash
# Use the startup script (handles this automatically)
sudo ./start-simulator.sh

# Or manually install policy
sudo cp docs/com.obdsimulator.conf /etc/dbus-1/system.d/
sudo systemctl restart dbus
sudo systemctl restart bluetooth
```

If errors persist after policy install, **reboot** to ensure D-Bus reloads all policies.

### "Cannot acquire name com.obdsimulator"

Another instance may be running:

```bash
# Check for running instances
ps aux | grep simulator-dbus

# Kill if found
sudo pkill -f simulator-dbus
```

### Device not appearing in Chrome Bluetooth scan

1. Verify simulator shows: `✅ Advertisement registered`
2. Ensure you're using **Chrome** or **Edge** (Firefox doesn't support Web Bluetooth)
3. Check adapter is advertising: `sudo hcitool lescan`
4. Try restarting the simulator
5. Clear Chrome's Bluetooth cache: `chrome://bluetooth-internals` → Adapter → "Forget all devices"

### Android pairing dialog appears

This is normal on first connection. The simulator is configured for BLE-only (no BR/EDR) but Android may still show a pairing dialog. Just accept it once.

### PWA gets "GATT Error" or disconnects

The Web Bluetooth API can be unstable. Try:

1. Refresh the PWA page
2. Restart the simulator
3. Disable/re-enable Bluetooth on your device
4. Check `journalctl -u bluetooth -f` for BlueZ logs

### No response to commands / notifications not received

Check simulator console for:

- `📥 Received: "ATZ"` - confirms write received
- `📤 Response: "ELM327 v1.5"` - confirms response generated
- `✓ PropertiesChanged emitted successfully` - confirms notification sent

If you see `✗ PropertiesChanged error` or `mainCharInterface not initialized`, this is a bug - please report.

### Permission denied (even as root)

SELinux or AppArmor may be blocking D-Bus access:

```bash
# Check SELinux (if applicable)
getenforce

# Temporarily set to permissive for testing
sudo setenforce 0
```

## Architecture Diagram

```
┌────────────────────────┐
│   PWA (Chrome) or      │
│   Android App          │
│                        │
│   Web Bluetooth /      │
│   Android BLE API      │
└───────────┬────────────┘
            │ BLE GATT
            │ (Read/Write/Notify)
            ▼
┌────────────────────────┐
│   BlueZ (bluetoothd)   │◄─── System D-Bus
│   Linux BLE Stack      │
└───────────┬────────────┘
            │ D-Bus API
            │ (GattManager1, LEAdvertisingManager1)
            ▼
┌────────────────────────┐
│  simulator-dbus.js     │
│  ┌──────────────────┐  │
│  │ VehicleSimulator │  │ ← 57kWh battery physics
│  │  • SOC/SOH/V/A/T │  │
│  │  • Speed/odo     │  │
│  │  • Consumption   │  │
│  └──────────────────┘  │
│  ┌──────────────────┐  │
│  │ OBD Handler      │  │ ← AT + PID responses
│  │  • processAT()   │  │
│  │  • processOBD()  │  │
│  └──────────────────┘  │
│  ┌──────────────────┐  │
│  │ D-Bus Interfaces │  │
│  │  • GATT Services │  │
│  │  • Advertising   │  │
│  │  • Properties    │  │
│  └──────────────────┘  │
└────────────────────────┘
```

**Data Flow** (Example: PWA requests SOC):

1. PWA writes `22B046\r` to OBD characteristic
2. BlueZ forwards write via D-Bus to `simulator-dbus.js`
3. `processOBDPID()` reads `vehicle.soc`, formats response `62B046024E`
4. Simulator calls `DBusInterface.emitPropertiesChanged()` with Value
5. BlueZ sends BLE notification: `62B046024E\r`
6. 100ms later, second notification: `>`
7. PWA receives both, strips `\r`, parses `62B046024E` → SOC = 58.6%

## Using with Android App

The simulator works identically for Android BLE testing:

1. Start simulator on Linux machine
2. Android app scans for BLE devices
3. Finds "IOS-Vlink" with service UUID `e7810a71-73ae-499d-8c15-faa9aef0c3f2`
4. Connects to main characteristic `bef8d6c9-9c21-4c9e-b632-bd58c1009f9f`
5. Sends AT initialization commands
6. Subscribes to notifications
7. Sends OBD PIDs and receives responses

See `android-app/README-obd-ble.md` for Android implementation details.

## Development

### Project Structure

```
obd-ble-simulator/
├── simulator-dbus.js       # Main simulator (D-Bus/BlueZ implementation)
├── start-simulator.sh      # Startup script with D-Bus policy setup
├── package.json            # Dependencies (dbus-next)
├── README.md              # This file
└── docs/
    └── com.obdsimulator.conf  # D-Bus policy file
```

### Key Components

- **VehicleSimulator class**: Physics simulation with 1-second update loop
- **processCommand()**: Routes AT vs OBD commands
- **processATCommand()**: Handles ELM327 AT commands
- **processOBDPID()**: Handles Mode 22 PIDs with vehicle data
- **OBDCharacteristic class**: GATT characteristic with Read/Write/Notify
- **GattApplication class**: ObjectManager exposing all services/characteristics
- **Advertisement class**: LEAdvertisement1 for device discovery
- **D-Bus robustness**: Auto-reconnect on BlueZ restart, signal emission helpers

### Logging

The simulator provides detailed logging:

- `📡` BLE notifications (subscribe/unsubscribe)
- `📥` Incoming commands
- `📤` Outgoing responses
- `⟲` D-Bus signal emissions
- `🔧` Configuration changes
- `🔋` Battery state updates
- `🚗` Driving simulation events
- `✅` / `❌` Success/error indicators

### Testing

Watch the logs while the PWA connects to see the full protocol exchange:

```bash
sudo ./start-simulator.sh

# In PWA, click Connect → IOS-Vlink
# You'll see:
📡 Client subscribed to notifications
📥 Received: "ATZ"
📤 Response: "ELM327 v1.5"
⟲ Emitting PropertiesChanged (Value)
   ✓ PropertiesChanged emitted successfully
...
```

## Contributing

When modifying the simulator:

1. **Battery physics**: Edit `VehicleSimulator` class constructor and `_updateSimulation()`
2. **AT commands**: Add to `processATCommand()` response map
3. **OBD PIDs**: Add cases to `processOBDPID()` switch statement
4. **BLE services**: Modify service UUIDs in `SERVICES` and `CHARS` constants
5. **Notification protocol**: Adjust split/timing in `WriteValue()` method

## License

MIT
