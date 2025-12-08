#!/bin/bash
#
# Build and sign Mac Catalyst app for distribution
# Creates a signed .pkg installer ready for notarization/distribution
#

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_PATH="$SCRIPT_DIR/FAME Smart Blinds.xcodeproj"
SCHEME="FAMESmartBlinds"
CONFIGURATION="Release"

# Output directories
BUILD_DIR="$SCRIPT_DIR/build"
ARCHIVE_DIR="$BUILD_DIR/archives"
EXPORT_DIR="$BUILD_DIR/export"
PKG_DIR="$BUILD_DIR/packages"

# Extract version info from project
get_version_info() {
    local pbxproj="$PROJECT_PATH/project.pbxproj"
    MARKETING_VERSION=$(grep -m1 'MARKETING_VERSION' "$pbxproj" | sed 's/.*= *\(.*\);/\1/' | tr -d ' "')
    BUILD_NUMBER=$(grep -m1 'CURRENT_PROJECT_VERSION' "$pbxproj" | sed 's/.*= *\(.*\);/\1/' | tr -d ' "')

    if [ -z "$MARKETING_VERSION" ]; then
        MARKETING_VERSION="1.0.0"
    fi
    if [ -z "$BUILD_NUMBER" ]; then
        BUILD_NUMBER="1"
    fi

    echo "Version: $MARKETING_VERSION ($BUILD_NUMBER)"
}

# Get the product name from the project
get_product_name() {
    PRODUCT_NAME="FAME Smart Blinds"
    PRODUCT_NAME_SAFE=$(echo "$PRODUCT_NAME" | tr ' ' '_')
    echo "Product: $PRODUCT_NAME"
}

# Clean build directory
clean() {
    echo "=== Cleaning build directory ==="
    rm -rf "$BUILD_DIR"
    mkdir -p "$ARCHIVE_DIR" "$EXPORT_DIR" "$PKG_DIR"
}

# Build and archive
archive() {
    echo "=== Building and archiving Mac Catalyst app ==="

    ARCHIVE_PATH="$ARCHIVE_DIR/${PRODUCT_NAME_SAFE}_${MARKETING_VERSION}_${BUILD_NUMBER}.xcarchive"

    xcodebuild archive \
        -project "$PROJECT_PATH" \
        -scheme "$SCHEME" \
        -configuration "$CONFIGURATION" \
        -destination 'platform=macOS,variant=Mac Catalyst' \
        -archivePath "$ARCHIVE_PATH" \
        CODE_SIGN_STYLE=Automatic \
        | xcbeautify 2>/dev/null || xcodebuild archive \
            -project "$PROJECT_PATH" \
            -scheme "$SCHEME" \
            -configuration "$CONFIGURATION" \
            -destination 'platform=macOS,variant=Mac Catalyst' \
            -archivePath "$ARCHIVE_PATH" \
            CODE_SIGN_STYLE=Automatic

    echo "Archive created: $ARCHIVE_PATH"
}

# Export the app
export_app() {
    echo "=== Exporting signed app ==="

    ARCHIVE_PATH="$ARCHIVE_DIR/${PRODUCT_NAME_SAFE}_${MARKETING_VERSION}_${BUILD_NUMBER}.xcarchive"
    EXPORT_OPTIONS="$SCRIPT_DIR/ExportOptions.plist"

    # Create ExportOptions.plist if it doesn't exist
    if [ ! -f "$EXPORT_OPTIONS" ]; then
        echo "Creating ExportOptions.plist for Developer ID distribution..."
        cat > "$EXPORT_OPTIONS" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>developer-id</string>
    <key>teamID</key>
    <string>QS865LKS7W</string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>destination</key>
    <string>export</string>
</dict>
</plist>
EOF
    fi

    xcodebuild -exportArchive \
        -archivePath "$ARCHIVE_PATH" \
        -exportPath "$EXPORT_DIR" \
        -exportOptionsPlist "$EXPORT_OPTIONS" \
        | xcbeautify 2>/dev/null || xcodebuild -exportArchive \
            -archivePath "$ARCHIVE_PATH" \
            -exportPath "$EXPORT_DIR" \
            -exportOptionsPlist "$EXPORT_OPTIONS"

    echo "App exported to: $EXPORT_DIR"
}

# Create signed installer package
create_pkg() {
    echo "=== Creating signed installer package ==="

    APP_PATH="$EXPORT_DIR/$PRODUCT_NAME.app"
    PKG_NAME="${PRODUCT_NAME_SAFE}_${MARKETING_VERSION}_${BUILD_NUMBER}_mac.pkg"
    PKG_PATH="$PKG_DIR/$PKG_NAME"

    if [ ! -d "$APP_PATH" ]; then
        echo "Error: App not found at $APP_PATH"
        exit 1
    fi

    # Get the Developer ID Installer identity
    INSTALLER_IDENTITY=$(security find-identity -v -p basic | grep "Developer ID Installer" | head -1 | sed 's/.*"\(Developer ID Installer[^"]*\)".*/\1/')

    if [ -z "$INSTALLER_IDENTITY" ]; then
        echo "Warning: No Developer ID Installer certificate found. Creating unsigned package."
        pkgbuild \
            --root "$EXPORT_DIR" \
            --install-location /Applications \
            --identifier "com.fyrbyadditive.famesmartblinds.pkg" \
            --version "$MARKETING_VERSION" \
            "$PKG_PATH"
    else
        echo "Using installer identity: $INSTALLER_IDENTITY"
        pkgbuild \
            --root "$EXPORT_DIR" \
            --install-location /Applications \
            --identifier "com.fyrbyadditive.famesmartblinds.pkg" \
            --version "$MARKETING_VERSION" \
            --sign "$INSTALLER_IDENTITY" \
            "$PKG_PATH"
    fi

    echo ""
    echo "========================================="
    echo "Package created: $PKG_PATH"
    echo "========================================="
}

# Create a simple zip as alternative
create_zip() {
    echo "=== Creating zip archive ==="

    APP_PATH="$EXPORT_DIR/$PRODUCT_NAME.app"
    ZIP_NAME="${PRODUCT_NAME_SAFE}_${MARKETING_VERSION}_${BUILD_NUMBER}_mac.zip"
    ZIP_PATH="$PKG_DIR/$ZIP_NAME"

    if [ ! -d "$APP_PATH" ]; then
        echo "Error: App not found at $APP_PATH"
        exit 1
    fi

    cd "$EXPORT_DIR"
    ditto -c -k --keepParent "$PRODUCT_NAME.app" "$ZIP_PATH"
    cd "$SCRIPT_DIR"

    echo "Zip created: $ZIP_PATH"
}

# Show help
show_help() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  all       Build, export, and create package (default)"
    echo "  clean     Clean build directory"
    echo "  archive   Build and create archive only"
    echo "  export    Export app from latest archive"
    echo "  pkg       Create installer package from exported app"
    echo "  zip       Create zip archive from exported app"
    echo "  help      Show this help"
    echo ""
    echo "Output:"
    echo "  Archives:  $ARCHIVE_DIR/"
    echo "  Exports:   $EXPORT_DIR/"
    echo "  Packages:  $PKG_DIR/"
}

# Main
main() {
    echo "=== FAME Smart Blinds Mac Catalyst Build Script ==="
    echo ""

    get_version_info
    get_product_name
    echo ""

    case "${1:-all}" in
        all)
            clean
            archive
            export_app
            create_pkg
            create_zip
            echo ""
            echo "=== Build complete ==="
            echo "Package: $PKG_DIR/${PRODUCT_NAME_SAFE}_${MARKETING_VERSION}_${BUILD_NUMBER}_mac.pkg"
            echo "Zip:     $PKG_DIR/${PRODUCT_NAME_SAFE}_${MARKETING_VERSION}_${BUILD_NUMBER}_mac.zip"
            ;;
        clean)
            clean
            echo "Clean complete"
            ;;
        archive)
            mkdir -p "$ARCHIVE_DIR"
            archive
            ;;
        export)
            mkdir -p "$EXPORT_DIR"
            export_app
            ;;
        pkg)
            mkdir -p "$PKG_DIR"
            create_pkg
            ;;
        zip)
            mkdir -p "$PKG_DIR"
            create_zip
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            echo "Unknown command: $1"
            show_help
            exit 1
            ;;
    esac
}

main "$@"
