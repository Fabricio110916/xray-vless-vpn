#!/bin/bash
echo "Testando parse da URL VLESS..."
echo ""

URL='vless://ace48b42-c8dc-42b6-aaba-e88f4a4f0e4e@ofertas.tim.com.br:443?mode=auto&path=%2Fws%2F&security=tls&alpn=h2%2Chttp%2F1.1&encryption=none&insecure=1&host=7aaxhh.azion.app&fp=chrome&type=xhttp&allowInsecure=1&sni=ofertas.tim.com.br#Freenet'

echo "URL: $URL"
echo ""

# Extrair campos como o app faz
CLEAN="${URL#vless://}"
UUID="${CLEAN%%@*}"
REST="${CLEAN#*@}"
SERVER="${REST%%:*}"
PORT="${REST#*:}"; PORT="${PORT%%\?*}"; PORT="${PORT%%#*}"
PARAMS="${REST#*\?}"; PARAMS="${PARAMS%%#*}"
REMARK="${REST#*#}"

echo "UUID: $UUID"
echo "Server: $SERVER"
echo "Port: $PORT"
echo "Remark: $REMARK"
echo ""

# Extrair cada parâmetro
echo "Parâmetros:"
IFS='&' read -ra PAR <<< "$PARAMS"
for p in "${PAR[@]}"; do
    key="${p%%=*}"
    val="${p#*=}"
    # Decodificar URL
    val=$(python3 -c "import urllib.parse; print(urllib.parse.unquote('$val'))" 2>/dev/null || echo "$val")
    echo "  $key = $val"
done
