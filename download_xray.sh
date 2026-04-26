#!/bin/bash
echo "?? Baixando Xray Core v26.4.25 para Android..."
echo ""

VERSION="26.4.25"
BASE_URL="https://github.com/XTLS/Xray-core/releases/download/v${VERSION}"
mkdir -p app/src/main/jniLibs

# Arquiteturas disponíveis e seus nomes no GitHub
declare -A ARCH_MAP
ARCH_MAP["arm64-v8a"]="Xray-android-arm64-v8a.zip"
ARCH_MAP["armeabi-v7a"]="Xray-linux-arm32-v7a.zip"    # Android arm32 usa o mesmo que linux arm32
ARCH_MAP["x86_64"]="Xray-android-amd64.zip"
ARCH_MAP["x86"]="Xray-linux-32.zip"                    # x86 usa linux 32

for ARCH in "${!ARCH_MAP[@]}"; do
  ZIP="${ARCH_MAP[$ARCH]}"
  echo "Baixando $ARCH ($ZIP)..."
  
  wget -q "${BASE_URL}/${ZIP}" -O /tmp/${ZIP} 2>/dev/null
  
  if [ -f "/tmp/${ZIP}" ]; then
    rm -rf /tmp/xray_${ARCH}
    mkdir -p /tmp/xray_${ARCH}
    unzip -q -o /tmp/${ZIP} -d /tmp/xray_${ARCH} 2>/dev/null
    
    # Procurar o binário xray (pode ter nomes diferentes)
    XRAY_BIN=$(find /tmp/xray_${ARCH} -type f -name "xray" -o -name "libxray.so" | head -1)
    
    if [ -n "$XRAY_BIN" ]; then
      mkdir -p app/src/main/jniLibs/$ARCH
      
      # Se for binário xray, renomear para libxray.so
      if [[ "$XRAY_BIN" == *"/xray" ]]; then
        cp "$XRAY_BIN" "app/src/main/jniLibs/$ARCH/libxray.so"
        echo "✅ $ARCH OK (binário xray copiado como libxray.so)"
      else
        cp "$XRAY_BIN" "app/src/main/jniLibs/$ARCH/"
        echo "✅ $ARCH OK (libxray.so)"
      fi
      
      ls -lh app/src/main/jniLibs/$ARCH/libxray.so 2>/dev/null
    else
      echo "⚠️  Nenhum binário encontrado em $ZIP"
      echo "   Conteúdo do zip:"
      unzip -l /tmp/${ZIP} 2>/dev/null | head -10
    fi
  else
    echo "⚠️  $ZIP não disponível"
  fi
done

echo ""
echo "========================================="
echo "RESULTADO:"
echo "========================================="
find app/src/main/jniLibs -name "*.so" -type f -exec ls -lh {} \; 2>/dev/null

if find app/src/main/jniLibs -name "libxray.so" | grep -q .; then
  echo ""
  echo "✅ DOWNLOAD CONCLUÍDO COM SUCESSO!"
else
  echo ""
  echo "❌ Nenhuma biblioteca baixada"
fi
