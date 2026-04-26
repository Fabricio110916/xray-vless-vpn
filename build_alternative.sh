#!/bin/bash
echo "Tentando compilar com Gradle..."

# Se tiver Android SDK, compilar diretamente
if [ -d "$ANDROID_SDK_ROOT" ] || [ -d "$ANDROID_HOME" ]; then
    export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$ANDROID_HOME}
    export ANDROID_HOME=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
    
    # Criar local.properties
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    
    # Tentar compilar
    ./gradlew assembleDebug --no-daemon
else
    echo "Android SDK não encontrado"
    exit 1
fi
