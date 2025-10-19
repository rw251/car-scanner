#!/usr/bin/env node

/**
 * BLE bridge that exposes an IOS-Vlink compatible peripheral backed by
 * the ELM327 emulator listening on a TCP socket.
 */

const bleno = require("bleno");
const net = require("net");

const DEFAULT_NAME = "IOS-Vlink";
const MAIN_SERVICE_UUID = "e7810a71-73ae-499d-8c15-faa9aef0c3f2";
const MAIN_CHARACTERISTIC_UUID = "e7810a71-73ae-499d-8c15-faa9aef0c3f3";
const DEVICE_INFORMATION_SERVICE_UUID = "0000180a-0000-1000-8000-00805f9b34fb";
const GENERIC_ACCESS_SERVICE_UUID = "00001800-0000-1000-8000-00805f9b34fb";
const GENERIC_ATTRIBUTE_SERVICE_UUID = "00001801-0000-1000-8000-00805f9b34fb";
const UNKNOWN_VENDOR_SERVICE_UUID = "000018f0-0000-1000-8000-00805f9b34fb";

const args = process.argv.slice(2);

let host = process.env.ELM_HOST || "127.0.0.1";
let port = parseInt(process.env.ELM_PORT || "35000", 10);
let deviceName = process.env.IOS_VLINK_NAME || DEFAULT_NAME;

for (let i = 0; i < args.length; i += 1) {
  const arg = args[i];
  if (arg === "--host" && i + 1 < args.length) {
    host = args[i + 1];
    i += 1;
  } else if (arg === "--port" && i + 1 < args.length) {
    port = parseInt(args[i + 1], 10);
    i += 1;
  } else if (arg === "--name" && i + 1 < args.length) {
    deviceName = args[i + 1];
    i += 1;
  } else if (arg === "--help" || arg === "-h") {
    console.log("Usage: node index.js [--host 127.0.0.1] [--port 35000] [--name IOS-Vlink]");
    process.exit(0);
  }
}

if (!Number.isFinite(port) || port <= 0) {
  console.error("Invalid port value:", port);
  process.exit(1);
}

let elmSocket = null;
let elmConnected = false;
const pendingWrites = [];

function log(...line) {
  console.log("[bridge]", ...line);
}

function createStaticCharacteristic(uuid, valueBuffer) {
  return new bleno.Characteristic({
    uuid,
    properties: ["read"],
    onReadRequest(offset, callback) {
      if (offset > valueBuffer.length) {
        callback(this.RESULT_INVALID_OFFSET);
        return;
      }
      callback(this.RESULT_SUCCESS, valueBuffer.slice(offset));
    },
  });
}

class ElmCharacteristic extends bleno.Characteristic {
  constructor() {
    super({
      uuid: MAIN_CHARACTERISTIC_UUID,
      properties: ["write", "writeWithoutResponse", "notify"],
      descriptors: [
        new bleno.Descriptor({
          uuid: "2901",
          value: "ELM327 command channel",
        }),
      ],
    });
    this._updateValueCallback = null;
    this._maxValueSize = 20;
  }

  onSubscribe(maxValueSize, updateValueCallback) {
    this._updateValueCallback = updateValueCallback;
    this._maxValueSize = maxValueSize || 20;
    log("Central subscribed, MTU", this._maxValueSize);
    ensureElmConnection();
  }

  onUnsubscribe() {
    log("Central unsubscribed");
    this._updateValueCallback = null;
    // Allow existing socket to remain in case the app reconnects quickly.
  }

  onWriteRequest(data, offset, withoutResponse, callback) {
    if (offset !== 0) {
      callback(this.RESULT_ATTR_NOT_LONG);
      return;
    }
    const text = data.toString("utf8");
    log("BLE -> ELM", JSON.stringify(text));
    pendingWrites.push(text);
    ensureElmConnection();
    flushPending();
    callback(this.RESULT_SUCCESS);
  }

  pushData(chunk) {
    if (!this._updateValueCallback) {
      return;
    }
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk, "utf8");
    for (let offset = 0; offset < buffer.length; offset += this._maxValueSize) {
      const slice = buffer.slice(offset, offset + this._maxValueSize);
      this._updateValueCallback(slice);
    }
  }
}

const elmCharacteristic = new ElmCharacteristic();

function ensureElmConnection() {
  if (elmSocket || elmConnected) {
    return;
  }

  elmSocket = net.createConnection({ host, port }, () => {
    elmConnected = true;
    log(`Connected to ELM emulator at ${host}:${port}`);
    flushPending();
  });

  elmSocket.setEncoding("utf8");
  elmSocket.on("data", (payload) => {
    log("ELM -> BLE", JSON.stringify(payload));
    elmCharacteristic.pushData(payload);
  });

  elmSocket.on("close", () => {
    log("Disconnected from ELM emulator");
    elmSocket = null;
    elmConnected = false;
  });

  elmSocket.on("error", (err) => {
    log("ELM socket error", err.message);
    elmSocket?.destroy();
    elmSocket = null;
    elmConnected = false;
    // Keep pending writes so that we retry on the next ensureElmConnection call.
  });
}

function flushPending() {
  if (!elmSocket || !elmConnected) {
    return;
  }
  while (pendingWrites.length) {
    const message = pendingWrites.shift();
    if (message && elmSocket.writable) {
      elmSocket.write(message);
    }
  }
}

const mainService = new bleno.PrimaryService({
  uuid: MAIN_SERVICE_UUID,
  characteristics: [elmCharacteristic],
});

const deviceInformationService = new bleno.PrimaryService({
  uuid: DEVICE_INFORMATION_SERVICE_UUID,
  characteristics: [
    createStaticCharacteristic("2a29", Buffer.from("Feasycom", "utf8")),
    createStaticCharacteristic("2a24", Buffer.from("FSC-BT826N", "utf8")),
    createStaticCharacteristic("2a26", Buffer.from("5.4.2,20190819", "utf8")),
  ],
});

const genericAccessService = new bleno.PrimaryService({
  uuid: GENERIC_ACCESS_SERVICE_UUID,
  characteristics: [
    createStaticCharacteristic("2a00", Buffer.from(deviceName, "utf8")),
    createStaticCharacteristic("2a01", Buffer.from([0x00, 0x00])), // appearance: unknown
  ],
});

const serviceChangedValue = Buffer.from([0x00, 0x01, 0xff, 0xff]);
const serviceChangedCharacteristic = new bleno.Characteristic({
  uuid: "2a05",
  properties: ["indicate"],
  onSubscribe(maxValueSize, updateValueCallback) {
    // Service changed indication is not used but must respond successfully.
    updateValueCallback(serviceChangedValue);
  },
});

const genericAttributeService = new bleno.PrimaryService({
  uuid: GENERIC_ATTRIBUTE_SERVICE_UUID,
  characteristics: [serviceChangedCharacteristic],
});

const unknownVendorService = new bleno.PrimaryService({
  uuid: UNKNOWN_VENDOR_SERVICE_UUID,
  characteristics: [
    new bleno.Characteristic({
      uuid: "00002af0-0000-1000-8000-00805f9b34fb",
      properties: ["read"],
      onReadRequest(offset, callback) {
        callback(this.RESULT_SUCCESS, Buffer.alloc(0));
      },
    }),
  ],
});

bleno.on("stateChange", (state) => {
  log("Bluetooth state changed to", state);
  log("Device name set to", deviceName);
  if (state === "poweredOn") {
    bleno.startAdvertising(deviceName, [MAIN_SERVICE_UUID], (err) => {
      if (err) {
        log("Failed to start advertising", err.message);
      } else {
        log("startAdvertising request accepted");
      }
    });
  } else {
    bleno.stopAdvertising();
  }
});

bleno.on("advertisingStart", (err) => {
  if (err) {
    log("Advertising start error", err);
    console.log(err);
    return;
  }
  log("Advertising as", deviceName);
  bleno.setServices(
    [
      mainService,
      deviceInformationService,
      genericAccessService,
      genericAttributeService,
      unknownVendorService,
    ],
    (serviceError) => {
      if (serviceError) {
        log("Failed to set services", serviceError.message);
      } else {
        log("GATT services registered");
      }
    }
  );
});

bleno.on("advertisingStartError", (err) => {
  log("Advertising start error event", err?.message ?? err);
});

bleno.on("servicesSetError", (err) => {
  log("Service set error event", err?.message ?? err);
});

bleno.on("accept", (clientAddress) => {
  log("Accepted connection from", clientAddress);
});

bleno.on("disconnect", (clientAddress) => {
  log("Disconnected from", clientAddress);
});

function shutdown() {
  log("Shutting down bridge");
  bleno.stopAdvertising();
  if (elmSocket) {
    elmSocket.destroy();
  }
  process.exit(0);
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
