Import("env")

def merge_bin_action(source, target, env):
    """Post-build action to create a merged firmware binary"""
    import os
    import subprocess

    # Get the build directory
    build_dir = env.subst("$BUILD_DIR")

    # File paths
    bootloader = os.path.join(build_dir, "bootloader.bin")
    partitions = os.path.join(build_dir, "partitions.bin")
    firmware = os.path.join(build_dir, "firmware.bin")
    output = os.path.join(build_dir, "merged-flash.bin")

    # Check all files exist
    if not all(os.path.exists(f) for f in [bootloader, partitions, firmware]):
        print("Warning: Not all firmware files exist, skipping merge")
        return

    # Get esptool from platformio
    esptool = env.subst("$PYTHONEXE") + " -m esptool"

    # Build merge command
    cmd = f'{esptool} --chip esp32c3 merge_bin -o "{output}" --flash_mode dio --flash_size 4MB 0x0 "{bootloader}" 0x8000 "{partitions}" 0x10000 "{firmware}"'

    print(f"Creating merged firmware: {output}")
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)

    if result.returncode == 0:
        size = os.path.getsize(output)
        print(f"Merged firmware created: {size:,} bytes")
    else:
        print(f"Error creating merged firmware: {result.stderr}")

# Register the post-build action
env.AddPostAction("$BUILD_DIR/firmware.bin", merge_bin_action)
