![FAME Smart Blinds Icon](https://raw.githubusercontent.com/FyrbyAdditive/FAME-Smart-Blinds/refs/heads/main/ios/FAME%20Smart%20Blinds/Assets.xcassets/AppIcon.appiconset/GB128.png)
# FAME Smart Blinds Development

## HTTP API

When connected to WiFi, the device exposes a REST API:

### Basic Control

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Health check |
| `/status` | GET | Device status, WiFi info, calibration state |
| `/info` | GET | Device info, version, endpoints |
| `/command` | POST | Send command `{"action": "OPEN\|CLOSE\|STOP"}` |
| `/open` | POST | Open blinds |
| `/close` | POST | Close blinds |
| `/stop` | POST | Stop movement |
| `/open/force` | POST | Force open (bypass limits) |
| `/close/force` | POST | Force close (bypass limits) |

### Calibration

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/calibrate/start` | POST | Begin calibration (find home) |
| `/calibrate/setbottom` | POST | Confirm bottom position |
| `/calibrate/cancel` | POST | Cancel calibration |
| `/calibrate/status` | GET | Get calibration state |

### Configuration

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/name` | POST | Set device name (`?name=...`) |
| `/password` | POST | Set device password (`?password=...`) |
| `/wifi` | POST | Set WiFi credentials (`?ssid=...&password=...`) |
| `/mqtt` | POST | Set MQTT config (`?broker=...&port=...&user=...&password=...`) |
| `/orientation` | GET/POST | Get/set mount orientation (`?orientation=left\|right`) |
| `/speed` | GET/POST | Get/set servo speed (`?value=0-4095`) |
| `/factory-reset` | POST | Erase all settings and restart |
| `/restart` | POST | Restart the device |

### Diagnostics

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/hall` | GET | Hall sensor debug info |
| `/logs` | GET | Get device logs (ring buffer) |
| `/logs` | DELETE | Clear device logs |
| `/events` | SSE | Real-time status updates |
| `/events/logs` | SSE | Real-time log streaming |

### OTA Updates

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/update` | POST | OTA update (multipart file upload) |
| `/update/status` | GET | Check multipart OTA status |
| `/ota/begin` | POST | Initialize chunked OTA (`?size=TOTAL`) |
| `/ota/chunk` | POST | Send firmware chunk (body=binary data) |
| `/ota/end` | POST | Finalize OTA update |
| `/ota/abort` | POST | Cancel OTA update |
| `/ota/status` | GET | Get chunked OTA progress |

## BLE Service

Service UUID: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`

Used for initial device setup before WiFi is configured.

| Characteristic | UUID Suffix | Properties | Description |
|----------------|-------------|------------|-------------|
| WiFi SSID | `...26a8` | Read/Write | WiFi network name |
| WiFi Password | `...26a9` | Write | WiFi password |
| Device Name | `...26aa` | Read/Write | Custom device name |
| MQTT Broker | `...26ab` | Read/Write | MQTT broker address |
| Status | `...26ac` | Read/Notify | Setup status updates |
| Command | `...26ad` | Write | Setup commands |
| Device Password | `...26ae` | Write | Device access password |
| Orientation | `...26af` | Read/Write | Mount orientation (left/right) |
| WiFi Scan Trigger | `...26b0` | Write | Start WiFi network scan |
| WiFi Scan Results | `...26b1` | Notify | Scanned networks JSON |

Full UUID format: `beb5483e-36e1-4688-b7f5-ea07361b{suffix}`
