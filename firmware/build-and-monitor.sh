#!/bin/bash
pio run --target upload && pio device monitor -p /dev/tty.usbmodem101 -b 115200
