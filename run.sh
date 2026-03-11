#!/bin/bash

# Maven path - using IntelliJ bundled Maven
MVN="/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn"

# Fallback to system Maven if IntelliJ not found
if [ ! -f "$MVN" ]; then
    MVN=$(which mvn 2>/dev/null || echo "mvn")
fi

# Load environment variables from .env file
if [ -f .env ]; then
    echo "Loading environment from .env file..."
    export $(grep -v '^#' .env | xargs)
fi

# Check for required environment variables
check_env() {
    if [ -z "${!1}" ]; then
        echo "ERROR: $1 is not set"
        return 1
    fi
    echo "✓ $1 is set"
    return 0
}

echo ""
echo "=== Checking Environment Variables ==="
check_env "OPENAI_API_KEY" || exit 1
check_env "DEEPGRAM_API_KEY" || exit 1
check_env "ELEVENLABS_API_KEY" || exit 1

echo ""
echo "Optional LiveKit configuration:"
check_env "LIVEKIT_API_KEY" || echo "  (LiveKit not configured - will run without RTC)"
check_env "LIVEKIT_API_SECRET" || true
check_env "LIVEKIT_URL" || true

echo ""
echo "=== Building Project ==="
echo "Using Maven: $MVN"
"$MVN" clean package -DskipTests -q

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "=== Starting Voice Agent ==="
java -jar target/MiniVoiceAgent-1.0-SNAPSHOT.jar "$@"
