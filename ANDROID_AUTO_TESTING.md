# Android Auto Testing Guide

## Testing Options

### 1. Desktop Head Unit (DHU) - Recommended for Development

The DHU is a desktop simulator that lets you test Android Auto apps without a car.

#### Install DHU

Install via SDK Manager in Android Studio:

Android Studio > Tools > SDK Manager > SDK Tools > Android Auto Desktop Head Unit Emulator

#### Run DHU

```bash

# Start DHU after connecting via usb
~/Android/Sdk/extras/google/auto/desktop-head-unit --usb

```

#### Connect Your Phone

1. Install the PleaseCharge app on your Android phone
2. Enable Android Auto developer mode on your phone:
   - Open Android Auto app
   - Tap hamburger menu → About
   - Tap "Version" 10 times
   - Developer mode enabled!
3. In Android Auto developer settings:
   - Enable "Unknown sources" (for testing unsigned apps)
   - Set "Application mode" to "Developer"
4. Connect phone to computer via USB
5. Run `adb devices` to verify connection
6. DHU should now show your phone screen
7. PleaseCharge should appear in the app list

### 2. Real Car Testing

For testing in an actual vehicle:

#### Option A: Internal Test Track (No Play Store Review Required)

1. Go to Google Play Console
2. Create an Internal Test Track
3. Upload your APK
4. Add your Google account as a tester
5. Install via Play Store on your phone
6. Connect phone to car via USB
7. PleaseCharge will appear in Android Auto

#### Option B: Internal App Sharing (Even Faster)

1. Go to Play Console > Internal App Sharing
2. Upload APK
3. Get sharing link
4. Open link on your phone
5. Install app
6. Connect to car

**Note**: For Car App Library apps (like PleaseCharge), you MUST use Internal Test Track or Internal App Sharing. The "unknown sources" option doesn't work for these apps.

### 3. Android Automotive OS Emulator

If you want to test on Automotive OS (car's native OS, not phone projection):

```bash
# In Android Studio
# Tools > Device Manager > Create Device
# Category: Automotive
# Select "Automotive (1024p landscape)" or similar
# Download a system image (API 29+ for Automotive)
# Create and launch emulator
```

## Testing the PleaseCharge App

### Initial Setup

1. Build and install:

   ```bash
   cd android-app
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. Grant Bluetooth permissions on phone:

   - Open PleaseCharge app on phone
   - Grant all permissions (Bluetooth, Location)
   - Connect to OBD device from phone app first

3. Launch in Android Auto:
   - Open Android Auto (on DHU or in car)
   - Look for PleaseCharge in the app launcher
   - Tap to open

### What You Should See

The Android Auto screen will display:

- **Connection Status**: DISCONNECTED, SCANNING, CONNECTING, or READY
- **State of Charge**: SOC percentage (e.g., "85.3%")
- **Raw Value**: Raw OBD value (e.g., "809")
- **Last Updated**: Timestamp of last SOC reading
- **Connect/Disconnect button**
- **Refresh SOC button** (when connected)

SOC updates automatically every 10 seconds when connected.

## Troubleshooting

### DHU doesn't show my app

1. Check DHU is running: `ps aux | grep desktop-head-unit`
2. Check ADB forward: `adb forward --list` (should show tcp:5277)
3. Check app is installed: `adb shell pm list packages | grep pleasecharge`
4. Check Android Auto developer mode is enabled
5. Restart DHU and reconnect

### "Missing BLE permissions" error in car

- BLE permissions must be granted from the phone app first
- Open PleaseCharge on phone, grant all permissions
- Then try again in Android Auto

### App crashes when clicking Connect

- Make sure BLE simulator is running
- Check phone has Bluetooth enabled
- Check Android logs: `adb logcat | grep PleaseCharge`

### Can't test in real car

- Use Internal Test Track or Internal App Sharing
- "Unknown sources" doesn't work for Car App Library apps
- Must upload to Play Console for real car testing

## Debugging

```bash
# View logs while testing in DHU or car
adb logcat | grep -E "(PleaseCharge|BleObd|CarApp)"

# Filter for errors only
adb logcat *:E | grep PleaseCharge

# Clear app data (forces fresh start)
adb shell pm clear com.rw251.pleasecharge
```

## Additional Resources

- [Android Auto Documentation](https://developer.android.com/training/cars)
- [DHU Documentation](https://developer.android.com/training/cars/testing/dhu)
- [Car App Library Guide](https://developer.android.com/training/cars/apps)
- [Internal App Sharing](https://play.google.com/console/about/internalappsharing/)
