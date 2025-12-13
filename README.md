![FAME Smart Blinds Icon](https://raw.githubusercontent.com/FyrbyAdditive/FAME-Smart-Blinds/refs/heads/main/ios/FAME%20Smart%20Blinds/Assets.xcassets/AppIcon.appiconset/GB128.png)
# FAME Smart Blinds

An open smart blind project with Home Assistant integration.

## Electronic Hardware

- **Seeed XIAO ESP32-C3** - WiFi/BLE microcontroller
- **XIAO Bus Servo Adapter** - Serial bus interface
- **Feetech STS Series Servo** - STS3215 Smart serial servo
- **Seeed Hall Sensor**

## Features

- **Home Assistant Integration** via MQTT auto-discovery
- **iOS, Android and macOS Companion Apps** for setup and direct control
- **BLE Provisioning** for WiFi configuration
- **HTTP REST API** for local network control
- **USB Serial Debugging** with categorized logging

## Getting Started

### Model, Parts & Blind

The model, full list of hardware and build instructions can be found [at my club on Printables](https://www.printables.com/model/1504685-fame-smart-blinds-preview-1).

### Firmware

Download the firmware from the releases section and use [FAME Smart Flasher](https://github.com/FyrbyAdditive/FAME-Smart-Flasher) to flash the initial firmware.

## Home Assistant

The device uses MQTT auto-discovery. Once connected to your MQTT broker, it will appear automatically as a `cover` entity with:

- Open / Close / Stop controls
- State feedback (opening, closing, open, closed, stopped)
- Availability status

# About

Copyright 2025 Timothy Ellis, Fyrby Additive Manufacturing & Engineering
