#!/bin/bash

# WebSocket Voice Agent Server
# Starts a WebSocket server that serves a web UI for voice interaction

set -e

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║        🎤 Starting WebSocket Voice Agent Server           ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""

# Load environment variables from .env if it exists
if [ -f .env ]; then
    echo "Loading configuration from .env..."
    while IFS='=' read -r key value; do
        # Skip comments and empty lines
        [[ "$key" =~ ^#.*$ ]] && continue
        [[ -z "$key" ]] && continue
        # Remove any surrounding quotes from value
        value="${value%\"}"
        value="${value#\"}"
        export "$key=$value"
    done < .env
fi

# Maven path (use IntelliJ's Maven or system Maven)
INTELLIJ_MVN="/Applications/IntelliJ IDEA CE.app/Contents/plugins/maven/lib/maven3/bin/mvn"
if [ -f "$INTELLIJ_MVN" ]; then
    MVN="$INTELLIJ_MVN"
elif command -v mvn &> /dev/null; then
    MVN="mvn"
else
    echo "❌ Maven not found! Please install Maven or open the project in IntelliJ."
    exit 1
fi

# Verify key environment variables
echo "Checking configuration..."
if [ -z "$GEMINI_API_KEY" ] && [ -z "$OPENAI_API_KEY" ]; then
    echo "❌ Error: No LLM API key found. Set GEMINI_API_KEY or OPENAI_API_KEY in .env"
    exit 1
fi
echo "✅ LLM API key found"

if [ -z "$DEEPGRAM_API_KEY" ]; then
    echo "❌ Error: DEEPGRAM_API_KEY not found in .env"
    exit 1
fi
echo "✅ Deepgram API key found"

if [ -z "$ELEVENLABS_API_KEY" ]; then
    echo "❌ Error: ELEVENLABS_API_KEY not found in .env"
    exit 1
fi
echo "✅ ElevenLabs API key found"
echo ""

# Compile
echo "Compiling..."
"$MVN" compile -q

# Run with environment variables passed as JVM system properties
echo ""
echo "Starting server..."
echo ""

# Build classpath and run directly with Java
CLASSPATH=$("$MVN" dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q 2>/dev/null)
CLASSPATH="target/classes:$CLASSPATH"

# Find Java
if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="java"
fi

# Run the WebSocket server
exec "$JAVA" \
    -DGEMINI_API_KEY="$GEMINI_API_KEY" \
    -DOPENAI_API_KEY="$OPENAI_API_KEY" \
    -DDEEPGRAM_API_KEY="$DEEPGRAM_API_KEY" \
    -DELEVENLABS_API_KEY="$ELEVENLABS_API_KEY" \
    -DLIVEKIT_URL="$LIVEKIT_URL" \
    -DLIVEKIT_API_KEY="$LIVEKIT_API_KEY" \
    -DLIVEKIT_API_SECRET="$LIVEKIT_API_SECRET" \
    -cp "$CLASSPATH" \
    org.example.WebSocketVoiceServer
