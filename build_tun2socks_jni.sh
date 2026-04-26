#!/bin/bash
echo "?? Compilando libtun2socks.so para Android..."
echo ""

# Verificar dependências
command -v go >/dev/null 2>&1 || { echo "❌ Go não instalado"; exit 1; }

# Verificar NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/26.1.10909125"
fi

if [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "❌ Android NDK não encontrado em $ANDROID_NDK_HOME"
    echo "Defina ANDROID_NDK_HOME ou instale o NDK"
    exit 1
fi

echo "NDK: $ANDROID_NDK_HOME"
echo ""

# Compilar para cada arquitetura
cd tun2socks_jni

for ARCH in arm64-v8a armeabi-v7a x86_64; do
    case $ARCH in
        arm64-v8a)
            TARGET="aarch64-linux-android"
            API=21
            ;;
        armeabi-v7a)
            TARGET="armv7a-linux-androideabi"
            API=21
            ;;
        x86_64)
            TARGET="x86_64-linux-android"
            API=21
            ;;
    esac
    
    CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${TARGET}${API}-clang"
    
    echo "Compilando $ARCH..."
    CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CC" \
        go build -buildmode=c-shared -o ../app/src/main/jniLibs/$ARCH/libtun2socks.so . 2>/dev/null
    
    if [ -f "../app/src/main/jniLibs/$ARCH/libtun2socks.so" ]; then
        echo "  ✅ $ARCH OK ($(ls -lh ../app/src/main/jniLibs/$ARCH/libtun2socks.so | awk '{print $5}'))"
    else
        echo "  ❌ $ARCH falhou"
    fi
done

echo ""
echo "✅ Compilação concluída!"
find ../app/src/main/jniLibs -name "libtun2socks.so" -exec ls -lh {} \;
