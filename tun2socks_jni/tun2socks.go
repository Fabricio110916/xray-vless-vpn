package tun2socks

import (
    "fmt"
    "io"
    "net"
    "os"
    "strconv"
    "sync"
    "time"
)

var (
    socksAddr  string
    connMap    sync.Map
    globalMTU  int
)

type connPair struct {
    socksConn net.Conn
    dstIP     net.IP
    dstPort   int
}

//export StartTun2Socks
func StartTun2Socks(tunFd int, socksAddrParam string, mtu int) {
    socksAddr = socksAddrParam
    globalMTU = mtu

    tunFile := os.NewFile(uintptr(tunFd), "tun")
    if tunFile == nil {
        fmt.Println("ERRO: não foi possível criar arquivo TUN")
        return
    }
    defer tunFile.Close()

    buf := make([]byte, mtu)
    fmt.Println("Tun2Socks iniciado - MTU:", mtu, "SOCKS:", socksAddr)

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

        go processPacket(buf[:n])
    }
}

func processPacket(packet []byte) {
    version := (packet[0] >> 4) & 0x0F
    if version != 4 {
        return
    }

    protocol := packet[9]
    if protocol != 6 && protocol != 17 {
        return
    }

    dstIP := net.IP(packet[16:20])
    dstPort := int(packet[20])<<8 | int(packet[21])

    key := dstIP.String() + ":" + strconv.Itoa(dstPort)

    if val, ok := connMap.Load(key); ok {
        pair := val.(*connPair)
        pair.socksConn.SetWriteDeadline(time.Now().Add(30 * time.Second))
        pair.socksConn.Write(packet)
    } else {
        go createSocksConnection(key, dstIP, dstPort, packet)
    }
}

func createSocksConnection(key string, dstIP net.IP, dstPort int, packet []byte) {
    socksConn, err := net.DialTimeout("tcp", socksAddr, 10*time.Second)
    if err != nil {
        return
    }

    // SOCKS5 handshake
    socksConn.Write([]byte{5, 1, 0})
    resp := make([]byte, 2)
    _, err = socksConn.Read(resp)
    if err != nil || resp[1] != 0 {
        socksConn.Close()
        return
    }

    // SOCKS5 CONNECT
    connReq := []byte{5, 1, 0, 1} // VER, CMD=CONNECT, RSV, ATYP=IPv4
    connReq = append(connReq, dstIP.To4()...)
    connReq = append(connReq, byte(dstPort>>8), byte(dstPort&0xFF))
    socksConn.Write(connReq)

    connResp := make([]byte, 10)
    _, err = socksConn.Read(connResp)
    if err != nil || connResp[1] != 0 {
        socksConn.Close()
        return
    }

    connMap.Store(key, &connPair{
        socksConn: socksConn,
        dstIP:     dstIP,
        dstPort:   dstPort,
    })

    socksConn.Write(packet)
}

//export StopTun2Socks
func StopTun2Socks() {
    connMap.Range(func(key, value interface{}) bool {
        if pair, ok := value.(*connPair); ok {
            pair.socksConn.Close()
        }
        connMap.Delete(key)
        return true
    })
    fmt.Println("Tun2Socks parado")
}

func main() {}
