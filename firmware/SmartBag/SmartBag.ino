// SmartBag firmware: ESP32 + BLE Nordic UART Service (Arduino IDE, ESP32 core 2.x or 3.x).
// Open the Serial Monitor (115200 baud, "Newline") and type a SKU to drop it in the bag:
//   1001            add one
//   -1001           remove one
//   +1001@TAG42     add, deduplicated by tag id (use your RFID tag UID here)
// Swap the Serial read in loop() for your RFID / barcode reader when the hardware is ready.
// BLE packets carry 20 bytes by default; the app reassembles text across packets until "\n".
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

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override { connected = true; Serial.println("phone connected"); }
  void onDisconnect(BLEServer *s) override { connected = false; Serial.println("phone disconnected"); s->getAdvertising()->start(); }
};

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String msg = String(c->getValue().c_str());
    msg.trim();
    Serial.print("phone: "); Serial.println(msg);
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
  BLEDevice::init("SmartBag-01");
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
  Serial.println("SmartBag-01 advertising");
}

void loop() {
  if (Serial.available()) {
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length()) sendLine(line);
  }
}
