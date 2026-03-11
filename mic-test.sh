#!/bin/bash

# Local microphone test - speak directly to the voice agent

# Maven path - using IntelliJ bundled Maven
MVN="/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn"

if [ ! -f "$MVN" ]; then
    MVN=$(which mvn 2>/dev/null || echo "mvn")
fi

# Load environment variables
if [ -f .env ]; then
    echo "Loading environment from .env file..."
    set -a
    source .env
    set +a
fi

echo ""
echo "=== Building Project ==="
"$MVN" clean package -DskipTests -q

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "=== Starting Local Microphone Test ==="
echo "Deepgram API Key: ${DEEPGRAM_API_KEY:0:8}..."
echo "Gemini API Key: ${GEMINI_API_KEY:0:8}..."
echo ""

# Run the shaded uber-jar with the LocalMicrophoneTest main class
java -cp "target/MiniVoiceAgent-1.0-SNAPSHOT.jar" org.example.LocalMicrophoneTest
