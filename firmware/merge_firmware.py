Import("env")
import re
import os
import shutil
import subprocess

def get_firmware_version():
    """Extract FIRMWARE_VERSION from config.h"""
    config_path = os.path.join(env.subst("$PROJECT_DIR"), "include", "config.h")
    try:
        with open(config_path, 'r') as f:
            content = f.read()
            match = re.search(r'#define\s+FIRMWARE_VERSION\s+"([^"]+)"', content)
            if match:
                return match.group(1)
    except Exception as e:
        print(f"Warning: Could not read version from config.h: {e}")
    return "unknown"

def rename_app_firmware(source, target, env):
    """Rename firmware.bin to app-firmware-{version}.bin"""
    build_dir = env.subst("$BUILD_DIR")
    version = get_firmware_version()

    firmware_src = os.path.join(build_dir, "firmware.bin")
    firmware_dst = os.path.join(build_dir, f"app-firmware-{version}.bin")

    if os.path.exists(firmware_src):
        shutil.copy2(firmware_src, firmware_dst)
        size = os.path.getsize(firmware_dst)
        print(f"Created app-firmware-{version}.bin: {size:,} bytes")

def merge_bin_action(source, target, env):
    """Post-build action to create a merged firmware binary for initial setup"""
    build_dir = env.subst("$BUILD_DIR")
    version = get_firmware_version()

    # File paths
    bootloader = os.path.join(build_dir, "bootloader.bin")
    partitions = os.path.join(build_dir, "partitions.bin")
    firmware = os.path.join(build_dir, "firmware.bin")
    output = os.path.join(build_dir, f"setup-firmware-{version}.bin")

    # Check all files exist
    if not all(os.path.exists(f) for f in [bootloader, partitions, firmware]):
        print("Warning: Not all firmware files exist, skipping merge")
        return

    # Get esptool from platformio
    esptool = env.subst("$PYTHONEXE") + " -m esptool"

    # Build merge command
    cmd = f'{esptool} --chip esp32c3 merge_bin -o "{output}" --flash_mode dio --flash_size 4MB 0x0 "{bootloader}" 0x8000 "{partitions}" 0x10000 "{firmware}"'

    print(f"Creating setup-firmware-{version}.bin...")
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)

    if result.returncode == 0:
        size = os.path.getsize(output)
        print(f"Created setup-firmware-{version}.bin: {size:,} bytes")
        print(f"\nBuild outputs in {build_dir}:")
        print(f"  - app-firmware-{version}.bin    (for OTA updates)")
        print(f"  - setup-firmware-{version}.bin  (for initial device setup)")
    else:
        print(f"Error creating merged firmware: {result.stderr}")

# Register post-build actions
env.AddPostAction("$BUILD_DIR/firmware.bin", rename_app_firmware)
env.AddPostAction("$BUILD_DIR/firmware.bin", merge_bin_action)
