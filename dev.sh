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

# Function for fast incremental build and deploy
fast_deploy() {
    if ! ensure_device_connected; then
        echo "❌ Cannot deploy without device connection"
        return 1
    fi
    
    echo ""
    echo "⚡ Fast Deploy (incremental build)..."
    echo "========================================"
    
    # Incremental build - only rebuilds changed files
    echo "🔨 Incremental build..."
    (cd "$APP_DIR" && ./gradlew assembleDebug --parallel --build-cache --configuration-cache)
    
    if [ $? -ne 0 ]; then
        echo "❌ Build failed!"
        return 1
    fi
    
    local apk_path="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
    if [ ! -f "$apk_path" ]; then
        echo "❌ APK not found at $apk_path"
        return 1
    fi
    
    echo "✅ Build complete!"
    echo "📦 APK size: $(du -h "$apk_path" | cut -f1)"
    echo ""
    echo "📱 Installing to device..."
    
    # Install with -r (replace) and -t (allow test packages)
    $ADB install -r -t "$apk_path"
    
    if [ $? -eq 0 ]; then
        echo "✅ Installation successful!"
        
        # Force stop and restart the app (debug package has .debug suffix)
        echo "🔄 Restarting app..."
        $ADB shell am force-stop com.rw251.pleasecharge.debug
        sleep 1
        $ADB shell am start -n com.rw251.pleasecharge.debug/com.rw251.pleasecharge.MainActivity
        
        echo ""
        echo "✅ Fast deploy complete!"
        echo "⏱️  Total time: ~15 seconds (vs ~2 minutes for full rebuild)"
        echo ""
        echo "💡 Tip: Your phone can stay connected to DHU via USB"
        echo "   while deploying wirelessly. See FAST_DEV_WORKFLOW.md"
        return 0
    else
        echo "❌ Installation failed!"
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
            $ADB shell am start -n com.rw251.pleasecharge.debug/com.rw251.pleasecharge.MainActivity

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
    echo "📋 Exporting logs and data from device..."
    echo "========================================"
    
    # Try both debug and release packages
    local debug_pkg="com.rw251.pleasecharge.debug"
    local release_pkg="com.rw251.pleasecharge"
    
    # Check which package is installed and has data
    local app_pkg=""
    if $ADB shell pm list packages | grep -q "^package:$debug_pkg$"; then
        if $ADB shell "run-as $debug_pkg test -d files && echo exists" 2>/dev/null | grep -q "exists"; then
            app_pkg="$debug_pkg"
            echo "📱 Using debug package: $debug_pkg"
        fi
    fi
    
    if [ -z "$app_pkg" ] && $ADB shell pm list packages | grep -q "^package:$release_pkg$"; then
        if $ADB shell "run-as $release_pkg test -d files && echo exists" 2>/dev/null | grep -q "exists"; then
            app_pkg="$release_pkg"
            echo "📱 Using release package: $release_pkg"
        fi
    fi
    
    if [ -z "$app_pkg" ]; then
        echo "❌ No app package found with data files"
        echo "   Make sure the app has been launched at least once"
        return 1
    fi

    # Local output paths
    local out_dir="$ROOT_DIR/export"
    mkdir -p "$out_dir"
    local local_log="$out_dir/app_log.txt"
    local local_csv="$out_dir/vehicle_data.csv"
    local local_log_old="$out_dir/app_log.txt.old"
    local local_csv_old="$out_dir/vehicle_data.csv.old"

    echo "📂 Pulling files to $out_dir"
    
    # Use exec-out for more reliable binary-safe transfer
    $ADB exec-out run-as "$app_pkg" cat files/app_log.txt > "$local_log" 2>/dev/null || rm -f "$local_log"
    $ADB exec-out run-as "$app_pkg" cat files/vehicle_data.csv > "$local_csv" 2>/dev/null || rm -f "$local_csv"
    $ADB exec-out run-as "$app_pkg" cat files/app_log.txt.old > "$local_log_old" 2>/dev/null || rm -f "$local_log_old"
    $ADB exec-out run-as "$app_pkg" cat files/vehicle_data.csv.old > "$local_csv_old" 2>/dev/null || rm -f "$local_csv_old"

    echo ""
    local found_any=false
    if [ -s "$local_log" ]; then
        echo "✅ App log exported: $local_log (size: $(du -h "$local_log" | cut -f1))"
        found_any=true
    else
        echo "⚠️  No app log found"
    fi
    if [ -s "$local_csv" ]; then
        echo "✅ CSV data exported: $local_csv (size: $(du -h "$local_csv" | cut -f1))"
        found_any=true
    else
        echo "⚠️  No CSV data found"
    fi
    if [ -s "$local_log_old" ]; then
        echo "ℹ️  Old app log: $local_log_old (size: $(du -h "$local_log_old" | cut -f1))"
        found_any=true
    fi
    if [ -s "$local_csv_old" ]; then
        echo "ℹ️  Old CSV data: $local_csv_old (size: $(du -h "$local_csv_old" | cut -f1))"
        found_any=true
    fi
    
    if [ "$found_any" = false ]; then
        echo ""
        echo "💡 No files found. Possible reasons:"
        echo "   • App was just installed and hasn't created files yet"
        echo "   • App needs to be launched to initialize logging"
        echo "   • Try running the app and then export again"
    fi

    echo ""
    echo "📄 Log tail (last 20 lines):"
    echo "----------------------------------------"
    if [ -f "$local_log" ]; then
        tail -20 "$local_log"
    else
        echo "(no log file - app may not have been launched yet)"
    fi
    return 0
}

# Function to show menu and get user choice
show_menu() {
    echo ""
    echo "Choose an option:"
    echo "1) Build APK (debug)"
    echo "2) Build and deploy to phone"
    echo "3) Build production release (Play Store)"
    echo "4) Export logs & data from phone"
    echo "5) Fast deploy (incremental build + restart)"
    echo "6) List connected devices"
    echo "7) Restart ADB server"
    echo "8) Exit"
    echo ""
    read -p "Enter choice [1-8]: " choice

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
            fast_deploy
            ;;
        6)
            list_devices
            ;;
        7)
            restart_adb
            ;;
        8)
            echo "👋 Goodbye!"
            exit 0
            ;;
        *)
            echo "❌ Invalid option. Please choose 1-8."
            return 1
            ;;
    esac
}

# Main script loop
while true; do
    show_menu
    echo ""
done
