#!/bin/bash
#
# Build and sign Android APK for distribution
# Creates a signed APK ready for distribution
#
# Signing can be configured via:
#   1. Environment variables: KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
#   2. Local keystore.properties file (git-ignored)
#   3. Interactive prompts if neither is available
#

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
BUILD_GRADLE="$PROJECT_DIR/app/build.gradle.kts"

# Output directories
BUILD_DIR="$PROJECT_DIR/build"
APK_DIR="$BUILD_DIR/outputs"

# Product info
PRODUCT_NAME="FAME_Smart_Blinds"

# Setup Java environment (use Android Studio's bundled JDK if available)
setup_java() {
    # Check if Java is already available
    if command -v java &> /dev/null && java -version &> /dev/null 2>&1; then
        echo "Using system Java: $(java -version 2>&1 | head -1)"
        return 0
    fi

    # Try Android Studio's bundled JDK on macOS
    ANDROID_STUDIO_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    if [ -d "$ANDROID_STUDIO_JDK" ]; then
        export JAVA_HOME="$ANDROID_STUDIO_JDK"
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "Using Android Studio JDK: $JAVA_HOME"
        return 0
    fi

    # Try alternative Android Studio JDK location
    ANDROID_STUDIO_JDK_ALT="/Applications/Android Studio.app/Contents/jre/Contents/Home"
    if [ -d "$ANDROID_STUDIO_JDK_ALT" ]; then
        export JAVA_HOME="$ANDROID_STUDIO_JDK_ALT"
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "Using Android Studio JDK: $JAVA_HOME"
        return 0
    fi

    # Try Homebrew OpenJDK
    BREW_JDK="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
    if [ -d "$BREW_JDK" ]; then
        export JAVA_HOME="$BREW_JDK"
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "Using Homebrew OpenJDK: $JAVA_HOME"
        return 0
    fi

    echo "Error: Java runtime not found."
    echo ""
    echo "Please either:"
    echo "  1. Install Android Studio (recommended)"
    echo "  2. Install Java: brew install openjdk"
    echo "  3. Set JAVA_HOME environment variable"
    return 1
}

# Extract version info from build.gradle.kts
get_version_info() {
    echo "Reading version from build.gradle.kts..."

    VERSION_NAME=$(grep -m1 'versionName' "$BUILD_GRADLE" | sed 's/.*"\(.*\)".*/\1/')
    VERSION_CODE=$(grep -m1 'versionCode' "$BUILD_GRADLE" | sed 's/.*= *\([0-9]*\).*/\1/')

    if [ -z "$VERSION_NAME" ]; then
        VERSION_NAME="1.0.0"
    fi
    if [ -z "$VERSION_CODE" ]; then
        VERSION_CODE="1"
    fi

    echo "Version: $VERSION_NAME ($VERSION_CODE)"
}

# Check/setup signing configuration
setup_signing() {
    KEYSTORE_PROPS="$PROJECT_DIR/keystore.properties"

    # Check for environment variables first
    if [ -n "$KEYSTORE_FILE" ] && [ -n "$KEYSTORE_PASSWORD" ] && [ -n "$KEY_ALIAS" ] && [ -n "$KEY_PASSWORD" ]; then
        echo "Using signing config from environment variables"
        return 0
    fi

    # Check for keystore.properties file
    if [ -f "$KEYSTORE_PROPS" ]; then
        echo "Using signing config from keystore.properties"
        source "$KEYSTORE_PROPS" 2>/dev/null || {
            # Parse properties file format
            KEYSTORE_FILE=$(grep -m1 'storeFile' "$KEYSTORE_PROPS" | cut -d'=' -f2 | tr -d ' ')
            KEYSTORE_PASSWORD=$(grep -m1 'storePassword' "$KEYSTORE_PROPS" | cut -d'=' -f2 | tr -d ' ')
            KEY_ALIAS=$(grep -m1 'keyAlias' "$KEYSTORE_PROPS" | cut -d'=' -f2 | tr -d ' ')
            KEY_PASSWORD=$(grep -m1 'keyPassword' "$KEYSTORE_PROPS" | cut -d'=' -f2 | tr -d ' ')
        }
        return 0
    fi

    echo ""
    echo "========================================="
    echo "No signing configuration found."
    echo ""
    echo "To sign release builds, either:"
    echo "1. Set environment variables:"
    echo "   export KEYSTORE_FILE=/path/to/keystore.jks"
    echo "   export KEYSTORE_PASSWORD=your_store_password"
    echo "   export KEY_ALIAS=your_key_alias"
    echo "   export KEY_PASSWORD=your_key_password"
    echo ""
    echo "2. Create keystore.properties in the android directory:"
    echo "   storeFile=/path/to/keystore.jks"
    echo "   storePassword=your_store_password"
    echo "   keyAlias=your_key_alias"
    echo "   keyPassword=your_key_password"
    echo ""
    echo "3. Generate a new keystore:"
    echo "   $0 generate-keystore"
    echo "========================================="
    echo ""
    return 1
}

# Generate a new keystore
generate_keystore() {
    KEYSTORE_PATH="$PROJECT_DIR/release-keystore.jks"

    if [ -f "$KEYSTORE_PATH" ]; then
        echo "Keystore already exists at: $KEYSTORE_PATH"
        read -p "Overwrite? (y/N) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 0
        fi
    fi

    echo "Generating new release keystore..."
    echo ""

    read -p "Key alias [fame-smart-blinds]: " KEY_ALIAS
    KEY_ALIAS=${KEY_ALIAS:-fame-smart-blinds}

    read -p "Your name (CN): " CN
    read -p "Organization (O): " O
    read -p "Country code (C) [AU]: " C
    C=${C:-AU}

    keytool -genkeypair \
        -v \
        -keystore "$KEYSTORE_PATH" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -alias "$KEY_ALIAS" \
        -dname "CN=$CN, O=$O, C=$C"

    echo ""
    echo "Keystore created: $KEYSTORE_PATH"
    echo ""
    echo "Creating keystore.properties..."

    read -sp "Enter the keystore password you just set: " STORE_PASS
    echo
    read -sp "Enter the key password you just set: " KEY_PASS
    echo

    cat > "$PROJECT_DIR/keystore.properties" << EOF
storeFile=$KEYSTORE_PATH
storePassword=$STORE_PASS
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASS
EOF

    echo ""
    echo "Created keystore.properties"
    echo ""
    echo "IMPORTANT: Add these files to .gitignore:"
    echo "  - release-keystore.jks"
    echo "  - keystore.properties"
    echo ""

    # Add to .gitignore if not already there
    GITIGNORE="$PROJECT_DIR/.gitignore"
    if [ -f "$GITIGNORE" ]; then
        grep -q "release-keystore.jks" "$GITIGNORE" || echo "release-keystore.jks" >> "$GITIGNORE"
        grep -q "keystore.properties" "$GITIGNORE" || echo "keystore.properties" >> "$GITIGNORE"
        echo "Updated .gitignore"
    fi
}

# Clean build directory
clean() {
    echo "=== Cleaning build directory ==="
    cd "$PROJECT_DIR"
    ./gradlew clean
    rm -rf "$APK_DIR"
    mkdir -p "$APK_DIR"
}

# Build release APK
build_release() {
    echo "=== Building release APK ==="
    cd "$PROJECT_DIR"

    if [ -n "$KEYSTORE_FILE" ] && [ -f "$KEYSTORE_FILE" ]; then
        # Build with signing via command line
        ./gradlew assembleRelease \
            -Pandroid.injected.signing.store.file="$KEYSTORE_FILE" \
            -Pandroid.injected.signing.store.password="$KEYSTORE_PASSWORD" \
            -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
            -Pandroid.injected.signing.key.password="$KEY_PASSWORD"
    else
        # Build without signing (debug signed)
        echo "Warning: Building without release signing (will be debug-signed)"
        ./gradlew assembleRelease
    fi

    echo "Build complete"
}

# Copy and rename APK
package_apk() {
    echo "=== Packaging APK ==="

    mkdir -p "$APK_DIR"

    # Find the built APK
    APK_SOURCE="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
    UNSIGNED_SOURCE="$PROJECT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"

    if [ -f "$APK_SOURCE" ]; then
        SOURCE="$APK_SOURCE"
    elif [ -f "$UNSIGNED_SOURCE" ]; then
        SOURCE="$UNSIGNED_SOURCE"
        echo "Warning: APK is unsigned"
    else
        echo "Error: No APK found in build outputs"
        ls -la "$PROJECT_DIR/app/build/outputs/apk/release/" 2>/dev/null || echo "Release directory not found"
        exit 1
    fi

    # Create versioned filename matching iOS convention
    APK_NAME="${PRODUCT_NAME}_${VERSION_NAME}_${VERSION_CODE}_android.apk"
    APK_PATH="$APK_DIR/$APK_NAME"

    cp "$SOURCE" "$APK_PATH"

    echo ""
    echo "========================================="
    echo "APK created: $APK_PATH"
    echo "Size: $(du -h "$APK_PATH" | cut -f1)"
    echo "========================================="
}

# Show help
show_help() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  all                Build and package APK (default)"
    echo "  clean              Clean build directory"
    echo "  build              Build release APK only"
    echo "  package            Package APK from existing build"
    echo "  generate-keystore  Generate a new signing keystore"
    echo "  help               Show this help"
    echo ""
    echo "Output:"
    echo "  APK: $APK_DIR/${PRODUCT_NAME}_<version>_<code>_android.apk"
    echo ""
    echo "Signing:"
    echo "  Set KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD"
    echo "  Or create keystore.properties in the android directory"
}

# Main
main() {
    echo "=== FAME Smart Blinds Android Build Script ==="
    echo ""

    # Setup Java environment first
    if ! setup_java; then
        exit 1
    fi
    echo ""

    get_version_info
    echo ""

    case "${1:-all}" in
        all)
            if setup_signing; then
                clean
                build_release
                package_apk
                echo ""
                echo "=== Build complete ==="
                echo "APK: $APK_DIR/${PRODUCT_NAME}_${VERSION_NAME}_${VERSION_CODE}_android.apk"
            else
                echo "Run '$0 generate-keystore' to create a signing key first."
                exit 1
            fi
            ;;
        clean)
            clean
            echo "Clean complete"
            ;;
        build)
            setup_signing || true
            build_release
            ;;
        package)
            package_apk
            ;;
        generate-keystore)
            generate_keystore
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
