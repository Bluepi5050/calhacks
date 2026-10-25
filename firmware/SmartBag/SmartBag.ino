// SmartBag firmware v3: ESP32 + BLE Nordic UART Service (Arduino IDE, ESP32 core 2.x or 3.x).
// Open the Serial Monitor (115200 baud, "Newline") and type a SKU to drop it in the bag:
//   1001            add one
//   -1001           remove one
//   +1001@TAG42     add, deduplicated by tag id (use your RFID tag UID here)
// Swap the Serial read in loop() for your RFID / barcode reader when the hardware is ready.
// BLE packets carry 20 bytes by default; the app reassembles text across packets until "\n".
// Pairing (v2): every bag advertises a unique name, SmartBag-XXXX (last two bytes of its MAC). When the phone
// writes "HELLO", the light blinks three times and the bag answers "BAG <name> fw3" so the app can confirm
// which bag is in your hand. Apps treat lines starting with "BAG " as control lines, never as items.
// Walk-out signal (v3): "PAID <code>" = the trip was charged: one short blink (as before). "DUE <code>" = the trip
// ended payment due (no card, nothing charged): three slow blinks, 600 ms on / 400 ms off, clearly different from
// PAID and from the HELLO blinks. Any other line from the phone is ignored, so older apps and bags stay harmless.
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define NUS_SERVICE "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define NUS_RX      "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // phone -> bag (acks)
#define NUS_TX      "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // bag -> phone (items)
#define LED_PIN 2

BLECharacteristic *txChar;
bool connected = false;
volatile bool helloRequested = false;
volatile bool dueRequested = false;
String bagName;

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override { connected = true; Serial.println("phone connected"); }
  void onDisconnect(BLEServer *s) override { connected = false; Serial.println("phone disconnected"); s->getAdvertising()->start(); }
};

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String msg = String(c->getValue().c_str());
    msg.trim();
    Serial.print("phone: "); Serial.println(msg);
    if (msg.startsWith("HELLO")) { helloRequested = true; return; }   // handled in loop(): never block the BLE callback
    if (msg.startsWith("DUE ")) { dueRequested = true; return; }      // payment due: slow pattern, also in loop()
    if (msg.startsWith("OK") || msg.startsWith("PAID")) { digitalWrite(LED_PIN, HIGH); delay(120); digitalWrite(LED_PIN, LOW); }
  }
};

void sendLine(const String &line) {
  if (!connected) { Serial.println("not connected, dropped: " + line); return; }
  String out = line + "\n";
  txChar->setValue((uint8_t *)out.c_str(), out.length());
  txChar->notify();
  Serial.println("sent: " + line);
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  uint64_t mac = ESP.getEfuseMac();
  char name[20];
  snprintf(name, sizeof name, "SmartBag-%04X", (unsigned)((mac >> 32) & 0xFFFF));
  bagName = String(name);
  BLEDevice::init(name);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService *svc = server->createService(NUS_SERVICE);
  txChar = svc->createCharacteristic(NUS_TX, BLECharacteristic::PROPERTY_NOTIFY);
  txChar->addDescriptor(new BLE2902());
  BLECharacteristic *rx = svc->createCharacteristic(NUS_RX, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCallbacks());
  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(NUS_SERVICE);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println(bagName + " advertising");
}

void loop() {
  if (helloRequested) {
    helloRequested = false;
    for (int i = 0; i < 3; i++) { digitalWrite(LED_PIN, HIGH); delay(180); digitalWrite(LED_PIN, LOW); delay(180); }
    sendLine("BAG " + bagName + " fw3");
  }
  if (dueRequested) {
    dueRequested = false;
    for (int i = 0; i < 3; i++) { digitalWrite(LED_PIN, HIGH); delay(600); digitalWrite(LED_PIN, LOW); delay(400); }
  }
  if (Serial.available()) {
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length()) sendLine(line);
  }
}
