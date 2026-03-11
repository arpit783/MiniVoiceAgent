#!/bin/bash

# Run the voice agent in demo mode (no API calls required)
# This demonstrates the architecture and event flow

# Maven path - using IntelliJ bundled Maven
MVN="/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn"

# Fallback to system Maven if IntelliJ not found
if [ ! -f "$MVN" ]; then
    MVN=$(which mvn 2>/dev/null || echo "mvn")
fi

echo ""
echo "=== Building Project ==="
echo "Using Maven: $MVN"
"$MVN" clean package -DskipTests -q

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "=== Running Demo Mode ==="
java -jar target/MiniVoiceAgent-1.0-SNAPSHOT.jar --demo
