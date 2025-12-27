# Android Auto Implementation Summary

## What Was Added

### 1. Dependencies

- Added `androidx.car.app` library (v1.7.0) to `build.gradle.kts`
- This is the official Car App Library for building Android Auto apps

### 2. Car App Service

**File**: `PleaseChargeCarAppService.kt`

- Entry point for Android Auto
- Creates a `Session` when Android Auto connects
- Session creates the `SocDisplayScreen` to show SOC data

### 3. SOC Display Screen

**File**: `SocDisplayScreen.kt`

- Displays connection status, SOC percentage, raw value, and timestamp
- Connect/Disconnect buttons
- Refresh SOC button (when connected)
- Auto-updates every 10 seconds via BLE polling
- Uses Car App Library's `PaneTemplate` for driver-safe UI

### 4. Shared BLE Connection

**File**: `BleConnectionManager.kt`

- Singleton that shares BLE connection between phone app and Android Auto
- Allows switching between phone and car UI without reconnecting
- Uses proxy listener pattern to route callbacks to active UI

### 5. Manifest Configuration

- Added `CarAppService` declaration
- Added automotive app metadata
- Points to `automotive_app_desc.xml`

### 6. Automotive Descriptor

**File**: `automotive_app_desc.xml`

- Declares the app uses "template" (Car App Library templates)

## How to Test

### Quick Start with DHU (No Car Needed)

1. **Install DHU** (Desktop Head Unit):

   ```bash
   # In Android Studio: Tools > SDK Manager > SDK Tools
   # Check "Android Auto Desktop Head Unit Emulator"
   ```

2. **Enable Developer Mode on Phone**:

   - Open Android Auto app
   - Go to About
   - Tap "Version" 10 times
   - Enable "Unknown sources" in developer settings

3. **Run DHU**:

   ```bash
   cd ~/Library/Android/sdk/extras/google/auto/desktop-head-unit/
   ./desktop-head-unit
   ```

4. **Connect Phone**:

   ```bash
   adb forward tcp:5277 tcp:5277
   # Connect phone via USB
   # DHU window should show your phone
   ```

5. **Launch PleaseCharge**:
   - Find PleaseCharge in the DHU app list
   - Click to open
   - You should see SOC display

### Testing in Real Car

**Option 1: Internal Test Track** (Recommended)

- Upload APK to Play Console Internal Test Track
- Add yourself as tester
- Install from Play Store
- Connect phone to car
- App appears in Android Auto

**Option 2: Internal App Sharing** (Faster)

- Upload to Play Console > Internal App Sharing
- Get sharing link, open on phone
- Install and connect to car

**Important**: "Unknown sources" doesn't work for Car App Library apps in real cars - you must use Play Console distribution.

## Current Features

- ✅ Connection status display
- ✅ SOC percentage display
- ✅ Raw OBD value display
- ✅ Last update timestamp
- ✅ Connect/Disconnect control
- ✅ Manual refresh button
- ✅ Auto-polling every 10 seconds
- ✅ Shared connection with phone app

## Architecture

```
Phone App (MainActivity)
    ↓
BleConnectionManager (Singleton)
    ↓
BleObdManager
    ↓
[Bluetooth OBD Device]
    ↑
BleConnectionManager (Singleton)
    ↑
Android Auto (SocDisplayScreen)
```

Both UIs share the same BLE connection via the singleton. When you switch from phone to car (or vice versa), the listener is updated but the connection stays active.

## Next Steps / Future Enhancements

- Add battery temperature display
- Add voltage and current readings
- Add historical SOC graph
- Add charging rate calculation
- Improve error handling and recovery
- Add voice command support
- Customize update frequency
- Add low battery warnings

## File Structure

```
android-app/app/src/main/
├── java/com/rw251/pleasecharge/
│   ├── MainActivity.kt              # Phone UI
│   ├── MainViewModel.kt             # Phone UI state
│   ├── BleConnectionManager.kt      # NEW: Shared BLE singleton
│   ├── ble/
│   │   └── BleObdManager.kt         # BLE logic
│   └── car/                         # NEW: Android Auto package
│       ├── PleaseChargeCarAppService.kt  # NEW: Car app entry point
│       └── SocDisplayScreen.kt      # NEW: Car UI screen
├── res/
│   └── xml/
│       └── automotive_app_desc.xml  # NEW: Car app metadata
└── AndroidManifest.xml              # Updated with CarAppService
```

## Testing Checklist

- [ ] App builds successfully
- [ ] Phone app still works
- [ ] DHU shows PleaseCharge in app list
- [ ] Can open PleaseCharge in DHU
- [ ] Connect button works
- [ ] SOC displays correctly
- [ ] Auto-updates every 10 seconds
- [ ] Manual refresh works
- [ ] Disconnect works
- [ ] Connection state persists when switching between phone and car UI

## Documentation

See `ANDROID_AUTO_TESTING.md` for detailed testing instructions, troubleshooting, and debugging tips.
