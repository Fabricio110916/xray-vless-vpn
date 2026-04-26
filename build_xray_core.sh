#!/bin/bash

echo "??️  Compilando Xray Core para Android..."
echo ""

# Verificar Go
if ! command -v go &> /dev/null; then
    echo "❌ Go não instalado"
    echo "Instale: apt install golang-go ou https://go.dev/dl/"
    exit 1
fi

GO_VERSION=$(go version | grep -oP 'go\K[0-9]+\.[0-9]+')
echo "Go versão: $GO_VERSION"

# Verificar se a versão é compatível
if [ "$(echo "$GO_VERSION < 1.21" | bc -l 2>/dev/null || echo 0)" = "1" ]; then
    echo "❌ Go muito antigo. Instale Go 1.21+"
    exit 1
fi

export GOPATH=${GOPATH:-$HOME/go}
export PATH=$GOPATH/bin:$PATH

# Instalar gomobile
echo "?? Instalando gomobile..."
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20231127183840-76d4a6d75e67

# Inicializar gomobile
echo "⚙️  Inicializando gomobile..."
gomobile init 2>/dev/null || true

# Criar diretório libs
mkdir -p app/libs

# Verificar se já existe
if [ -f "app/libs/xray.aar" ]; then
    echo "⚠️  xray.aar já existe. Removendo..."
    rm app/libs/xray.aar
fi

# Baixar Xray
echo "?? Baixando Xray Core..."
if [ ! -d "/tmp/xray-build" ]; then
    git clone --depth 1 https://github.com/XTLS/Xray-core.git /tmp/xray-build
fi

cd /tmp/xray-build

# Compilar
echo "?? Compilando (isso pode levar alguns minutos)..."
gomobile bind \
  -v \
  -target=android \
  -androidapi 21 \
  -o $OLDPWD/app/libs/xray.aar \
  -ldflags="-s -w -buildid=" \
  ./core

if [ $? -eq 0 ]; then
    echo ""
    echo "========================================="
    echo "✅ XRAY CORE COMPILADO COM SUCESSO!"
    echo "========================================="
    echo "Arquivo: app/libs/xray.aar"
    ls -la $OLDPWD/app/libs/xray.aar
    echo ""
    echo "Agora compile o APK:"
    echo "  cd $OLDPWD"
    echo "  ./gradlew assembleDebug"
else
    echo "❌ Falha na compilação"
    exit 1
fi
