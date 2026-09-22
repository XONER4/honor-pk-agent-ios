#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ ! -f HonorPKAgent/DeepSeekConfig.plist ]; then
  cp HonorPKAgent/DeepSeekConfig.example.plist HonorPKAgent/DeepSeekConfig.plist
fi
xcodegen generate
xcodebuild -project HonorPKAgent.xcodeproj -scheme HonorPKAgent \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath build/device CODE_SIGNING_ALLOWED=NO build
mkdir -p build/package/Payload
cp -R build/device/Build/Products/Release-iphoneos/HonorPKAgent.app build/package/Payload/
(cd build/package && zip -qry ../Honor-PK-Agent-unsigned.ipa Payload)
echo 'Created build/Honor-PK-Agent-unsigned.ipa'
