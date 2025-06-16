const controlButton = document.getElementById("controlButton");
const socButton = document.getElementById("soc");
const sohButton = document.getElementById("soh");
const votageButton = document.getElementById("votage");
const currentButton = document.getElementById("current");
const tempButton = document.getElementById("temp");
const spark = document.getElementById("spark");
const lr = document.getElementById("lr");
const svg = document.getElementsByTagName("svg")[0];
const socTimeLabel = document.getElementById("socTime");
const socValueLabel = document.getElementById("socValue");
const socTimerLabel = document.getElementById("socTimer");
let interval = 10000;
const version = "v1.0.13";
const SOC = [];
const logData = [];

// For testing
//test();

function predict() {
  var n = Math.min(SOC.length, 24);
  if (SOC.length < 12) {
    spark.setAttribute("d", socToSVG());
    return;
  }
  var sum_x = 0;
  var sum_y = 0;
  var sum_xy = 0;
  var sum_xx = 0;
  var sum_yy = 0;

  for (var i = SOC.length - n; i < SOC.length; i++) {
    var x = (SOC[i].d - SOC[0].d) / 1000;
    sum_x += x;
    sum_y += SOC[i].s;
    sum_xy += x * SOC[i].s;
    sum_xx += x * x;
    sum_yy += SOC[i] * SOC[i].s;
  }

  var slope = (n * sum_xy - sum_x * sum_y) / (n * sum_xx - sum_x * sum_x);
  var intercept = (sum_y - slope * sum_x) / n;

  var x1 = (SOC[SOC.length - 1].d - SOC[0].d) / 1000;
  var x2 = -intercept / slope; // (SOC[SOC.length - 1].d - SOC[0].d)/1000;
  var y1 = x1 * slope + intercept;
  var y2 = 0;

  connectionStatus.innerText = `Battery expires: ${Math.round((x2 - x1) / 60, 0)} minutes`;

  svg.setAttribute("viewBox", `0 0 ${x2} 930`);
  lr.setAttribute("d", `M ${x1} ${930 - y1} L${x2} ${930}`);
  spark.setAttribute("d", socToSVG());
}

function time(t = new Date()) {
  return t.toISOString().substring(11, 19);
}

function drawTime() {
  socTimerLabel.innerText = time();
  requestAnimationFrame(drawTime);
}
requestAnimationFrame(drawTime);
socTimeLabel.innerText = time();
socValueLabel.innerText = version;

const connectionStatus = document.getElementById("connectionStatus");

const msg = document.getElementById("msg");
const sendButton = document.getElementById("send");
const response = document.getElementById("response");

let hasReceivedResponse = false;

controlButton.addEventListener("click", BLEManager);
socButton.addEventListener("click", () => {
  sendMessage("22B046");
});
sohButton.addEventListener("click", () => {
  sendMessage("22B061");
});
votageButton.addEventListener("click", () => {
  sendMessage("22B042");
});
currentButton.addEventListener("click", () => {
  sendMessage("22B043");
});
tempButton.addEventListener("click", () => {
  sendMessage("22B056");
});
sendButton.addEventListener("click", sendMessageEvent);

function getValueFromHex(hex) {
  const byte4 = parseInt(hex.substring(0, 2), 16);
  const byte5 = parseInt(hex.substring(2, 4), 16);
  return { byte4, byte5, byte4And5: byte4 * 256 + byte5 };
}

function socToSVG() {
  if (SOC.length > 2) {
    const first = SOC[0];
    const svg = [`M0 ${930 - first.s}`];
    for (let i = 1; i < SOC.length; i++) {
      svg.push(`L${(SOC[i].d - SOC[0].d) / 1000} ${930 - SOC[i].s}`);
    }
    return svg.join("");
  }
  return "M0 0";
}

function rnd(number, decimalPlaces) {
  let factor = 1;
  while (decimalPlaces > 0) {
    decimalPlaces--;
    factor *= 10;
  }
  return Math.round(number * factor) / factor;
}

function parseMessage(value) {
  // "62B046036A\r62B04601
  if (!value[0].match(/[0-9]/)) {
    console.log(`Not parsing: ${value}`);
    return;
  }
  l(`Parsing: ${value}`);

  const byte1 = value.substring(0, 2);

  if (byte1 !== "62") {
    l(`First byte of [${value}] is [${byte1}]. Expecting 62.`);
    return;
  }

  const splitString = value.substring(0, 6);
  if (splitString.length < 6) {
    l("Split string too short");
    return;
  }

  const bits = value.split(splitString).filter((x) => x.length === 4);
  if (bits.length !== 1) {
    l(`Expecting ${splitString}XXXX at some point. Not found. Ignoring`);
    return;
  }

  const { byte4, byte5, byte4And5 } = getValueFromHex(bits[0]);

  switch (splitString) {
    case "62B046":
      // Soc appears to range from 0 to 1000. But probably the SOC of
      // the battery i.e. not the usable range. That is somewhere
      // between 3% and 7% of the battery hence the conversions below
      const Soc93 = rnd(byte4And5 / 9.3, 1);
      const Soc95 = rnd(byte4And5 / 9.5, 1);
      const Soc97 = rnd(byte4And5 / 9.7, 1);

      if (!Number.isNaN(Soc93)) {
        const now = new Date();
        SOC.push({ d: new Date(), s: byte4And5 });
        predict();
        l(`SOC93: ${Soc93}`);
        l(`SOC95: ${Soc95}`);
        l(`SOC97: ${Soc97}`);

        socTimeLabel.innerText = time(now);
        socValueLabel.innerText = byte4And5;

        // window.speechSynthesis.speak(new SpeechSynthesisUtterance(`${Soc93}%, ${Soc95}%, ${Soc97}%`));
      }
      break;
    case "62B061":
      const Soh = byte4And5 / 100;
      if (!Number.isNaN(Soh)) {
        l(`SOH: ${Soh}`);
      }
      break;
    case "62B042":
      const voltage = byte4And5 / 4;
      if (!Number.isNaN(voltage)) {
        l(`Voltage: ${voltage}`);
      }
      break;
    case "62B043":
      const current = (byte4And5 - 40000) * 0.025;
      if (!Number.isNaN(current)) {
        l(`Current: ${current}`);
      }
      break;
    case "62B056":
      const temp = byte4 / 2 - 40;
      if (!Number.isNaN(temp) && byte4And5 !== 0) {
        l(`Battery temp: ${temp}`);
      }
      break;
    /*"odometer": {
    "equation": "(A*65536+B*256+C)",
    "minValue": "0",
    "maxValue": "1000000",
    "type": "Number",
    "command": "22B101",
    "ecu": "760"
  },
  "vehicle_reported_speed": {
    "equation": "((A*256+B)-20000)/100",
    "minValue": "0",
    "maxValue": "220",
    "type": "Number",
    "command": "22BA00",
    "ecu": "7E3"
  },
  "ext_temp": {
    "equation": "A-40",
    "minValue": "-40",
    "maxValue": "80",
    "type": "Number",
    "command": "22BB05",
    "ecu": "7E3"
  }*/
    default:
  }
}

function publish(message) {
  console.log(message);
}
if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.addEventListener("controllerchange", () => {
      publish("NEW_SW_CONTROLLING");
    });

    navigator.serviceWorker.register("/service-worker.js?097jj").then(
      (registration) => {
        console.log("ServiceWorker registration successful with scope: ", registration.scope);
      },
      (err) => {
        console.log("ServiceWorker registration failed: ", err);
      }
    );
  });
}

let isReady = false;
let writer;
let notifiers;
let awaitingResponse = false;
const enc = new TextEncoder("utf-8");
const dec = new TextDecoder("utf-8");
const resetQueue = ["ATZ", "ATD", "ATE0", "ATS0", "ATH0", "ATL0" /*,'ATAT2'*/];
let currentQueue;

async function reset() {
  currentQueue = resetQueue.slice(0); // copy
  await executeMessageAwaitResponse();
}

async function executeMessageAwaitResponse() {
  const next = currentQueue.shift();
  if (!next) {
    connectionStatus.textContent = `READY`;
    controlButton.style.display = "none";
    setTimeout(() => {
      sendMessage("22B046");
    }, 500);
    setInterval(() => {
      sendMessage("22B046");
    }, interval);
  } else {
    connectionStatus.textContent = `Sending message: ${next}`;
    hasReceivedResponse = false;
    await sendMessage(next);
  }
}

const services = {
  main: { id: "e7810a71-73ae-499d-8c15-faa9aef0c3f2" },
  deviceInformation: { id: "0000180a-0000-1000-8000-00805f9b34fb" },
  unknown: { id: "000018f0-0000-1000-8000-00805f9b34fb" }, // Does not exist. Means MG are being naughty https://github.com/WebBluetoothCG/demos/issues/75
  genericAttribute: { id: "00001801-0000-1000-8000-00805f9b34fb" },
  genericAccess: { id: "00001800-0000-1000-8000-00805f9b34fb" },
};

const characteristics = [];

async function sendMessageEvent() {
  const message = msg.value.trim();
  return sendMessage(message);
}

async function sendMessage(message) {
  if (!isReady) {
    l("Not connected to device", true);
    return;
  }
  if (!writer) {
    l("Must select a writer", true);
    return;
  }
  if (message.length < 2) {
    l("Message empty or too short", true);
    return;
  }

  l(`Sending: ${message}`);
  await write(writer, message);
}
function l(msg) {
  logData.push(msg);
  response.innerText = logData.slice(-100).join("\n");
}
async function write(c, msg) {
  var comm = enc.encode(msg + "\r");
  await c.writeValueWithoutResponse(comm);
}
async function handleCharacteristicValueChanged(event) {
  var value = dec.decode(event.target.value).replace(/\r/g, "").trim();
  // "62B046036A\r62B04601
  if (!hasReceivedResponse) {
    setTimeout(async () => {
      await executeMessageAwaitResponse();
    }, 250);
  } else if (value !== "" && value !== ">") {
    l(`Received: ${value}`);
    parseMessage(value);
  }
  hasReceivedResponse = true;
}

async function BLEManager() {
  // TTS
  connectionStatus.textContent = "SEARCHING";
  let device;
  try {
    device = await navigator.bluetooth.requestDevice({
      filters: [{ name: "IOS-Vlink" }],
      //acceptAllDevices: true,
      optionalServices: Object.values(services).map((x) => x.id),
    });
    connectionStatus.textContent = "CONNECTING";
    const connectedDevice = await device.gatt.connect();

    connectionStatus.textContent = "GETTING SERVICES";
    services.main.service = await connectedDevice.getPrimaryService(services.main.id);
    services.deviceInformation.service = await connectedDevice.getPrimaryService(
      services.deviceInformation.id
    );
    services.unknown.service = await connectedDevice.getPrimaryService(services.unknown.id);
    services.genericAttribute.service = await connectedDevice.getPrimaryService(
      services.genericAttribute.id
    );
    services.genericAccess.service = await connectedDevice.getPrimaryService(
      services.genericAccess.id
    );

    connectionStatus.textContent = "GETTING CHARACTERISTICS";
    services.main.characteristics = await services.main.service.getCharacteristics();
    services.deviceInformation.characteristics =
      await services.deviceInformation.service.getCharacteristics();
    services.unknown.characteristics = await services.unknown.service.getCharacteristics();
    services.genericAttribute.characteristics =
      await services.genericAttribute.service.getCharacteristics();
    services.genericAccess.characteristics =
      await services.genericAccess.service.getCharacteristics();

    services.main.characteristics.forEach((characteristic, index) => {
      characteristics.push({ type: "main", index, characteristic });
    });
    services.deviceInformation.characteristics.forEach((characteristic, index) => {
      characteristics.push({
        type: "deviceInformation",
        index,
        characteristic,
      });
    });
    services.unknown.characteristics.forEach((characteristic, index) => {
      characteristics.push({ type: "unknown", index, characteristic });
    });
    services.genericAttribute.characteristics.forEach((characteristic, index) => {
      characteristics.push({
        type: "genericAttribute",
        index,
        characteristic,
      });
    });
    services.genericAccess.characteristics.forEach((characteristic, index) => {
      characteristics.push({ type: "genericAccess", index, characteristic });
    });

    const writers = characteristics.filter(({ characteristic }) => characteristic.properties.write);
    notifiers = characteristics.filter(({ characteristic }) => characteristic.properties.notify);

    writers.forEach(({ type, index, characteristic }) => {
      const option = document.createElement("option");
      option.value = `${type}-${index}`;
      option.innerText = `${type}-${index}`;
    });

    for (let notifier of notifiers) {
      if (notifier.type !== "main") continue;
      l(`Start listening to ${notifier.type}-${notifier.index}...`, true);
      await notifier.characteristic.startNotifications();
      notifier.characteristic.addEventListener(
        "characteristicvaluechanged",
        handleCharacteristicValueChanged
      );
      l(`Now listening to ${notifier.type}-${notifier.index}.`, true);
    }

    connectionStatus.textContent = "CONNECTED";
    isReady = true;

    // select1.addEventListener("change", () => {
    //   const [attr, index] = select1.value.split("-");
    //   writer = services[attr].characteristics[+index];
    // });
    writer = writers[0].characteristic;

    await reset();
  } catch (e) {
    console.log(e);
    if (typeof device !== "undefined") {
      connectionStatus.textContent = "CONNECTION FAILED";
    } else {
      connectionStatus.textContent = "CANCELLED";
    }
  }
}

/*
    deviceInformation - 4 chars - all read, no write
    genericAttribute - 1 char - read, no write
    genericAccess - 2 char - read, no write
    unknown - 2 char - no read or write
    
    main 
    deviceInformation FSC-BT826N
    deviceInformation 1.2
    deviceInformation 5.4.2,20190819
    deviceInformation Feasycom
    genericAttribute 
    genericAccess IOS-Vlink
    genericAccess 
    */

/*
    possible init commands???
     self.write('ATZ'); //Performs device reset and returns ELM-USB identification 
     self.write('ATL0'); //Turns off extra line feed and carriage return
        //This disables spaces in in output, which is faster!
        self.write('ATS0');
        self.write('ATH0'); //Turns off headers and checksum to be sent.
        self.write('ATE0'); //Turns off echo.
        self.write('ATAT2'); //Turn adaptive timing to 2. This is an aggressive learn curve for adjusting the timeout. Will make huge difference on slow systems.
        //Set timeout to 10 * 4 = 40msec, allows +20 queries per second. This is the maximum wait-time. ATAT will decide if it should wait shorter or not.
        //self.write('ATST0A');
        //http://www.obdtester.com/elm-usb-commands
        self.write('ATSP'+self.protocol); self.protocol = 0
    */

// from https://github.com/iternio/ev-obd-pids/blob/main/mg/mgzsev.json
// init_commands: [
// "ATZ", // reset
//  "ATD", // set defaults
// "ATE0", // turn off echo
// "ATS0", // disables spaces
// "ATAL" // ????
//]
/*
    "ATSH781",
    "ATFCSH781",
    "22B042", // Voltage / ecu 781 / (A*256+B)*0.25
    "22B043", // Current / ecu 781 / ((A*256+B)-40000)*0.25/10
    "22B061", // SOH (state of health?) / ecu 781 / (A*256+B)/100
    "22B046", // SOC (state of charge) / ecu 781 / (A*256+B)/100
    "22B056", // Battery temp / 
    "22B048",
    "ATSH7E3",
    "ATFCSH7E3",
    "22BA00",
    "22BB05",
    "ATSH760",
    "ATFCSH760",
    "22B101"
    */

// OBD BLE mock
async function req() {
  return {
    gatt: {
      connect,
    },
  };
}

async function connect() {
  return {
    getPrimaryService,
  };
}

async function getPrimaryService() {
  return {
    getCharacteristics,
  };
}

let haveDoneNotifier = false;
async function getCharacteristics() {
  const n = Math.floor(Math.random() * 3 + 1);
  const rtn = [];
  for (let i = 0; i < n; i++) {
    var fake = document.createElement("phony");

    fake.properties = {
      read: Math.random() > 0.8 ? true : false,
      write: Math.random() > 0.8 ? true : false,
      notify: false,
    };
    if (!haveDoneNotifier) {
      fake.properties.notify = true;
      fake.type === "main";
      haveDoneNotifier = true;
    }
    fake.startNotifications = startNotifications;
    fake.writeValueWithoutResponse = writeValueWithoutResponse;
    rtn.push(fake);
  }
  return rtn;
}

async function startNotifications() {}

async function writeValueWithoutResponse(val) {
  const decoded = dec.decode(val);
  if (decoded[0] === "A") {
    notifiers[0].characteristic.value = enc.encode("P" + Math.random());
    notifiers[0].characteristic.dispatchEvent(new Event("characteristicvaluechanged"));
  } else {
    const x = new Date();
    const SOC = Math.round(930 - ((x.getMinutes() * 60 + x.getSeconds()) / 3600) * 930, 0);
    const SOChex = ("0000" + SOC.toString(16)).slice(-4);
    notifiers[0].characteristic.value = enc.encode("62B046" + SOChex);
    notifiers[0].characteristic.dispatchEvent(new Event("characteristicvaluechanged"));
  }
}

function test() {
  if (navigator.bluetooth) navigator.bluetooth.requestDevice = req;
  else navigator.bluetooth = { requestDevice: req };
  interval = 1000;
}
