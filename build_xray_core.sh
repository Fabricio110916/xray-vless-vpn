#!/bin/bash

echo "??️  Compilando Xray Core para Android..."
echo ""

# Verificar dependências
command -v go >/dev/null 2>&1 || { echo "❌ Go não instalado. Instale: apt install golang-go"; exit 1; }
command -v gomobile >/dev/null 2>&1 || { echo "Instalando gomobile..."; go install golang.org/x/mobile/cmd/gomobile@latest; }

# Configurar ambiente
export ANDROID_HOME=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
export PATH=$PATH:$(go env GOPATH)/bin

# Inicializar gomobile
gomobile init 2>/dev/null || true

# Baixar Xray Core
echo "?? Baixando Xray Core..."
if [ ! -d "Xray-core" ]; then
    git clone --depth 1 https://github.com/XTLS/Xray-core.git
fi

cd Xray-core

# Compilar para Android
echo "?? Compilando para Android (armeabi-v7a, arm64-v8a, x86, x86_64)..."
gomobile bind -v \
    -target=android \
    -androidapi 21 \
    -o ../app/libs/xray.aar \
    -ldflags="-s -w" \
    ./core

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Xray Core compilado com sucesso!"
    echo "?? AAR gerado em: app/libs/xray.aar"
    
    # Extrair .so do AAR
    cd ../app/libs
    unzip -o xray.aar -d xray_libs
    cp -r xray_libs/jni/* ../src/main/jniLibs/ 2>/dev/null || true
    echo "✅ Bibliotecas nativas extraídas"
else
    echo "❌ Falha na compilação"
fi
