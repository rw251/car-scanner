# OBD BLE Simulator

A Bluetooth Low Energy OBD-II simulator that emulates an IOS-Vlink (ELM327-compatible) device. This allows you to test the car-scanner PWA and Android app without needing the actual vehicle.

## Prerequisites

### Linux (Ubuntu/Debian)

```bash
# Install Bluetooth development libraries
sudo apt-get install bluetooth bluez libbluetooth-dev libudev-dev

# Make sure Bluetooth is enabled
sudo systemctl start bluetooth
sudo systemctl enable bluetooth
```

### Node.js

Make sure you have Node.js 14+ installed.

## Installation

```bash
cd tools/obd-ble-simulator
npm install
```

## Usage

**Important:** On Linux, you need to run as root to access Bluetooth LE peripheral functionality. Use `su` to switch to root:

```bash
# Option 1: Use npm script (will prompt for root password)
npm start

# Option 2: Run directly as root
su -c 'node simulator.js'
```

Or for verbose debug output:

```bash
su -c 'DEBUG=* node simulator.js'
```

### First-time Setup

If you get errors about Bluetooth permissions, you may need to:

1. Stop the Bluetooth service temporarily:

   ```bash
   su -c 'systemctl stop bluetooth'
   ```

2. Run the simulator, which will manage Bluetooth directly

3. After you're done, restart Bluetooth:
   ```bash
   su -c 'systemctl start bluetooth'
   ```

Alternatively, grant node Bluetooth permissions (as root):

```bash
su -c "setcap cap_net_raw+eip $(readlink -f $(which node))"
```

## Interactive Commands

Once running, you can control the simulated vehicle data:

| Command           | Description                     | Example               |
| ----------------- | ------------------------------- | --------------------- |
| `mode <mode>`     | Set simulation mode             | `mode charging`       |
| `soc <0-1000>`    | Set State of Charge (0-100%)    | `soc 750` (75%)       |
| `soh <0-10000>`   | Set State of Health             | `soh 9850` (98.5%)    |
| `voltage <value>` | Set voltage (value \* 0.25 = V) | `voltage 1520` (380V) |
| `temp <value>`    | Set battery temp                | `temp 140` (30°C)     |
| `status`          | Show all current values         | `status`              |
| `help`            | Show help                       | `help`                |
| `exit`            | Stop simulator                  | `exit`                |

### Simulation Modes

- **static** - Values remain constant
- **charging** - SOC increases over time, positive current
- **discharging** - SOC decreases over time, negative current
- **driving** - SOC decreases, current fluctuates, speed varies

## Connecting from PWA

1. Start the simulator on your laptop
2. Open your PWA in Chrome (on the same laptop or a different device)
3. Click "Connect" - you should see "IOS-Vlink" in the device list
4. Connect and the PWA should start receiving simulated data

## Supported Commands

### AT Commands (ELM327)

| Command   | Description         |
| --------- | ------------------- |
| ATZ       | Reset device        |
| ATD       | Set defaults        |
| ATE0/ATE1 | Echo off/on         |
| ATS0/ATS1 | Spaces off/on       |
| ATH0/ATH1 | Headers off/on      |
| ATL0/ATL1 | Linefeeds off/on    |
| ATAT2     | Adaptive timing     |
| ATSP0     | Protocol auto       |
| ATSHxxx   | Set header          |
| ATFCSHxxx | Flow control header |

### OBD PIDs (Mode 22)

| PID    | Description          | Formula                      |
| ------ | -------------------- | ---------------------------- |
| 22B046 | State of Charge      | value / 10 = %               |
| 22B061 | State of Health      | value / 100 = %              |
| 22B042 | Battery Voltage      | value \* 0.25 = V            |
| 22B043 | Battery Current      | (value - 40000) \* 0.025 = A |
| 22B056 | Battery Temperature  | value / 2 - 40 = °C          |
| 22B101 | Odometer             | A*65536 + B*256 + C = km     |
| 22BA00 | Vehicle Speed        | (value - 20000) / 100 = km/h |
| 22BB05 | External Temperature | value - 40 = °C              |

## BLE Service UUIDs

These match the real IOS-Vlink device:

- **Main Service:** `e7810a71-73ae-499d-8c15-faa9aef0c3f2`
- **Device Information Service:** `180a`
- **OBD Characteristic:** `bef8d6c9-9c21-4c9e-b632-bd58c1009f9f`

## Troubleshooting

### "Bluetooth adapter not found"

Make sure Bluetooth is enabled:

```bash
su -c 'hciconfig hci0 up'
```

### "Permission denied"

Run as root using `su`, or set capabilities:

```bash
su -c "setcap cap_net_raw+eip $(readlink -f $(which node))"
```

### Device not appearing in Chrome

1. Make sure you're using Chrome (not Firefox - Firefox doesn't support Web Bluetooth)
2. Check that the simulator shows "Advertising started successfully"
3. Try restarting Bluetooth: `su -c 'systemctl restart bluetooth'`
4. Make sure no other device is connected to your laptop's Bluetooth

### "GATT Error"

The Web Bluetooth API can be finicky. Try:

1. Refreshing the PWA page
2. Restarting the simulator
3. Clearing Bluetooth cache in Chrome: `chrome://bluetooth-internals`

## Architecture

```
┌──────────────────┐     BLE      ┌──────────────────┐
│                  │◄────────────►│                  │
│   PWA / Android  │              │  OBD Simulator   │
│   App            │              │  (Your Laptop)   │
│                  │              │                  │
└──────────────────┘              └──────────────────┘
                                         │
                                         ▼
                                  ┌──────────────────┐
                                  │  vehicle-data.js │
                                  │  (Configurable   │
                                  │   test data)     │
                                  └──────────────────┘
```

## Using with Android App

The same simulator works for testing your Android app. The Android app will:

1. Scan for BLE devices
2. Find "IOS-Vlink"
3. Connect to the main service UUID
4. Send AT commands and OBD PIDs
5. Receive responses via notifications

See the main `android-app/README-obd-ble.md` for Android implementation details.
