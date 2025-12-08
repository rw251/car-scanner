#!/bin/bash

# Development Helper
# Unified script for build, deploy, and debugging operations

# Configuration
ROOT_DIR="$(dirname "${BASH_SOURCE[0]}")"
APP_DIR="$ROOT_DIR/android-app"
ADB_DIR="/home/richard/Android/Sdk/platform-tools"
ADB="$ADB_DIR/adb"
TIMEOUT_SECS=60
POLL_INTERVAL=2

echo "========================================"
echo "Development Helper"
echo "========================================"

# Function to check if adb is available
check_adb() {
    if ! command -v "$ADB" >/dev/null 2>&1; then
        echo "❌ ADB not found at '$ADB'"
        echo "Please ensure Android SDK platform-tools is installed"
        exit 1
    fi
}

# Function to check if device is connected
check_device_connected() {
    local list
    list=$($ADB devices | tail -n +2 | awk '$2 == "device" {print $1}')
    if [[ -n "$list" ]]; then
        echo "✅ Android device connected: $list"
        return 0
    fi
    return 1
}

# Function to pair and connect device
pair_device() {
    echo ""
    echo "📱 No connected devices found."
    echo ""
    echo "To pair over Wi-Fi from your phone:"
    echo "  1. On the phone: Settings → System → Developer options → Wireless debugging (enable it)"
    echo "  2. In Wireless debugging: choose 'Pair device with pairing code'"
    echo "  3. Note the IP:PORT and 6-digit pairing code shown"
    echo ""
    
    read -rp "Enter the phone IP:PORT to pair (e.g. 192.168.1.42:5555) or blank to cancel: " PHONE_ADDR
    if [[ -z "$PHONE_ADDR" ]]; then
        echo "❌ Pairing cancelled"
        return 1
    fi
    
    echo "🔗 Running: $ADB pair $PHONE_ADDR"
    echo "Enter the 6-digit pairing code when prompted..."
    
    $ADB pair "$PHONE_ADDR"
    PAIR_EXIT=$?
    if [[ $PAIR_EXIT -ne 0 ]]; then
        echo "❌ Pairing failed. Check the IP:PORT and pairing code"
        return 1
    fi
    
    echo "✅ Pairing successful! Waiting for device to appear..."
    
    local SECS_WAITED=0
    while [[ $SECS_WAITED -lt $TIMEOUT_SECS ]]; do
        if check_device_connected >/dev/null 2>&1; then
            local SEEN_DEVICE=$($ADB devices | tail -n +2 | awk '$2 == "device" {print $1; exit}')
            echo "✅ Device connected: $SEEN_DEVICE"
            return 0
        fi
        sleep $POLL_INTERVAL
        SECS_WAITED=$((SECS_WAITED + POLL_INTERVAL))
        echo -n "."
    done
    
    echo ""
    echo "⏰ Timeout waiting for device. Try running the script again or check Wireless debugging settings"
    return 1
}

# Function to ensure device connection
ensure_device_connected() {
    check_adb
    
    if check_device_connected >/dev/null 2>&1; then
        return 0
    else
        pair_device
        return $?
    fi
}

list_devices() {
    check_adb
    echo ""
    echo "📱 Connected devices:"
    echo "========================================"
    $ADB devices | tail -n +2
}

restart_adb() {
    echo ""
    echo "🔄 Restarting ADB server..."
    $ADB kill-server
    sleep 2
    $ADB start-server
    echo "✅ ADB server restarted"
    list_devices
}

# Function to build the app
build_app() {
    echo ""
    echo "🔨 Building Widget APK..."
    echo "========================================"

    (cd "$APP_DIR" && ./gradlew assembleDebug)
    
    if [ $? -eq 0 ]; then
        echo "✅ Build successful!"
        
        local apk_path="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
        if [ -f "$apk_path" ]; then
            local size=$(du -h "$apk_path" | cut -f1)
            echo "📦 APK size: $size"
            echo "📍 APK location: $apk_path"
        fi
        return 0
    else
        echo "❌ Build failed!"
        return 1
    fi
}

# Function to update version for release
update_version_for_release() {
    local gradle_file="$APP_DIR/app/build.gradle.kts"
    
    echo ""
    echo "📋 Current version:"
    echo "========================================"
    
    # Extract current version info
    local current_code=$(grep -oP 'versionCode = \K\d+' "$gradle_file")
    local current_name=$(grep -oP 'versionName = "\K[^"]+' "$gradle_file")
    
    echo "  Version Code: $current_code"
    echo "  Version Name: $current_name"
    echo ""
    
    # Ask user for new version
    read -p "Enter new version name (e.g., 1.1, 2.0) or press Enter to skip: " new_version_name
    
    if [[ -z "$new_version_name" ]]; then
        echo "⏭️  Skipping version update"
        return 0
    fi
    
    # Increment version code
    local new_version_code=$((current_code + 1))
    
    echo ""
    echo "🆕 New version:"
    echo "  Version Code: $new_version_code"
    echo "  Version Name: $new_version_name"
    echo ""
    
    # Update gradle file
    sed -i "s/versionCode = $current_code/versionCode = $new_version_code/" "$gradle_file"
    sed -i "s/versionName = \"$current_name\"/versionName = \"$new_version_name\"/" "$gradle_file"
    
    if [ $? -eq 0 ]; then
        echo "✅ Version updated successfully!"
        
        # Optionally create git tag
        read -p "Create git tag for this version? (y/n): " -n 1 -r create_tag
        echo ""
        
        if [[ $create_tag =~ ^[Yy]$ ]]; then
            (cd "$ROOT_DIR" && git add "$gradle_file" && git commit -m "Bump version to $new_version_name" && git tag -a "v$new_version_name" -m "Release version $new_version_name")
            if [ $? -eq 0 ]; then
                echo "✅ Git tag created: v$new_version_name"
            else
                echo "⚠️  Failed to create git tag (make sure git is configured)"
            fi
        fi
        
        return 0
    else
        echo "❌ Failed to update version"
        return 1
    fi
}

# Function to build production release
build_release() {
    echo ""
    echo "📦 Building Production Release Bundle..."
    echo "========================================"
    
    # First, handle version update
    update_version_for_release
    if [ $? -ne 0 ]; then
        return 1
    fi
    
    local keystore_path="$APP_DIR/release-keystore.jks"
    
    echo ""
    read -rsp "Enter keystore password: " KEYSTORE_PASS
    echo ""
    # read -rp "Enter key alias [pleasecharge-release]: " KEY_ALIAS
    KEY_ALIAS=${KEY_ALIAS:-upload}
    read -rsp "Enter key password (press Enter if same as keystore): " KEY_PASS
    echo ""
    KEY_PASS=${KEY_PASS:-$KEYSTORE_PASS}
    
    echo ""
    echo "🔨 Building release bundle..."
    
    # Convert to absolute path
    local abs_keystore_path=$(cd "$(dirname "$keystore_path")" && pwd)/$(basename "$keystore_path")
    
    (cd "$APP_DIR" && ./gradlew bundleRelease \
        -Pandroid.injected.signing.store.file="$abs_keystore_path" \
        -Pandroid.injected.signing.store.password="$KEYSTORE_PASS" \
        -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
        -Pandroid.injected.signing.key.password="$KEY_PASS")
    
    if [ $? -eq 0 ]; then
        echo "✅ Release build successful!"
        
        local aab_path="$APP_DIR/app/build/outputs/bundle/release/app-release.aab"
        if [ -f "$aab_path" ]; then
            local size=$(du -h "$aab_path" | cut -f1)
            echo "📦 AAB size: $size"
            echo "📍 AAB location: $aab_path"
            echo ""
            echo "📝 Next steps:"
            echo "  1. Go to https://play.google.com/console"
            echo "  2. Select your app (or create a new one)"
            echo "  3. Go to Release → Production → Create new release"
            echo "  4. Upload the AAB file: $aab_path"
            echo ""
            echo "⚠️  Don't forget to back up: $keystore_path"
        fi
        return 0
    else
        echo "❌ Release build failed!"
        return 1
    fi
}

# Function to build and deploy
build_and_deploy() {
    if ! ensure_device_connected; then
        echo "❌ Cannot deploy without device connection"
        return 1
    fi
    
    if build_app; then
        echo ""
        echo "📱 Installing APK to device..."
        
        local apk_path="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
        $ADB install -r "$apk_path"
        
        if [ $? -eq 0 ]; then
            echo "✅ Installation successful!"
            
            echo ""
            echo "🚀 Launching app..."
            $ADB shell am start -n com.rw251.pleasecharge/.MainActivity

            echo ""
            echo "📝 Next steps:"
            echo "  • Add Widget to home screen: Long-press → Widgets → PleaseCharge"
            echo "  • Use option 3 to export logs for debugging"
            return 0
        else
            echo "❌ Installation failed!"
            return 1
        fi
      else
          return 1
    fi
}

# Function to export logs
export_logs() {
    if ! ensure_device_connected; then
        echo "❌ Cannot export logs without device connection"
        return 1
    fi
    
    echo ""
    echo "📋 Exporting logs from device..."
    echo "========================================"
    
    # Export logs from device storage
    local log_path="/data/data/com.rw251.pleasecharge/files/widget_log.txt"
    local local_file="$ROOT_DIR/widget_logs.txt"
    $ADB shell "run-as com.rw251.pleasecharge cat $log_path" > "$local_file"

    if [ -f "$local_file" ]; then
        echo "✅ Logs exported to: $local_file"
        echo "📊 Log file size: $(du -h "$local_file" | cut -f1)"
        echo ""
        echo "📄 Last 10 lines:"
        echo "----------------------------------------"
        tail -10 "$local_file"
        return 0
    else
        echo "❌ Failed to export logs"
        return 1
    fi
}

# Function to show menu and get user choice
show_menu() {
    echo ""
    echo "Choose an option:"
    echo "1) Build APK (debug)"
    echo "2) Build and deploy to phone"
    echo "3) Build production release (Play Store)"
    echo "4) Export logs from phone"
    echo "5) List connected devices"
    echo "6) Restart ADB server"
    echo "7) Exit"
    echo ""
    read -p "Enter choice [1-7]: " choice

    case $choice in
        1)
            build_app
            ;;
        2)
            build_and_deploy
            ;;
        3)
            build_release
            ;;
        4)
            export_logs
            ;;
        5)
            list_devices
            ;;
        6)
            restart_adb
            ;;
        7)
            echo "👋 Goodbye!"
            exit 0
            ;;
        *)
            echo "❌ Invalid option. Please choose 1-7."
            return 1
            ;;
    esac
}

# Main script loop
while true; do
    show_menu
    echo ""
done
