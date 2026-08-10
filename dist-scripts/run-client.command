#!/bin/bash
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
    echo "Java 21 was not found on your PATH."
    echo "Install it from https://adoptium.net/temurin/releases/?version=21 then try again."
    read -n 1 -s -r -p "Press any key to close..."
    exit 1
fi

java -Xmx768m --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED -jar client.jar

if [ $? -ne 0 ]; then
    echo ""
    echo "Zelus exited with an error."
    read -n 1 -s -r -p "Press any key to close..."
fi
