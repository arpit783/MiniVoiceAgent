#!/bin/bash

# Load environment variables
if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
fi

# Generate a LiveKit token for testing
# You can use this token to join the room from a browser

ROOM_NAME="${1:-voice-agent-room}"
PARTICIPANT_NAME="${2:-test-user}"

echo ""
echo "=== LiveKit Token Generator ==="
echo "Room: $ROOM_NAME"
echo "Participant: $PARTICIPANT_NAME"
echo ""
echo "To join from browser, go to:"
echo "https://meet.livekit.io"
echo ""
echo "Or use the LiveKit CLI:"
echo "lk room join --url $LIVEKIT_URL --api-key $LIVEKIT_API_KEY --api-secret $LIVEKIT_API_SECRET --room $ROOM_NAME --identity $PARTICIPANT_NAME"
echo ""
echo "LiveKit Cloud URL: $LIVEKIT_URL"
echo ""
