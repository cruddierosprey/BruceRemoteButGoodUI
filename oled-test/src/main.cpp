#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_RESET -1

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

struct I2CPins {
  int sda;
  int scl;
};

const I2CPins candidates[] = {
  {8, 9},   // Current physical wiring: SDA -> GPIO8, SCL -> GPIO9
  {9, 8}    // Fallback for swapped signal wires
};

uint8_t oledAddress = 0;
int activeSDA = -1;
int activeSCL = -1;
float wavePhase = 0.0f;
unsigned long lastFlash = 0;

uint8_t probeOLEDAddress() {
  const uint8_t addresses[] = {0x3C, 0x3D};
  for (uint8_t address : addresses) {
    Wire.beginTransmission(address);
    if (Wire.endTransmission() == 0) {
      return address;
    }
  }
  return 0;
}

bool initOLED() {
  for (const auto &pins : candidates) {
    Wire.end();
    delay(50);

    if (!Wire.begin(pins.sda, pins.scl)) {
      continue;
    }
    Wire.setClock(100000);
    delay(100);

    uint8_t address = probeOLEDAddress();
    if (address == 0) {
      continue;
    }

    // IMPORTANT: periphBegin=false keeps our custom SDA/SCL mapping.
    if (!display.begin(SSD1306_SWITCHCAPVCC, address, true, false)) {
      continue;
    }

    oledAddress = address;
    activeSDA = pins.sda;
    activeSCL = pins.scl;
    return true;
  }

  return false;
}

void showBootScreen() {
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(2);
  display.setCursor(8, 8);
  display.println("OLED OK!");

  display.setTextSize(1);
  display.setCursor(8, 36);
  display.print("SDA:");
  display.print(activeSDA);
  display.print(" SCL:");
  display.println(activeSCL);
  display.setCursor(8, 50);
  display.print("ADDR: 0x");
  display.println(oledAddress, HEX);
  display.display();
  delay(2500);
}

void drawStarburst(int centerX, int centerY, int radius, int lines) {
  for (int i = 0; i < lines; i++) {
    float angle = i * (2.0f * PI / lines);
    int x = centerX + static_cast<int>(radius * cosf(angle));
    int y = centerY + static_cast<int>(radius * sinf(angle));
    display.drawLine(centerX, centerY, x, y, SSD1306_WHITE);
  }
}

void drawWave(float phase) {
  for (int x = 0; x < SCREEN_WIDTH; x++) {
    int y = SCREEN_HEIGHT / 2 + static_cast<int>(10.0f * sinf((x + phase) * 0.1f));
    display.drawPixel(x, y, SSD1306_WHITE);
  }
}

void drawFlashTransition() {
  display.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT, SSD1306_WHITE);
  display.display();
  delay(100);

  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(28, 24);
  display.println("OLED ESP32-S3");
  display.setCursor(34, 38);
  display.println("READY");
  display.display();
  delay(700);
}

void setup() {
  Serial.begin(115200);
  delay(1200);
  Serial.println("ESP32-S3 OLED bootstrap");

  if (!initOLED()) {
    Serial.println("OLED not detected on SDA/SCL 8/9 or 9/8 at 0x3C/0x3D.");
    while (true) {
      delay(1000);
    }
  }

  Serial.printf("OLED detected: SDA=%d SCL=%d address=0x%02X\n", activeSDA, activeSCL, oledAddress);
  showBootScreen();
  lastFlash = millis();
}

void loop() {
  display.clearDisplay();

  int radius = 10 + static_cast<int>(fabsf(sinf(millis() * 0.002f)) * 20.0f);
  drawStarburst(SCREEN_WIDTH / 2, SCREEN_HEIGHT / 2, radius, 12);
  drawWave(wavePhase);
  wavePhase += 2.0f;

  display.display();
  delay(50);

  if (millis() - lastFlash >= 10000UL) {
    drawFlashTransition();
    lastFlash = millis();
  }
}
