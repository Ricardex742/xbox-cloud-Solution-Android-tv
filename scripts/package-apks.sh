#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Android SDK required}"
: "${CLOUDX_KEYSTORE:?Persistent signing keystore required}"
: "${CLOUDX_KEY_ALIAS:?Key alias required}"
: "${CLOUDX_STORE_PASSWORD:?Store password required}"
: "${CLOUDX_KEY_PASSWORD:?Key password required}"
raw=app/build/outputs/apk/release/app-release-unsigned.apk
[[ -s "$raw" ]]
bt="$ANDROID_HOME/build-tools/35.0.0"
mkdir -p dist_apks
for arch in armeabi-v7a arm64-v8a universal; do
  case "$arch" in
    armeabi-v7a) name=CloudX-TV-UltraLeve-32bit-armeabi-v7a ;;
    arm64-v8a) name=CloudX-UltraLeve-64bit-arm64-v8a ;;
    universal) name=CloudX-Universal-ARM ;;
  esac
  target="dist_apks/$name.apk"
  python3 scripts/filter-apk.py "$raw" "$target" "$arch"
  "$bt/zipalign" -f -p 4 "$target" "$target.aligned"
  mv "$target.aligned" "$target"
  "$bt/apksigner" sign --ks "$CLOUDX_KEYSTORE" --ks-key-alias "$CLOUDX_KEY_ALIAS" \
    --ks-pass env:CLOUDX_STORE_PASSWORD --key-pass env:CLOUDX_KEY_PASSWORD "$target"
  "$bt/apksigner" verify "$target"
  "$bt/zipalign" -c -p 4 "$target"
done
sha256sum dist_apks/*.apk > dist_apks/SHA256SUMS
