#!/bin/bash
# OBD BLE Simulator Startup Script
# Uses D-Bus API - requires root but works WITH bluetooth service

echo "🚗 OBD BLE Simulator - IOS-Vlink"
echo "================================="

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo ""
    echo "❌ This script requires root privileges for BLE access."
    echo ""
    echo "Please run the following commands:"
    echo ""
    echo "  su"
    echo "  (enter root password)"
    echo "  source /home/richard/.nvm/nvm.sh && nvm use 22"
    echo "  node /home/richard/Development/Home/car-scanner/tools/obd-ble-simulator/simulator-dbus.js"
    echo ""
    exit 1
fi

# Navigate to the script directory
cd "$(dirname "$0")"

# Ensure D-Bus policy exists so BlueZ (root) can call us on our unique name
POLICY_FILE="/etc/dbus-1/system.d/com.obdsimulator.conf"
POLICY_FILE_ALT="/usr/share/dbus-1/system.d/com.obdsimulator.conf"
read -r -d '' POLICY_CONTENT <<'EOF'
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <!-- Allow the simulator (runs as root here) to own the name -->
  <policy user="root">
    <allow own="com.obdsimulator"/>
    <!-- Let BlueZ (root on some distros) send method calls, including to our unique name -->
    <allow send_destination="*"/>
    <allow send_type="method_call"/>
    <allow send_interface="org.freedesktop.DBus.ObjectManager"/>
    <allow send_interface="org.freedesktop.DBus.Properties"/>
  </policy>
  <!-- Allow bluetooth user (common bluetoothd user) -->
  <policy user="bluetooth">
    <allow send_destination="*"/>
    <allow send_type="method_call"/>
    <allow send_interface="org.freedesktop.DBus.ObjectManager"/>
    <allow send_interface="org.freedesktop.DBus.Properties"/>
  </policy>
  <!-- Allow any user to send to the well-known name -->
  <policy context="default">
    <allow send_destination="com.obdsimulator"/>
    <allow send_type="method_call"/>
  </policy>
</busconfig>
EOF

install_policy() {
    local target="$1"
    if ! [ -f "$target" ] || ! cmp -s <(echo "$POLICY_CONTENT") "$target"; then
        echo "🔧 Installing/updating D-Bus policy at $target ..."
        printf "%s" "$POLICY_CONTENT" > "$target"
        chmod 644 "$target"
        echo "✅ Policy installed/updated at $target"
        return 0
    fi
    return 1
}

changed=0
install_policy "$POLICY_FILE" && changed=1
install_policy "$POLICY_FILE_ALT" && changed=1

if [ "$changed" -eq 1 ]; then
    echo "🔧 Installing/updating D-Bus policy at $POLICY_FILE ..."
    echo "🔄 Restarting bluetoothd to apply policy..."
    systemctl restart bluetooth
    echo "ℹ️ If RegisterApplication still fails, restart the system bus (or reboot) once so it reloads policy files."
    sleep 1
fi

# Source nvm to get Node 22
export NVM_DIR="/home/richard/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"
nvm use 22 2>/dev/null

# Check for Node.js
if ! command -v node &> /dev/null; then
    echo "❌ Node.js not found. Please install Node.js 18+ first."
    exit 1
fi

# Ensure bluetooth service is running (D-Bus requires it)
echo "🔧 Ensuring bluetooth service is running..."
systemctl start bluetooth 2>/dev/null

# Wait for it to be ready
sleep 1

# Run the D-Bus simulator
echo "🚀 Starting simulator..."
node simulator-dbus.js
