/**
 * OBD BLE Simulator - D-Bus Version
 *
 * Full simulation of IOS-Vlink OBD-II adapter with all required services.
 * Simulates a 57kWh battery with realistic driving consumption.
 *
 * Run with: node simulator-dbus.js
 */

const dbus = require("dbus-next");
const readline = require("readline");

const { Interface: DBusInterface, ACCESS_READ } = dbus.interface;

// Service UUIDs matching the real IOS-Vlink device
const SERVICES = {
  main: "e7810a71-73ae-499d-8c15-faa9aef0c3f2",
  deviceInformation: "0000180a-0000-1000-8000-00805f9b34fb",
  unknown: "000018f0-0000-1000-8000-00805f9b34fb", // MG custom service
  genericAttribute: "00001801-0000-1000-8000-00805f9b34fb",
  genericAccess: "00001800-0000-1000-8000-00805f9b34fb",
};

// Standard characteristic UUIDs
const CHARS = {
  // Main OBD service characteristic
  obdReadWrite: "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
  // Device Information characteristics
  manufacturerName: "00002a29-0000-1000-8000-00805f9b34fb",
  modelNumber: "00002a24-0000-1000-8000-00805f9b34fb",
  firmwareRevision: "00002a26-0000-1000-8000-00805f9b34fb",
  // Generic Access characteristics
  deviceName: "00002a00-0000-1000-8000-00805f9b34fb",
  appearance: "00002a01-0000-1000-8000-00805f9b34fb",
  // Unknown service characteristic (mimic real device)
  unknownChar: "000018f1-0000-1000-8000-00805f9b34fb",
  // DEV-only: Simulated GPS characteristic
  devGps: "bef8d6c9-9c21-4c9e-b632-bd58c1009f00",
};

// D-Bus paths
const BLUEZ_SERVICE = "org.bluez";
const ADAPTER_PATH = "/org/bluez/hci0";
const ADVERT_PATH = "/com/obdsimulator/advertisement0";
const APP_PATH = "/com/obdsimulator";

let bus;
let mainCharInterface = null;
let advManager = null;
let gattManager = null;

// ============================================================================
// Vehicle Simulation - 57kWh battery with realistic driving
// ============================================================================

class VehicleSimulator {
  constructor() {
    // 57kWh battery
    this.batteryCapacity = 57; // kWh

    // Start with high SOC (80-100%) for realistic "just charged" scenario
    const startingSocPercent = 80 + Math.random() * 20;
    this._energyRemaining = (startingSocPercent / 100) * this.batteryCapacity; // kWh

    // SOH - slight degradation from new
    this._soh = 9700 + Math.floor(Math.random() * 200); // 97-99%

    // Battery state
    this._voltage = 1520; // ~380V

    // Temperature: start between 4-25°C, stored in car format (value/2 - 40 = °C)
    // So °C = 4-25 means value = (°C + 40) * 2 = 88-130
    const startTempC = 4 + Math.random() * 21; // 4-25°C
    this._batteryTemp = (startTempC + 40) * 2; // Convert to car format

    this._extTemp = 55; // 15°C
    this._odometer = 15000 + Math.floor(Math.random() * 10000);

    // Driving simulation
    this._mode = "static";
    this._speed = 0; // km/h (actual speed)
    this._lastUpdate = Date.now();

    // Request counter for sped-up drain simulation
    this._requestCount = 0;

    // Consumption: ~4 kWh per mile = ~2.5 kWh per km
    // At 100 km/h, that's 250 kW draw
    // More realistically, factor in speed^2 relationship for air resistance
    this.baseConsumption = 0.15; // kWh/km base (rolling resistance, etc)
    this.aeroFactor = 0.00003; // kWh/km per (km/h)^2

    // Start simulation loop
    setInterval(() => this._updateSimulation(), 1000);

    console.log(`🔋 Battery: ${this.batteryCapacity} kWh`);
    console.log(`🔋 Starting SOC: ${this.getSocPercent().toFixed(1)}%`);
    console.log(`🔋 Energy: ${this._energyRemaining.toFixed(2)} kWh`);
    console.log(`🌡️  Starting Temp: ${this.getBatteryTempCelsius().toFixed(1)}°C`);
  }

  // Called on each SOC/temp request to simulate sped-up drain
  onDataRequest() {
    this._requestCount++;

    // Drain SOC by 0-0.5% on each request (sped up simulation)
    const drainPercent = Math.random() * 0.5;
    const drainEnergy = (drainPercent / 100) * this.batteryCapacity;
    this._energyRemaining = Math.max(0, this._energyRemaining - drainEnergy);

    // Fluctuate temperature by ±0.3°C
    // In car format, 0.3°C = 0.6 units (since value/2 - 40 = °C)
    const tempChange = (Math.random() - 0.5) * 2 * 0.3; // -0.3 to +0.3°C
    const tempChangeUnits = tempChange * 2; // Convert to car format units
    this._batteryTemp = Math.max(88, Math.min(130, this._batteryTemp + tempChangeUnits));

    // Update voltage based on SOC
    const socPercent = this.getSocPercent();
    this._voltage = Math.floor(1400 + (socPercent / 100) * 200);

    console.log(
      `📊 Request #${this._requestCount}: SOC=${this.getSocPercent().toFixed(
        1
      )}% (-${drainPercent.toFixed(2)}%), Temp=${this.getBatteryTempCelsius().toFixed(1)}°C`
    );
  }

  getBatteryTempCelsius() {
    return this._batteryTemp / 2 - 40;
  }

  _updateSimulation() {
    const now = Date.now();
    const elapsed = (now - this._lastUpdate) / 1000 / 3600; // hours
    this._lastUpdate = now;

    if (this._mode === "driving" && this._speed > 0) {
      // Calculate consumption based on speed
      // consumption = base + aero * speed^2 (in kWh/km)
      const consumptionPerKm = this.baseConsumption + this.aeroFactor * this._speed * this._speed;
      const distanceTraveled = this._speed * elapsed; // km
      const energyUsed = consumptionPerKm * distanceTraveled;

      this._energyRemaining = Math.max(0, this._energyRemaining - energyUsed);
      this._odometer += distanceTraveled;

      // Update voltage based on SOC (lower voltage at low SOC)
      const socPercent = this.getSocPercent();
      this._voltage = Math.floor(1400 + (socPercent / 100) * 200); // 350-400V range

      // Increase battery temp while driving
      if (this._batteryTemp < 180) {
        // max 50°C
        this._batteryTemp += 0.05;
      }
    } else if (this._mode === "charging") {
      // Charging at ~50kW average
      const chargeRate = 50 / 3600; // kWh per second -> per hour for elapsed
      const energyAdded = chargeRate * elapsed * 3600;
      this._energyRemaining = Math.min(this.batteryCapacity, this._energyRemaining + energyAdded);
    }
  }

  getSocPercent() {
    return (this._energyRemaining / this.batteryCapacity) * 100;
  }

  // Raw SOC value (0-1000 scale used by the car)
  get soc() {
    return Math.floor(this.getSocPercent() * 10);
  }

  set soc(value) {
    this._energyRemaining = (value / 1000) * this.batteryCapacity;
  }

  get soh() {
    return this._soh;
  }
  set soh(value) {
    this._soh = Math.max(0, Math.min(10000, value));
  }

  get voltage() {
    return Math.floor(this._voltage);
  }

  // Current: (value - 40000) * 0.025 = Amps
  get current() {
    if (this._mode === "driving" && this._speed > 0) {
      // Calculate power draw based on speed
      const consumptionPerKm = this.baseConsumption + this.aeroFactor * this._speed * this._speed;
      const powerKw = consumptionPerKm * this._speed; // kW
      const voltage = this._voltage * 0.25; // actual voltage
      const amps = -((powerKw * 1000) / voltage); // negative = discharging
      return Math.floor(40000 + amps / 0.025);
    } else if (this._mode === "charging") {
      return 42000; // ~50A charging
    }
    return 40000; // 0A idle
  }

  get batteryTemp() {
    return Math.floor(this._batteryTemp);
  }
  set batteryTemp(value) {
    this._batteryTemp = value;
  }

  get speed() {
    // Return in car's format: (value - 20000) / 100 = km/h
    return Math.floor(20000 + this._speed * 100);
  }

  get extTemp() {
    return this._extTemp;
  }
  get odometer() {
    return Math.floor(this._odometer);
  }

  setMode(mode, speed = null) {
    const validModes = ["static", "charging", "driving"];
    if (validModes.includes(mode)) {
      this._mode = mode;
      if (mode === "driving") {
        this._speed = speed !== null ? speed : 50; // default 50 km/h
        console.log(`🚗 Driving at ${this._speed} km/h`);
      } else {
        this._speed = 0;
      }
      console.log(`Mode: ${mode}`);
    }
  }

  setSpeed(kmh) {
    this._speed = Math.max(0, Math.min(200, kmh));
    if (this._speed > 0) {
      this._mode = "driving";
    }
    console.log(`🚗 Speed: ${this._speed} km/h`);
  }

  getSummary() {
    return {
      mode: this._mode,
      soc: `${this.getSocPercent().toFixed(1)}%`,
      energy: `${this._energyRemaining.toFixed(2)} kWh`,
      soh: `${(this._soh / 100).toFixed(2)}%`,
      voltage: `${(this._voltage * 0.25).toFixed(1)}V`,
      current: `${((this.current - 40000) * 0.025).toFixed(1)}A`,
      batteryTemp: `${this.getBatteryTempCelsius().toFixed(1)}°C`,
      speed: `${this._speed} km/h`,
      odometer: `${Math.floor(this._odometer)} km`,
      requests: this._requestCount,
    };
  }

  static toHex(value, bytes = 2) {
    const hex = Math.floor(value).toString(16).toUpperCase();
    return hex.padStart(bytes * 2, "0");
  }

  static odometerToBytes(km) {
    const a = Math.floor(km / 65536);
    const b = Math.floor((km % 65536) / 256);
    const c = km % 256;
    return (
      VehicleSimulator.toHex(a, 1) + VehicleSimulator.toHex(b, 1) + VehicleSimulator.toHex(c, 1)
    );
  }
}

const vehicle = new VehicleSimulator();

// ============================================================================
// GPS Simulator - Simulates car movement along roads
// ============================================================================

class GPSSimulator {
  constructor() {
    // Starting point: Manchester city center area
    this._lat = 53.4808;
    this._lon = -2.2426;

    // Current heading (degrees, 0 = North, 90 = East)
    this._heading = Math.random() * 360;

    // Speed in km/h (will affect distance moved)
    this._speedKmh = 0;

    // Last update time
    this._lastUpdate = Date.now();

    // Road simulation: occasionally change direction (simulating turns)
    this._nextTurnDistance = this._randomTurnDistance();
    this._distanceSinceLastTurn = 0;

    // Update GPS position every second
    setInterval(() => this._updatePosition(), 1000);

    console.log(`📍 GPS Start: ${this._lat.toFixed(6)}, ${this._lon.toFixed(6)}`);
  }

  _randomTurnDistance() {
    // Turn every 100-500 meters
    return 0.1 + Math.random() * 0.4;
  }

  _updatePosition() {
    const now = Date.now();
    const elapsedHours = (now - this._lastUpdate) / 1000 / 3600;
    this._lastUpdate = now;

    if (this._speedKmh <= 0) return;

    // Distance traveled in km
    const distanceKm = this._speedKmh * elapsedHours;
    this._distanceSinceLastTurn += distanceKm;

    // Check if we should turn
    if (this._distanceSinceLastTurn >= this._nextTurnDistance) {
      // Make a turn: -90 to +90 degrees (mostly straight-ish, occasionally sharp)
      const turnAmount = (Math.random() - 0.5) * 90;
      this._heading = (this._heading + turnAmount + 360) % 360;
      this._distanceSinceLastTurn = 0;
      this._nextTurnDistance = this._randomTurnDistance();
    }

    // Add small random variation to heading (road curves)
    this._heading += (Math.random() - 0.5) * 5;
    this._heading = (this._heading + 360) % 360;

    // Convert heading to radians
    const headingRad = (this._heading * Math.PI) / 180;

    // Earth's radius in km
    const R = 6371;

    // Calculate new position
    // Latitude change
    const deltaLat = (distanceKm / R) * Math.cos(headingRad) * (180 / Math.PI);
    // Longitude change (adjusted for latitude)
    const deltaLon =
      ((distanceKm / R) * Math.sin(headingRad) * (180 / Math.PI)) /
      Math.cos((this._lat * Math.PI) / 180);

    this._lat += deltaLat;
    this._lon += deltaLon;

    // Keep within reasonable bounds (UK area)
    this._lat = Math.max(50, Math.min(58, this._lat));
    this._lon = Math.max(-8, Math.min(2, this._lon));
  }

  setSpeed(kmh) {
    this._speedKmh = Math.max(0, Math.min(200, kmh));
  }

  get lat() {
    return this._lat;
  }

  get lon() {
    return this._lon;
  }

  get speed() {
    return this._speedKmh;
  }

  // Get GPS data as a string for BLE transmission
  // Format: "lat,lon,speed" e.g. "53.480800,-2.242600,50.0"
  getGPSString() {
    return `${this._lat.toFixed(6)},${this._lon.toFixed(6)},${this._speedKmh.toFixed(1)}`;
  }

  getSummary() {
    return {
      lat: this._lat.toFixed(6),
      lon: this._lon.toFixed(6),
      heading: `${this._heading.toFixed(1)}°`,
      speed: `${this._speedKmh} km/h`,
    };
  }
}

const gpsSimulator = new GPSSimulator();

// Sync GPS speed with vehicle speed
setInterval(() => {
  gpsSimulator.setSpeed(vehicle._speed);
}, 1000);

// ============================================================================
// OBD Command Processing
// ============================================================================

function processCommand(command) {
  const cmd = command.toUpperCase().trim();

  if (cmd.startsWith("AT")) {
    return processATCommand(cmd);
  }
  if (cmd.startsWith("22")) {
    return processOBDPID(cmd);
  }
  return "?";
}

function processATCommand(command) {
  const cmd = command.substring(2);
  console.log(`🔧 AT Command: ${cmd}`);

  const responses = {
    Z: "ELM327 v1.5",
    D: "OK",
    E0: "OK",
    E1: "OK",
    S0: "OK",
    S1: "OK",
    H0: "OK",
    H1: "OK",
    L0: "OK",
    L1: "OK",
    AT2: "OK",
    SP0: "OK",
    AL: "OK",
    I: "ELM327 v1.5",
    "@1": "OBDII to RS232 Interpreter",
    RV: "12.5V",
  };

  if (responses[cmd]) return responses[cmd];
  if (cmd.startsWith("SH") || cmd.startsWith("FCSH")) return "OK";
  return "OK";
}

function processOBDPID(command) {
  const pid = command.substring(2).toUpperCase();

  switch (pid) {
    case "B046": // SOC
      vehicle.onDataRequest(); // Trigger sped-up drain and temp fluctuation
      const socVal = vehicle.soc;
      console.log(`🔋 SOC: ${(socVal / 10).toFixed(1)}% (raw: ${socVal})`);
      return `62B046${VehicleSimulator.toHex(socVal, 2)}`;
    case "B061": // SOH
      return `62B061${VehicleSimulator.toHex(vehicle.soh, 2)}`;
    case "B042": // Voltage
      return `62B042${VehicleSimulator.toHex(vehicle.voltage, 2)}`;
    case "B043": // Current
      return `62B043${VehicleSimulator.toHex(vehicle.current, 2)}`;
    case "B056": // Battery temp
      const tempVal = vehicle.batteryTemp;
      console.log(`🌡️  Temp: ${vehicle.getBatteryTempCelsius().toFixed(1)}°C (raw: ${tempVal})`);
      return `62B056${VehicleSimulator.toHex(tempVal, 2)}`;
    case "B048":
      return "62B0480000";
    case "B101": // Odometer
      return `62B101${VehicleSimulator.odometerToBytes(vehicle.odometer)}`;
    case "BA00": // Speed
      return `62BA00${VehicleSimulator.toHex(vehicle.speed, 2)}`;
    case "BB05": // External temp
      return `62BB05${VehicleSimulator.toHex(vehicle.extTemp, 1)}`;
    case "DEV0": // DEV-only: Simulated GPS (lat,lon,speed)
      const gpsData = gpsSimulator.getGPSString();
      console.log(`📍 GPS: ${gpsData}`);
      return `62DEV0${gpsData}`;
    default:
      return "NO DATA";
  }
}

// ============================================================================
// D-Bus Advertisement
// ============================================================================

class Advertisement extends DBusInterface {
  constructor() {
    super("org.bluez.LEAdvertisement1");
  }
}

Advertisement.prototype.Release = function () {
  console.log("📢 Advertisement released");
};

Advertisement.configureMembers({
  properties: {
    Type: { signature: "s", access: ACCESS_READ },
    LocalName: { signature: "s", access: ACCESS_READ },
    ServiceUUIDs: { signature: "as", access: ACCESS_READ },
    IncludeTxPower: { signature: "b", access: ACCESS_READ },
  },
  methods: {
    Release: { inSignature: "", outSignature: "" },
  },
});
Advertisement.prototype.Type = "peripheral";
Advertisement.prototype.LocalName = "IOS-Vlink-DEV";
Advertisement.prototype.ServiceUUIDs = Object.values(SERVICES);
Advertisement.prototype.IncludeTxPower = true;

// Generic Properties helper so BlueZ can call Properties.Get/GetAll
function createPropertiesInterface(target, ifaceName, propDefs) {
  class Props extends DBusInterface {
    constructor() {
      super("org.freedesktop.DBus.Properties");
    }
  }

  Props.configureMembers({
    methods: {
      Get: { inSignature: "ss", outSignature: "v" },
      GetAll: { inSignature: "s", outSignature: "a{sv}" },
      Set: { inSignature: "ssv", outSignature: "" },
    },
    signals: {
      PropertiesChanged: { signature: "sa{sv}as" },
    },
  });

  const propsInstance = new Props();

  propsInstance.Get = function (iface, prop) {
    try {
      console.log(`⟲ Properties.Get called for iface=${iface} prop=${prop}`);
      if (iface !== ifaceName || !propDefs[prop]) throw new Error("Unknown property");
      const def = propDefs[prop];
      const val = def.get();
      const wrapped = val instanceof dbus.Variant ? val : new dbus.Variant(def.sig, val);
      console.log(`⟲ Properties.Get -> sig=${def.sig} val=${JSON.stringify(val)}`);
      return wrapped;
    } catch (e) {
      console.error("⚠️ Properties.Get error:", e && e.message ? e.message : e);
      throw e;
    }
  };

  propsInstance.GetAll = function (iface) {
    try {
      console.log(`⟲ Properties.GetAll called for iface=${iface}`);
      if (iface !== ifaceName) return {};
      const out = {};
      Object.keys(propDefs).forEach((key) => {
        const def = propDefs[key];
        const val = def.get();
        out[key] = val instanceof dbus.Variant ? val : new dbus.Variant(def.sig, val);
      });
      console.log(`⟲ Properties.GetAll -> ${Object.keys(out).join(",")}`);
      return out;
    } catch (e) {
      console.error("⚠️ Properties.GetAll error:", e && e.message ? e.message : e);
      throw e;
    }
  };

  propsInstance.Set = function () {
    throw new Error("Read-only");
  };

  return propsInstance;
}

// ============================================================================
// GATT Application with all services
// ============================================================================

class GattApplication extends DBusInterface {
  constructor() {
    super("org.freedesktop.DBus.ObjectManager");
  }
}
GattApplication.prototype.GetManagedObjects = function () {
  console.log("🔍 GetManagedObjects called — building object map for BlueZ");
  const objects = {};
  // Main OBD Service
  objects[`${APP_PATH}/service_main`] = {
    "org.bluez.GattService1": {
      UUID: new dbus.Variant("s", SERVICES.main),
      Primary: new dbus.Variant("b", true),
      Characteristics: new dbus.Variant("ao", [`${APP_PATH}/service_main/char_obd`]),
    },
  };
  objects[`${APP_PATH}/service_main/char_obd`] = {
    "org.bluez.GattCharacteristic1": {
      UUID: new dbus.Variant("s", CHARS.obdReadWrite),
      Service: new dbus.Variant("o", `${APP_PATH}/service_main`),
      Flags: new dbus.Variant("as", [
        "read",
        "write",
        "write-without-response",
        "notify",
        "indicate",
      ]),
    },
  };

  // Device Information Service
  objects[`${APP_PATH}/service_devinfo`] = {
    "org.bluez.GattService1": {
      UUID: new dbus.Variant("s", SERVICES.deviceInformation),
      Primary: new dbus.Variant("b", true),
      Characteristics: new dbus.Variant("ao", [
        `${APP_PATH}/service_devinfo/char_mfr`,
        `${APP_PATH}/service_devinfo/char_model`,
        `${APP_PATH}/service_devinfo/char_fw`,
      ]),
    },
  };
  objects[`${APP_PATH}/service_devinfo/char_mfr`] = {
    "org.bluez.GattCharacteristic1": {
      UUID: new dbus.Variant("s", CHARS.manufacturerName),
      Service: new dbus.Variant("o", `${APP_PATH}/service_devinfo`),
      Flags: new dbus.Variant("as", ["read"]),
    },
  };
  objects[`${APP_PATH}/service_devinfo/char_model`] = {
    "org.bluez.GattCharacteristic1": {
      UUID: new dbus.Variant("s", CHARS.modelNumber),
      Service: new dbus.Variant("o", `${APP_PATH}/service_devinfo`),
      Flags: new dbus.Variant("as", ["read"]),
    },
  };
  objects[`${APP_PATH}/service_devinfo/char_fw`] = {
    "org.bluez.GattCharacteristic1": {
      UUID: new dbus.Variant("s", CHARS.firmwareRevision),
      Service: new dbus.Variant("o", `${APP_PATH}/service_devinfo`),
      Flags: new dbus.Variant("as", ["read"]),
    },
  };

  // Unknown/MG Custom Service
  objects[`${APP_PATH}/service_unknown`] = {
    "org.bluez.GattService1": {
      UUID: new dbus.Variant("s", SERVICES.unknown),
      Primary: new dbus.Variant("b", true),
      Characteristics: new dbus.Variant("ao", [`${APP_PATH}/service_unknown/char0`]),
    },
  };
  objects[`${APP_PATH}/service_unknown/char0`] = {
    "org.bluez.GattCharacteristic1": {
      UUID: new dbus.Variant("s", CHARS.unknownChar),
      Service: new dbus.Variant("o", `${APP_PATH}/service_unknown`),
      Flags: new dbus.Variant("as", ["read"]),
    },
  };

  return objects;
};

GattApplication.configureMembers({
  methods: {
    GetManagedObjects: { inSignature: "", outSignature: "a{oa{sa{sv}}}" },
  },
});

// ============================================================================
// GATT Service Classes
// ============================================================================

function createServiceClass(uuid) {
  class Service extends DBusInterface {
    constructor(charPaths = []) {
      super("org.bluez.GattService1");
      this._charPaths = charPaths;
    }
  }

  Service.configureMembers({
    properties: {
      UUID: { signature: "s", access: ACCESS_READ },
      Primary: { signature: "b", access: ACCESS_READ },
      Characteristics: { signature: "ao", access: ACCESS_READ },
    },
  });

  Service.prototype.UUID = uuid;
  Service.prototype.Primary = true;
  Object.defineProperty(Service.prototype, "Characteristics", {
    get: function () {
      return this._charPaths;
    },
  });

  return Service;
}

// ============================================================================
// GATT Characteristic Classes
// ============================================================================

function createReadOnlyCharClass(uuid, servicePath, value) {
  class Char extends DBusInterface {
    constructor() {
      super("org.bluez.GattCharacteristic1");
      this._value = Buffer.from(value);
    }
  }

  Char.prototype.ReadValue = function (options) {
    return [...this._value];
  };

  Char.configureMembers({
    properties: {
      UUID: { signature: "s", access: ACCESS_READ },
      Service: { signature: "o", access: ACCESS_READ },
      Flags: { signature: "as", access: ACCESS_READ },
    },
    methods: {
      ReadValue: { inSignature: "a{sv}", outSignature: "ay" },
    },
  });

  Char.prototype.UUID = uuid;
  Char.prototype.Service = servicePath;
  Char.prototype.Flags = ["read"];

  return Char;
}

// Main OBD characteristic with read/write/notify
class OBDCharacteristic extends DBusInterface {
  constructor() {
    super("org.bluez.GattCharacteristic1");
    this._value = Buffer.from(">");
    this._notifying = false;
    this._promptMode = (process.env.OBD_PROMPT_MODE || "inline").toLowerCase(); // inline | split
  }
}

OBDCharacteristic.prototype.UUID = CHARS.obdReadWrite;
OBDCharacteristic.prototype.Service = `${APP_PATH}/service_main`;
OBDCharacteristic.prototype.Flags = [
  "read",
  "write",
  "write-without-response",
  "notify",
  "indicate",
];
OBDCharacteristic.prototype.Notifying = false;

OBDCharacteristic.prototype.ReadValue = function (options) {
  const valueStr = this._value.toString("utf-8");
  console.log(
    `📖 Read request → returning: ${JSON.stringify(valueStr)} (${this._value.length} bytes)`
  );
  return [...this._value];
};

OBDCharacteristic.prototype.WriteValue = function (value, options) {
  const command = Buffer.from(value)
    .toString("utf-8")
    .replace(/\r?\n?/g, "")
    .trim();
  console.log(`📥 Received: "${command}"`);

  const response = processCommand(command);
  console.log(`📤 Response: "${response}"`);

  // Send response with just \r (no prompt) - PWA strips \r before parsing
  this._value = Buffer.from(response + "\r");
  console.log(
    `   → Notification payload: ${JSON.stringify(response + "\r")} (${this._value.length} bytes)`
  );
  console.log(`   → Hex: ${this._value.toString("hex")}`);

  if (this._notifying) {
    try {
      console.log("⟲ Emitting PropertiesChanged (Value)", {
        len: this._value.length,
        notifying: this._notifying,
      });
      if (mainCharInterface) {
        DBusInterface.emitPropertiesChanged(mainCharInterface, { Value: [...this._value] }, []);
        console.log("   ✓ PropertiesChanged emitted successfully");
      } else {
        console.error("   ✗ mainCharInterface not initialized!");
      }
    } catch (e) {
      console.error("   ✗ PropertiesChanged error:", e.message);
    }

    // Send prompt separately after a short delay
    setTimeout(() => {
      try {
        this._value = Buffer.from(">");
        console.log("⟲ Emitting PropertiesChanged (prompt)");
        if (mainCharInterface) {
          DBusInterface.emitPropertiesChanged(mainCharInterface, { Value: [...this._value] }, []);
          console.log("   ✓ Prompt emitted successfully");
        }
      } catch (e) {
        console.error("   ✗ Prompt error:", e.message);
      }
    }, 100);
  } else {
    console.log("   ⚠️  Not notifying (client not subscribed)");
  }
};

OBDCharacteristic.prototype.StartNotify = function () {
  console.log("📡 Client subscribed to notifications");
  console.log("   → Setting _notifying=true, will emit on writes");
  this._notifying = true;
  this.Notifying = true;
  if (mainCharInterface) {
    try {
      console.log("   → Emitting Notifying=true signal");
      DBusInterface.emitPropertiesChanged(mainCharInterface, { Notifying: true }, []);
      console.log("   ✓ Notifying signal emitted");
    } catch (e) {
      console.error("   ⚠️  PropertiesChanged error:", e.message);
    }
  }
};

OBDCharacteristic.prototype.StopNotify = function () {
  console.log("📡 Client unsubscribed");
  this._notifying = false;
  this.Notifying = false;
  if (mainCharInterface) {
    try {
      DBusInterface.emitPropertiesChanged(mainCharInterface, { Notifying: false }, []);
    } catch (e) {
      /* ignore */
    }
  }
};

OBDCharacteristic.configureMembers({
  properties: {
    UUID: { signature: "s", access: ACCESS_READ },
    Service: { signature: "o", access: ACCESS_READ },
    Flags: { signature: "as", access: ACCESS_READ },
    Value: { signature: "ay", access: ACCESS_READ },
    Notifying: { signature: "b", access: ACCESS_READ },
  },
  methods: {
    ReadValue: { inSignature: "a{sv}", outSignature: "ay" },
    WriteValue: { inSignature: "aya{sv}", outSignature: "" },
    StartNotify: { inSignature: "", outSignature: "" },
    StopNotify: { inSignature: "", outSignature: "" },
  },
  signals: {
    PropertiesChanged: { signature: "sa{sv}as" },
  },
});

// ============================================================================
// Main Application
// ============================================================================
// Introspectable helper so BlueZ can introspect exported objects
class Introspectable extends DBusInterface {
  constructor(xml) {
    super("org.freedesktop.DBus.Introspectable");
    this._xml = xml;
  }
}
// Return static XML so BlueZ can introspect our exported objects
Introspectable.prototype.Introspect = function () {
  return this._xml || "<node></node>";
};
Introspectable.configureMembers({
  methods: { Introspect: { inSignature: "", outSignature: "s" } },
});

// --------------------------------------------------------------------------
// BlueZ robustness helpers: auto-(re)register on daemon restarts
// --------------------------------------------------------------------------
async function getDbusInterface() {
  const dbusObj = await bus.getProxyObject("org.freedesktop.DBus", "/org/freedesktop/DBus");
  return dbusObj.getInterface("org.freedesktop.DBus");
}

async function connectBluezAndRegister() {
  try {
    const dbusIface = await getDbusInterface();
    const hasOwner = await dbusIface.NameHasOwner(BLUEZ_SERVICE);
    if (!hasOwner) {
      console.log("🔄 BlueZ not present on D-Bus yet; retrying in 1s...");
      setTimeout(connectBluezAndRegister, 1000);
      return;
    }

    // Acquire managers from the adapter path
    let bluezObj;
    try {
      bluezObj = await bus.getProxyObject(BLUEZ_SERVICE, ADAPTER_PATH);
    } catch (e) {
      console.log("🔄 Adapter not ready (hci0). Retrying in 1s...");
      setTimeout(connectBluezAndRegister, 1000);
      return;
    }
    advManager = bluezObj.getInterface("org.bluez.LEAdvertisingManager1");
    gattManager = bluezObj.getInterface("org.bluez.GattManager1");
    console.log("✅ Connected to BlueZ managers");

    // Register GATT application (safe to call again on restart)
    try {
      await new Promise((resolve) => setTimeout(resolve, 200));
      await gattManager.RegisterApplication(APP_PATH, {});
      console.log("✅ GATT application registered");
    } catch (err) {
      console.log("⚠️ GATT registration issue:", err && err.message ? err.message : err);
    }

    // Register advertisement (re-register if needed)
    try {
      await advManager.RegisterAdvertisement(ADVERT_PATH, {});
      console.log("✅ Advertisement registered");
    } catch (err) {
      if (err && err.message && err.message.includes("AlreadyExists")) {
        try {
          await advManager.UnregisterAdvertisement(ADVERT_PATH);
          await advManager.RegisterAdvertisement(ADVERT_PATH, {});
          console.log("✅ Advertisement re-registered");
        } catch (e) {
          console.error("Failed to re-register advertisement:", e);
        }
      } else {
        console.error("Advertisement registration error:", err);
      }
    }
  } catch (e) {
    console.error("❌ connectBluezAndRegister error:", e && e.message ? e.message : e);
    setTimeout(connectBluezAndRegister, 1000);
  }
}

async function watchBluezRestarts() {
  try {
    const dbusIface = await getDbusInterface();
    dbusIface.on("NameOwnerChanged", (name, oldOwner, newOwner) => {
      if (name !== BLUEZ_SERVICE) return;
      if (!newOwner) {
        console.log("⚠️ BlueZ disappeared from D-Bus (org.bluez). Waiting to re-register...");
      } else {
        console.log("🔁 BlueZ appeared on D-Bus (org.bluez). Re-registering...");
        connectBluezAndRegister();
      }
    });
  } catch (e) {
    console.error("⚠️ Failed to attach BlueZ restart watcher:", e && e.message ? e.message : e);
  }
}

async function main() {
  console.log("\n🚗 OBD BLE Simulator (D-Bus) - IOS-Vlink");
  console.log("==========================================\n");

  try {
    bus = dbus.systemBus();
    // Take ownership so BlueZ can send us method calls on the system bus
    try {
      await bus.requestName("com.obdsimulator");
      setTimeout(() => {
        const unique =
          (bus.connection && (bus.connection.uniqueName || bus.connection._name)) ||
          (bus._connection && (bus._connection.uniqueName || bus._connection._name)) ||
          bus.uniqueName ||
          "unknown";
        console.log("✅ D-Bus name acquired: com.obdsimulator (unique:", unique + ")");
      }, 50);
    } catch (e) {
      console.log("⚠️ Could not acquire com.obdsimulator bus name:", e.message || e);
    }

    // Log incoming messages targeting our app path for debugging
    try {
      bus.connection.on("message", (msg) => {
        try {
          if (msg && msg.path && typeof msg.path === "string" && msg.path.indexOf(APP_PATH) === 0) {
            console.log(
              "⟲ D-Bus incoming message for APP_PATH:",
              msg.interface,
              msg.member,
              msg.path
            );
          }
        } catch (e) {
          /* ignore */
        }
      });
    } catch (e) {
      // best-effort logging
    }

    // Set up watch for BlueZ restarts and (re)register managers/advertising
    await watchBluezRestarts();

    // Export Advertisement
    const advertisement = new Advertisement();
    bus.export(ADVERT_PATH, advertisement);
    bus.export(
      ADVERT_PATH,
      createPropertiesInterface(advertisement, "org.bluez.LEAdvertisement1", {
        Type: { sig: "s", get: () => advertisement.Type },
        LocalName: { sig: "s", get: () => advertisement.LocalName },
        ServiceUUIDs: { sig: "as", get: () => advertisement.ServiceUUIDs },
        IncludeTxPower: { sig: "b", get: () => advertisement.IncludeTxPower },
      })
    );

    // Export GATT Application (ObjectManager)
    const gattApp = new GattApplication();
    bus.export(APP_PATH, gattApp);

    // Export all services and characteristics

    // Main OBD Service
    const MainService = createServiceClass(SERVICES.main);
    const mainService = new MainService([`${APP_PATH}/service_main/char_obd`]);
    bus.export(`${APP_PATH}/service_main`, mainService);
    // main service exported (Properties interface provided below)
    bus.export(
      `${APP_PATH}/service_main`,
      createPropertiesInterface(mainService, "org.bluez.GattService1", {
        UUID: { sig: "s", get: () => mainService.UUID },
        Primary: { sig: "b", get: () => mainService.Primary },
        Characteristics: { sig: "ao", get: () => mainService.Characteristics },
      })
    );

    mainCharInterface = new OBDCharacteristic();
    bus.export(`${APP_PATH}/service_main/char_obd`, mainCharInterface);
    // main characteristic exported (Properties interface provided below)
    const mainCharProps = createPropertiesInterface(
      mainCharInterface,
      "org.bluez.GattCharacteristic1",
      {
        UUID: { sig: "s", get: () => mainCharInterface.UUID },
        Service: { sig: "o", get: () => mainCharInterface.Service },
        Flags: { sig: "as", get: () => mainCharInterface.Flags },
        Value: { sig: "ay", get: () => [...mainCharInterface._value] },
        Notifying: { sig: "b", get: () => mainCharInterface.Notifying },
      }
    );
    bus.export(`${APP_PATH}/service_main/char_obd`, mainCharProps);
    // keep reference so characteristic can emit PropertiesChanged on the exported Props interface
    mainCharInterface._props = mainCharProps;

    // Device Information Service
    const DevInfoService = createServiceClass(SERVICES.deviceInformation);
    const devInfoService = new DevInfoService([
      `${APP_PATH}/service_devinfo/char_mfr`,
      `${APP_PATH}/service_devinfo/char_model`,
      `${APP_PATH}/service_devinfo/char_fw`,
    ]);
    bus.export(`${APP_PATH}/service_devinfo`, devInfoService);
    bus.export(
      `${APP_PATH}/service_devinfo`,
      createPropertiesInterface(devInfoService, "org.bluez.GattService1", {
        UUID: { sig: "s", get: () => devInfoService.UUID },
        Primary: { sig: "b", get: () => devInfoService.Primary },
        Characteristics: { sig: "ao", get: () => devInfoService.Characteristics },
      })
    );

    const MfrChar = createReadOnlyCharClass(
      CHARS.manufacturerName,
      `${APP_PATH}/service_devinfo`,
      "Vlink"
    );
    const mfrCharObj = new MfrChar();
    bus.export(`${APP_PATH}/service_devinfo/char_mfr`, mfrCharObj);
    bus.export(
      `${APP_PATH}/service_devinfo/char_mfr`,
      createPropertiesInterface(mfrCharObj, "org.bluez.GattCharacteristic1", {
        UUID: { sig: "s", get: () => mfrCharObj.UUID },
        Service: { sig: "o", get: () => mfrCharObj.Service },
        Flags: { sig: "as", get: () => mfrCharObj.Flags },
      })
    );

    const ModelChar = createReadOnlyCharClass(
      CHARS.modelNumber,
      `${APP_PATH}/service_devinfo`,
      "IOS-Vlink-DEV"
    );
    const modelCharObj = new ModelChar();
    bus.export(`${APP_PATH}/service_devinfo/char_model`, modelCharObj);
    bus.export(
      `${APP_PATH}/service_devinfo/char_model`,
      createPropertiesInterface(modelCharObj, "org.bluez.GattCharacteristic1", {
        UUID: { sig: "s", get: () => modelCharObj.UUID },
        Service: { sig: "o", get: () => modelCharObj.Service },
        Flags: { sig: "as", get: () => modelCharObj.Flags },
      })
    );

    const FwChar = createReadOnlyCharClass(
      CHARS.firmwareRevision,
      `${APP_PATH}/service_devinfo`,
      "1.5"
    );
    const fwCharObj = new FwChar();
    bus.export(`${APP_PATH}/service_devinfo/char_fw`, fwCharObj);
    bus.export(
      `${APP_PATH}/service_devinfo/char_fw`,
      createPropertiesInterface(fwCharObj, "org.bluez.GattCharacteristic1", {
        UUID: { sig: "s", get: () => fwCharObj.UUID },
        Service: { sig: "o", get: () => fwCharObj.Service },
        Flags: { sig: "as", get: () => fwCharObj.Flags },
      })
    );

    // Unknown/MG Service
    const UnknownService = createServiceClass(SERVICES.unknown);
    const unknownService = new UnknownService([`${APP_PATH}/service_unknown/char0`]);
    bus.export(`${APP_PATH}/service_unknown`, unknownService);
    bus.export(
      `${APP_PATH}/service_unknown`,
      createPropertiesInterface(unknownService, "org.bluez.GattService1", {
        UUID: { sig: "s", get: () => unknownService.UUID },
        Primary: { sig: "b", get: () => unknownService.Primary },
        Characteristics: { sig: "ao", get: () => unknownService.Characteristics },
      })
    );

    const UnknownChar = createReadOnlyCharClass(
      CHARS.unknownChar,
      `${APP_PATH}/service_unknown`,
      "\x00\x00"
    );
    const unknownCharObj = new UnknownChar();
    bus.export(`${APP_PATH}/service_unknown/char0`, unknownCharObj);
    bus.export(
      `${APP_PATH}/service_unknown/char0`,
      createPropertiesInterface(unknownCharObj, "org.bluez.GattCharacteristic1", {
        UUID: { sig: "s", get: () => unknownCharObj.UUID },
        Service: { sig: "o", get: () => unknownCharObj.Service },
        Flags: { sig: "as", get: () => unknownCharObj.Flags },
      })
    );

    // Note: GAP/GATT (Generic Access / Generic Attribute) services are handled by BlueZ.

    console.log("✅ All services and characteristics exported");

    // Disable BR/EDR discoverability while keeping pairable off
    // This prevents Android from seeing us as a Classic Bluetooth device
    try {
      const adapterObj = await bus.getProxyObject(BLUEZ_SERVICE, ADAPTER_PATH);
      const adapterProps = adapterObj.getInterface("org.freedesktop.DBus.Properties");

      // Make adapter non-discoverable and non-pairable for BR/EDR
      // LE advertising will still work via LEAdvertisingManager
      await adapterProps.Set("org.bluez.Adapter1", "Discoverable", new dbus.Variant("b", false));
      await adapterProps.Set("org.bluez.Adapter1", "Pairable", new dbus.Variant("b", false));
      console.log("✅ Disabled BR/EDR discoverability (LE advertising only)");
    } catch (e) {
      console.log("⚠️  Could not configure adapter discoverability:", e.message);
    }

    // Kick initial registration (and retry until BlueZ is ready)
    connectBluezAndRegister();

    console.log('\n📱 Device is now discoverable as "IOS-Vlink" (BLE only)');
    console.log("📊 Battery:", vehicle.getSummary());
    console.log("\nCommands: drive <speed>, charge, stop, status, soc <0-100>, help\n");

    startConsole();
  } catch (err) {
    console.error("❌ Error:", err && err.message ? err.message : err);
    if (err && err.stack) console.error(err.stack);
    process.exit(1);
  }
}

function startConsole() {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    prompt: "obd> ",
  });

  rl.prompt();

  rl.on("line", (line) => {
    const args = line.trim().split(/\s+/);
    const cmd = args[0] ? args[0].toLowerCase() : "";

    switch (cmd) {
      case "drive": {
        const speed = parseInt(args[1]) || 50;
        vehicle.setMode("driving", speed);
        gpsSimulator.setSpeed(speed);
        break;
      }
      case "charge":
        vehicle.setMode("charging");
        gpsSimulator.setSpeed(0);
        console.log("🔌 Charging started");
        break;
      case "stop":
        vehicle.setMode("static");
        gpsSimulator.setSpeed(0);
        console.log("🅿️ Stopped");
        break;
      case "status":
        console.log("🚗 Vehicle:", JSON.stringify(vehicle.getSummary(), null, 2));
        console.log("📍 GPS:", JSON.stringify(gpsSimulator.getSummary(), null, 2));
        break;
      case "gps":
        console.log("📍 GPS:", JSON.stringify(gpsSimulator.getSummary(), null, 2));
        break;
      case "soc": {
        const soc = parseFloat(args[1]);
        if (!isNaN(soc) && soc >= 0 && soc <= 100) {
          vehicle.soc = soc * 10;
          console.log(`🔋 SOC set to ${soc}%`);
        } else {
          console.log("Usage: soc <0-100>");
        }
        break;
      }
      case "speed": {
        const s = parseInt(args[1]) || 0;
        vehicle.setSpeed(s);
        gpsSimulator.setSpeed(s);
        break;
      }
      case "help":
        console.log(`
Commands:
  drive <speed>  - Start driving at speed km/h (default 50)
  speed <kmh>    - Change speed while driving
  charge         - Start charging
  stop           - Stop driving/charging
  status         - Show vehicle and GPS state
  gps            - Show GPS state only
  soc <0-100>    - Set SOC percentage
  help           - Show this help
  exit           - Exit simulator
        `);
        break;
      case "exit":
      case "quit":
        process.exit(0);
      case "":
        break;
      default:
        console.log(`Unknown command: ${cmd}`);
    }
    rl.prompt();
  });

  rl.on("close", () => process.exit(0));
}

process.on("SIGINT", () => process.exit(0));
main();
