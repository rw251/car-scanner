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
};

// D-Bus paths
const BLUEZ_SERVICE = "org.bluez";
const ADAPTER_PATH = "/org/bluez/hci0";
const ADVERT_PATH = "/com/obdsimulator/advertisement0";
const APP_PATH = "/com/obdsimulator";

let bus;
let mainCharInterface = null;

// ============================================================================
// Vehicle Simulation - 57kWh battery with realistic driving
// ============================================================================

class VehicleSimulator {
  constructor() {
    // 57kWh battery
    this.batteryCapacity = 57; // kWh

    // Random starting SOC between 40-95%
    const startingSocPercent = 40 + Math.random() * 55;
    this._energyRemaining = (startingSocPercent / 100) * this.batteryCapacity; // kWh

    // SOH - slight degradation from new
    this._soh = 9700 + Math.floor(Math.random() * 200); // 97-99%

    // Battery state
    this._voltage = 1520; // ~380V
    this._batteryTemp = 140; // 30°C
    this._extTemp = 55; // 15°C
    this._odometer = 15000 + Math.floor(Math.random() * 10000);

    // Driving simulation
    this._mode = "static";
    this._speed = 0; // km/h (actual speed)
    this._lastUpdate = Date.now();

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
      batteryTemp: `${(this._batteryTemp / 2 - 40).toFixed(1)}°C`,
      speed: `${this._speed} km/h`,
      odometer: `${Math.floor(this._odometer)} km`,
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
      return `62B056${VehicleSimulator.toHex(vehicle.batteryTemp, 2)}`;
    case "B048":
      return "62B0480000";
    case "B101": // Odometer
      return `62B101${VehicleSimulator.odometerToBytes(vehicle.odometer)}`;
    case "BA00": // Speed
      return `62BA00${VehicleSimulator.toHex(vehicle.speed, 2)}`;
    case "BB05": // External temp
      return `62BB05${VehicleSimulator.toHex(vehicle.extTemp, 1)}`;
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
Advertisement.prototype.LocalName = "IOS-Vlink";
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
  });

  Props.prototype.Get = function (iface, prop) {
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

  Props.prototype.GetAll = function (iface) {
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

  Props.prototype.Set = function () {
    throw new Error("Read-only");
  };

  return new Props();
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
      Flags: new dbus.Variant("as", ["read", "write", "write-without-response", "notify"]),
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

  // Generic Attribute Service
  // Note: Do not export Generic Attribute Service here; BlueZ manages GATT/GAP.

  // Note: Do not export Generic Access (GAP) service or its characteristics; BlueZ handles these.

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
  }
}

OBDCharacteristic.prototype.UUID = CHARS.obdReadWrite;
OBDCharacteristic.prototype.Service = `${APP_PATH}/service_main`;
OBDCharacteristic.prototype.Flags = ["read", "write", "write-without-response", "notify"];
OBDCharacteristic.prototype.Notifying = false;

OBDCharacteristic.prototype.ReadValue = function (options) {
  console.log("📖 Read request");
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

  this._value = Buffer.from(response + "\r>");

  if (this._notifying) {
    try {
      this.PropertiesChanged(
        "org.bluez.GattCharacteristic1",
        {
          Value: new dbus.Variant("ay", [...this._value]),
        },
        []
      );
    } catch (e) {
      // Signal sent
    }
  }
};

OBDCharacteristic.prototype.StartNotify = function () {
  console.log("📡 Client subscribed to notifications");
  this._notifying = true;
  this.Notifying = true;
};

OBDCharacteristic.prototype.StopNotify = function () {
  console.log("📡 Client unsubscribed");
  this._notifying = false;
  this.Notifying = false;
};

OBDCharacteristic.configureMembers({
  properties: {
    UUID: { signature: "s", access: ACCESS_READ },
    Service: { signature: "o", access: ACCESS_READ },
    Flags: { signature: "as", access: ACCESS_READ },
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

    const bluezObj = await bus.getProxyObject(BLUEZ_SERVICE, ADAPTER_PATH);
    const advManager = bluezObj.getInterface("org.bluez.LEAdvertisingManager1");
    const gattManager = bluezObj.getInterface("org.bluez.GattManager1");
    console.log("✅ Connected to BlueZ");

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
    bus.export(
      `${APP_PATH}/service_main/char_obd`,
      createPropertiesInterface(mainCharInterface, "org.bluez.GattCharacteristic1", {
        UUID: { sig: "s", get: () => mainCharInterface.UUID },
        Service: { sig: "o", get: () => mainCharInterface.Service },
        Flags: { sig: "as", get: () => mainCharInterface.Flags },
        Notifying: { sig: "b", get: () => mainCharInterface.Notifying },
      })
    );

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
      "IOS-Vlink"
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

    // Register GATT application
    try {
      // Small delay to ensure all exports are fully available on the bus
      await new Promise((resolve) => setTimeout(resolve, 200));
      await gattManager.RegisterApplication(APP_PATH, {});
      console.log("✅ GATT application registered");
    } catch (err) {
      console.log("⚠️ GATT registration issue:");
      console.log(err);
      try {
        console.log("Bus unique name debug:", {
          busName: bus.name || bus._name,
          connUnique:
            (bus.connection && (bus.connection.uniqueName || bus.connection._name)) || null,
          connUniqueAlt:
            (bus._connection && (bus._connection.uniqueName || bus._connection._name)) || null,
        });
      } catch (e) {
        /* ignore */
      }
      if (err && err.stack) console.log(err.stack);
    }

    // Register advertisement
    try {
      await advManager.RegisterAdvertisement(ADVERT_PATH, {});
      console.log("✅ Advertisement registered");
    } catch (err) {
      if (err.message.includes("AlreadyExists")) {
        await advManager.UnregisterAdvertisement(ADVERT_PATH);
        await advManager.RegisterAdvertisement(ADVERT_PATH, {});
        console.log("✅ Advertisement re-registered");
      } else {
        throw err;
      }
    }

    console.log('\n📱 Device is now discoverable as "IOS-Vlink"');
    console.log("📊 Battery:", vehicle.getSummary());
    console.log("\nCommands: drive <speed>, charge, stop, status, soc <0-100>, help\n");

    startConsole();
  } catch (err) {
    console.error("❌ Error:", err.message);
    console.error(err.stack);
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
      case "drive":
        const speed = parseInt(args[1]) || 50;
        vehicle.setMode("driving", speed);
        break;
      case "charge":
        vehicle.setMode("charging");
        console.log("🔌 Charging started");
        break;
      case "stop":
        vehicle.setMode("static");
        console.log("🅿️ Stopped");
        break;
      case "status":
        console.log(JSON.stringify(vehicle.getSummary(), null, 2));
        break;
      case "soc":
        const soc = parseFloat(args[1]);
        if (!isNaN(soc) && soc >= 0 && soc <= 100) {
          vehicle.soc = soc * 10;
          console.log(`🔋 SOC set to ${soc}%`);
        }
        break;
      case "speed":
        const s = parseInt(args[1]) || 0;
        vehicle.setSpeed(s);
        break;
      case "help":
        console.log(`
Commands:
  drive <speed>  - Start driving at speed km/h (default 50)
  speed <kmh>    - Change speed while driving
  charge         - Start charging
  stop           - Stop driving/charging
  status         - Show vehicle state
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
