#!/bin/zsh
set -euo pipefail

SCRIPT_DIR=${0:A:h}
OUTPUT_DIR=${1:-"$SCRIPT_DIR/dist"}
APP_NAME="LibrePods My.app"
ZIP_NAME="LibrePods-My-Mac-0.1.0.zip"

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=${OUTPUT_DIR:A}
APP_PATH="$OUTPUT_DIR/$APP_NAME"
ZIP_PATH="$OUTPUT_DIR/$ZIP_NAME"

if [[ -e "$APP_PATH" || -e "$ZIP_PATH" ]]; then
  print -u2 "출력 파일이 이미 있어요: $OUTPUT_DIR"
  exit 2
fi

BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/librepods-my-mac.XXXXXX")
trap 'rm -rf "$BUILD_DIR"' EXIT

cd "$SCRIPT_DIR"
swift build -c release --arch arm64 --arch x86_64 --scratch-path "$BUILD_DIR/swift-build"
BIN_DIR=$(swift build -c release --arch arm64 --arch x86_64 --scratch-path "$BUILD_DIR/swift-build" --show-bin-path)

STAGED_APP="$BUILD_DIR/$APP_NAME"
mkdir -p "$STAGED_APP/Contents/MacOS" "$STAGED_APP/Contents/Resources"
cp "$BIN_DIR/LibrePodsMyMac" "$STAGED_APP/Contents/MacOS/LibrePodsMyMac"
cp "$SCRIPT_DIR/App/Info.plist" "$STAGED_APP/Contents/Info.plist"
cp "$SCRIPT_DIR/../LICENSE" "$STAGED_APP/Contents/Resources/LICENSE"
chmod 755 "$STAGED_APP/Contents/MacOS/LibrePodsMyMac"

codesign --force --deep --sign - "$STAGED_APP"
codesign --verify --deep --strict "$STAGED_APP"

ditto "$STAGED_APP" "$APP_PATH"
ditto -c -k --sequesterRsrc --keepParent "$STAGED_APP" "$ZIP_PATH"

print "$APP_PATH"
print "$ZIP_PATH"
