#!/bin/bash
echo "?? Baixando Xray executável para Android..."

VERSION="26.4.25"
BASE="https://github.com/XTLS/Xray-core/releases/download/v${VERSION}"
mkdir -p app/src/main/jniLibs

# Mapeamento
declare -A ARCHS
ARCHS["arm64-v8a"]="Xray-android-arm64-v8a.zip"
ARCHS["armeabi-v7a"]="Xray-linux-arm32-v7a.zip"
ARCHS["x86_64"]="Xray-android-amd64.zip"

for ARCH in "${!ARCHS[@]}"; do
    ZIP="${ARCHS[$ARCH]}"
    echo "Baixando $ARCH: $ZIP"
    wget -q "${BASE}/${ZIP}" -O /tmp/xray_${ARCH}.zip 2>/dev/null
    
    if [ -f "/tmp/xray_${ARCH}.zip" ]; then
        rm -rf /tmp/xray_extract
        mkdir -p /tmp/xray_extract
        unzip -q -o /tmp/xray_${ARCH}.zip -d /tmp/xray_extract 2>/dev/null
        
        echo "  Conteúdo do zip:"
        find /tmp/xray_extract -type f -exec ls -lh {} \;
        
        # Procurar executável xray
        XRAY_BIN=$(find /tmp/xray_extract -type f -name "xray" -o -name "libxray.so" | head -1)
        
        if [ -n "$XRAY_BIN" ]; then
            mkdir -p app/src/main/jniLibs/$ARCH
            cp "$XRAY_BIN" "app/src/main/jniLibs/$ARCH/libxray.so"
            echo "  ✅ $ARCH: $(ls -lh app/src/main/jniLibs/$ARCH/libxray.so | awk '{print $5}')"
            
            # Verificar tipo do arquivo
            file "app/src/main/jniLibs/$ARCH/libxray.so"
        fi
    fi
done

echo ""
echo "✅ Download concluído!"
find app/src/main/jniLibs -name "*.so" -type f -exec ls -lh {} \;
