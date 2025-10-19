# IOS‑Vlink BLE bridge

This helper exposes the [ELM327-emulator](https://github.com/Ircama/ELM327-emulator) as a Bluetooth Low Energy peripheral that looks like the IOS‑Vlink dongle. It allows the Android app (or the legacy PWA) to connect over BLE while the emulator continues to run over a simple TCP socket.

## Prerequisites

- Linux laptop with Bluetooth LE support (BlueZ 5.50+).
- Python 3.8+ with `pip` and Node.js ≥ 16 (the bridge uses `@abandonware/bleno` which targets active LTS releases).
- The system Bluetooth adapter must be enabled (`bluetoothctl show`, `power on`).
- The process that runs the BLE bridge needs CAP_NET_RAW. Easiest option: run the bridge with `sudo`, or grant the capability once:
  ```bash
  sudo setcap cap_net_raw+eip "$(readlink -f "$(which node)")"
  ```
- Install BlueZ tooling if missing:
  ```bash
  sudo apt install bluez bluetooth libbluetooth-dev libudev-dev
  ```

## 1. Install and launch the ELM327 emulator

Create (or reuse) a Python virtual environment and install the emulator:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install ELM327-emulator
```

Start the emulator in “car” scenario and expose a TCP port (leave this running):

```bash
python3 -m elm -s car -n 35000
```

You should see a log line similar to:

```
ELM327-emulator is listening on 127.0.0.1:35000
```

## 2. Install bridge dependencies

```bash
cd tools/ios-vlink-ble-bridge
# if you previously installed dependencies, clear them the first time you switch to @abandonware/bleno
rm -rf node_modules package-lock.json
npm install
```

`npm install` only needs to run once (or after dependency updates).

## 3. Start the BLE bridge

With the emulator still running, launch the bridge in another terminal:

```bash
cd tools/ios-vlink-ble-bridge
sudo node index.js --host 127.0.0.1 --port 35000
```

Environment variables `ELM_HOST`, `ELM_PORT`, and `IOS_VLINK_NAME` can be used instead of the command-line switches.

On startup the bridge will power on the adapter, advertise as `IOS-Vlink`, and log every command/response exchanged with the emulator.

## 4. Pair and test

Open `bluetoothctl` to confirm advertising:

```bash
bluetoothctl scan on
```

You should see `IOS-Vlink` in the scan results.

On your Android device:

1. Make sure the old dongle is unplugged so it does not conflict.
2. Launch the PleaseCharge app and hit connect. It will discover the local bridge, run the AT init sequence, and begin polling SOC/temperature.

Bridge logs look like:

```
[bridge] BLE -> ELM "ATZ\r"
[bridge] ELM -> BLE "ELM327 v3.0\r>"
```

## 5. Stopping the setup

- Stop the bridge with `Ctrl+C`.
- Stop the emulator with `Ctrl+C`.
- If you granted `cap_net_raw` to `node` and want to undo it:
  ```bash
  sudo setcap -r "$(readlink -f "$(which node)")"
  ```

## Troubleshooting

- `Unhandled HCI error: LE Advertising Set Terminated` – ensure no other process (e.g., `bluetoothd --experimental`, `bluetoothctl advertise`) is advertising concurrently.
- Android cannot find `IOS-Vlink` – verify the bridge logs `Advertising as IOS-Vlink` and that the adapter is not hard blocked (`rfkill list`).
- No data flowing – double-check the emulator is running on the port specified, and that the bridge logs `Connected to ELM emulator`.
