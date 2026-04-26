#!/bin/bash
echo "🔨 Compilando tun2socks para Android..."

# Requisitos
command -v go >/dev/null 2>&1 || { echo "❌ Go não instalado"; exit 1; }

export GOPATH=${GOPATH:-$HOME/go}
export PATH=$GOPATH/bin:$PATH

# Instalar gomobile
go install golang.org/x/mobile/cmd/gomobile@latest 2>/dev/null
gomobile init 2>/dev/null

# Criar módulo tun2socks
cd $(dirname $0)/tun2socks

# Criar go.mod
cat > go.mod << 'GOMOD'
module tun2socks

go 1.21

require (
    golang.org/x/mobile v0.0.0
)
GOMOD

# Criar o código JNI
cat > tun2socks.go << 'GOCODE'
package tun2socks

import (
    "io"
    "net"
    "strconv"
    "sync"
    "time"
)

var (
    socksAddr string
    connMap   sync.Map
)

type connPair struct {
    tunConn   net.Conn
    socksConn net.Conn
}

// StartTun2Socks inicia o proxy TUN -> SOCKS
// tunFd: file descriptor da interface TUN
// socksAddr: endereço SOCKS (ex: "127.0.0.1:10808")
// mtu: MTU da interface
func StartTun2Socks(tunFd int, socksAddr string, mtu int) error {
    // Criar file descriptor a partir do fd
    tunFile := os.NewFile(uintptr(tunFd), "tun")
    if tunFile == nil {
        return fmt.Errorf("não foi possível criar arquivo TUN")
    }
    defer tunFile.Close()

    buf := make([]byte, mtu)

    for {
        n, err := tunFile.Read(buf)
        if err != nil {
            if err == io.EOF {
                break
            }
            continue
        }

        if n < 20 {
            continue
        }

        // Processar pacote IP
        go processPacket(buf[:n], socksAddr)
    }

    return nil
}

func processPacket(packet []byte, socksAddr string) {
    // Extrair destino do pacote IP
    version := (packet[0] >> 4) & 0x0F
    if version != 4 {
        return
    }

    protocol := packet[9]
    if protocol != 6 && protocol != 17 { // TCP=6, UDP=17
        return
    }

    dstIP := net.IP(packet[16:20])
    dstPort := int(packet[20])<<8 | int(packet[21])

    key := dstIP.String() + ":" + strconv.Itoa(dstPort)

    if pair, ok := connMap.Load(key); ok {
        // Conexão existente
        p := pair.(*connPair)
        p.socksConn.Write(packet)
    } else {
        // Nova conexão SOCKS5
        socksConn, err := net.DialTimeout("tcp", socksAddr, 10*time.Second)
        if err != nil {
            return
        }

        // SOCKS5 handshake
        socksConn.Write([]byte{5, 1, 0})
        resp := make([]byte, 2)
        socksConn.Read(resp)

        // SOCKS5 CONNECT
        connReq := []byte{5, 1, 0, 1}
        connReq = append(connReq, dstIP...)
        connReq = append(connReq, byte(dstPort>>8), byte(dstPort&0xFF))
        socksConn.Write(connReq)
        
        connResp := make([]byte, 10)
        socksConn.Read(connResp)

        if connResp[1] != 0 {
            socksConn.Close()
            return
        }

        connMap.Store(key, &connPair{
            socksConn: socksConn,
        })

        socksConn.Write(packet)
    }
}

func StopTun2Socks() {
    connMap.Range(func(key, value interface{}) bool {
        if pair, ok := value.(*connPair); ok {
            pair.socksConn.Close()
        }
        connMap.Delete(key)
        return true
    })
}
GOCODE

echo "Código Go criado. Agora compilando..."
echo "NOTA: A compilação real requer ajustes para Android NDK."
echo "Este é um esqueleto da lógica."

