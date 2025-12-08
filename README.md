# FAME Smart Blinds

An open smart blind project with Home Assistant integration.

## Hardware

- **Seeed XIAO ESP32-C3** - WiFi/BLE microcontroller
- **XIAO Bus Servo Adapter** - Serial bus interface
- **Feetech STS Series Servo** - STS3215 Smart serial servo

## Features

- **Home Assistant Integration** via MQTT auto-discovery
- **iOS Companion App** for setup and direct control
- **BLE Provisioning** for WiFi configuration
- **HTTP REST API** for local network control
- **USB Serial Debugging** with categorized logging

## Getting Started

### Model, Parts & Blind

The bill of materials and 3D printable files will shortly be available via my club on Printables.

### Firmware

Download the firmware from the releases section and use [FAME Smart Flasher](https://github.com/FyrbyAdditive/FAME-Smart-Flasher) to flash the initial firmware.

## Home Assistant

The device uses MQTT auto-discovery. Once connected to your MQTT broker, it will appear automatically as a `cover` entity with:

- Open / Close / Stop controls
- State feedback (opening, closing, open, closed, stopped)
- Availability status

## HTTP API

When connected to WiFi, the device exposes a REST API:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Health check |
| `/status` | GET | Device status, WiFi info |
| `/info` | GET | Device info, version |
| `/command` | POST | Send command `{"action": "OPEN\|CLOSE\|STOP"}` |
| `/open` | POST | Quick open |
| `/close` | POST | Quick close |
| `/stop` | POST | Quick stop |

## BLE Service

Service UUID: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`

| Characteristic | UUID | Properties |
|----------------|------|------------|
| WiFi SSID | `...a8` | Read/Write |
| WiFi Password | `...a9` | Write |
| Device Name | `...aa` | Read/Write |
| MQTT Broker | `...ab` | Read/Write |
| Status | `...ac` | Read/Notify |
| Command | `...ad` | Write |

# About

Copyright 2025 Timothy Ellis, Fyrby Additive Manufacturing & Engineering
