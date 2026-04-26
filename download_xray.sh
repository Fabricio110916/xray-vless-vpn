#!/bin/bash
echo "?? Baixando Xray Core pré-compilado..."

XRAY_VERSION="1.8.23"
BASE_URL="https://github.com/XTLS/Xray-core/releases/download/v${XRAY_VERSION}"

mkdir -p app/src/main/jniLibs

for ARCH in armeabi-v7a arm64-v8a x86 x86_64; do
  case $ARCH in
    armeabi-v7a) ZIP="Xray-android-arm32-v7a.zip" ;;
    arm64-v8a)   ZIP="Xray-android-arm64-v8a.zip" ;;
    x86)         ZIP="Xray-android-x86.zip" ;;
    x86_64)      ZIP="Xray-android-x86_64.zip" ;;
  esac
  
  echo "Baixando $ARCH..."
  wget -q "${BASE_URL}/${ZIP}" -O /tmp/${ZIP} 2>/dev/null
  
  if [ -f "/tmp/${ZIP}" ]; then
    mkdir -p app/src/main/jniLibs/$ARCH
    unzip -q -o /tmp/${ZIP} -d /tmp/xray_${ARCH}
    cp /tmp/xray_${ARCH}/libxray.so app/src/main/jniLibs/$ARCH/ 2>/dev/null
    echo "✅ $ARCH OK"
  else
    echo "⚠️  $ARCH não disponível"
  fi
done

echo ""
echo "✅ Download concluído!"
find app/src/main/jniLibs -name "*.so" -type f -exec ls -lh {} \;
