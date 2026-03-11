# MiniVoiceAgent

[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A production-grade, real-time voice AI agent built in Java. This project demonstrates how to build streaming voice applications using reactive architecture with proper handling of audio streams, state management, and the critical **barge-in problem** (detecting when a user interrupts the agent).

## Features

- **Real-time Voice Pipeline**: Audio → VAD → STT → LLM → TTS → Audio
- **Barge-In Detection**: Handles user interruptions gracefully
- **Reactive Streams**: Non-blocking, backpressure-aware audio processing with Project Reactor
- **Multiple LLM Support**: Google Gemini (FREE tier) or OpenAI GPT-4
- **Streaming STT**: Deepgram WebSocket streaming transcription
- **Streaming TTS**: ElevenLabs sentence-by-sentence synthesis for low latency
- **Multiple Run Modes**: Local microphone, WebSocket server with web UI, or LiveKit WebRTC

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Voice Agent Architecture                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌──────────┐ │
│  │  Audio   │───▶│   VAD   │───▶│   STT   │───▶│   LLM   │───▶│   TTS    │ │
│  │  Input   │    │ Detect  │    │Deepgram │    │ Gemini/ │    │ElevenLabs│ │
│  │          │◀───│ Speech  │    │         │    │ OpenAI  │    │          │ │
│  └──────────┘    └─────────┘    └─────────┘    └─────────┘    └──────────┘ │
│       ▲                              │              │              │        │
│       │                              ▼              ▼              ▼        │
│       │                        ┌─────────────────────────────────────┐      │
│       │                        │        Event Bus (Reactive)         │      │
│       │                        └─────────────────────────────────────┘      │
│       │                                         │                           │
│       │                                         ▼                           │
│       │                        ┌─────────────────────────────────────┐      │
│       └────────────────────────│      Voice Agent Orchestrator       │      │
│                                │   (State Machine + Barge-In Logic)  │      │
│                                └─────────────────────────────────────┘      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### State Machine

```
IDLE → LISTENING → THINKING → SPEAKING → IDLE
                ↑              │
                └── INTERRUPTED ←┘ (barge-in)
```

## Quick Start

### Prerequisites

- **Java 21+**
- **Maven 3.8+**
- **API Keys**: Deepgram, ElevenLabs, and either Gemini (FREE) or OpenAI

### 1. Clone the Repository

```bash
git clone https://github.com/yourusername/MiniVoiceAgent.git
cd MiniVoiceAgent
```

### 2. Set Up Environment Variables

Copy the example environment file and add your API keys:

```bash
cp .env.example .env
```

Edit `.env` with your API keys:

```bash
# Required - LLM (choose one)
GEMINI_API_KEY=your-gemini-key          # FREE tier available!
# OR
OPENAI_API_KEY=your-openai-key

# Required - Speech Services
DEEPGRAM_API_KEY=your-deepgram-key
ELEVENLABS_API_KEY=your-elevenlabs-key

# Optional - LiveKit (for WebRTC mode)
LIVEKIT_API_KEY=your-livekit-key
LIVEKIT_API_SECRET=your-livekit-secret
LIVEKIT_URL=wss://your-livekit-server
```

### 3. Build the Project

```bash
mvn clean package -DskipTests
```

### 4. Run the Agent

**Production mode** (requires all API keys):
```bash
java -jar target/MiniVoiceAgent-1.0-SNAPSHOT.jar
```

**Demo mode** (no API calls - demonstrates architecture):
```bash
java -jar target/MiniVoiceAgent-1.0-SNAPSHOT.jar --demo
```

**Validate configuration**:
```bash
java -jar target/MiniVoiceAgent-1.0-SNAPSHOT.jar --validate
```

## Running Modes

### 1. Local Microphone Test

Direct interaction using your system microphone and speakers:

```bash
./mic-test.sh
# Or manually:
java --enable-preview -cp target/MiniVoiceAgent-1.0-SNAPSHOT.jar org.example.LocalMicrophoneTest
```

### 2. WebSocket Server (Web UI)

Launches a WebSocket server with a browser-based interface:

```bash
./websocket-server.sh
```

Then open `http://localhost:3000` in your browser. The web UI captures your microphone and streams audio to the server.

### 3. LiveKit WebRTC

Full WebRTC integration via LiveKit (requires LiveKit server):

```bash
./run.sh
```

## Docker

### Build the Docker Image

```bash
docker build -t mini-voice-agent .
```

### Run with Docker

```bash
docker run -it --rm \
  -e GEMINI_API_KEY=your-key \
  -e DEEPGRAM_API_KEY=your-key \
  -e ELEVENLABS_API_KEY=your-key \
  mini-voice-agent
```

### Run with Docker Compose

```bash
# Copy and configure environment
cp .env.example .env
# Edit .env with your API keys

docker-compose up
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `GEMINI_API_KEY` | - | Google Gemini API key (FREE tier available) |
| `OPENAI_API_KEY` | - | OpenAI API key (alternative to Gemini) |
| `DEEPGRAM_API_KEY` | - | Deepgram STT API key |
| `ELEVENLABS_API_KEY` | - | ElevenLabs TTS API key |
| `LLM_MODEL` | `gpt-4o` | LLM model name |
| `TTS_VOICE_ID` | `21m00Tcm4TlvDq8ikWAM` | ElevenLabs voice (Rachel) |
| `STT_MODEL` | `nova-2` | Deepgram model |
| `ENABLE_BARGE_IN` | `true` | Enable interruption handling |
| `AGENT_NAME` | `VoiceAgent` | Agent identifier |
| `LIVEKIT_URL` | `wss://localhost:7880` | LiveKit server URL |
| `LIVEKIT_API_KEY` | - | LiveKit API key |
| `LIVEKIT_API_SECRET` | - | LiveKit API secret |

## Project Structure

```
src/main/java/org/example/
├── Main.java                          # Main entry point
├── LocalMicrophoneTest.java           # Direct microphone testing
├── WebSocketVoiceServer.java          # WebSocket server with web UI
├── LiveKitVoiceAgent.java             # LiveKit RTC integration
├── agent/
│   └── VoiceAgentOrchestrator.java    # Core orchestrator (state machine)
├── config/
│   └── VoiceAgentConfig.java          # Configuration management
├── core/
│   ├── event/
│   │   ├── EventBus.java              # Reactive event distribution
│   │   └── VoiceEvent.java            # Sealed event types (Java 21)
│   └── model/
│       ├── AgentState.java            # State machine enum
│       ├── AudioFrame.java            # Audio data container
│       ├── ConversationTurn.java      # Conversation history
│       └── TranscriptSegment.java     # STT results
├── llm/
│   ├── AgentBrain.java                # LLM interface
│   ├── GeminiBrain.java               # Google Gemini implementation
│   └── LangChain4jBrain.java          # OpenAI via LangChain4j
├── rtc/
│   ├── RTCParticipant.java            # RTC interface
│   └── LiveKitParticipant.java        # LiveKit implementation
├── stt/
│   ├── SpeechToTextService.java       # STT interface
│   └── DeepgramSTTService.java        # Deepgram WebSocket streaming
├── tts/
│   ├── TextToSpeechService.java       # TTS interface
│   └── ElevenLabsTTSService.java      # ElevenLabs streaming
└── vad/
    ├── VoiceActivityDetector.java     # VAD interface
    └── EnergyBasedVAD.java            # Energy-based speech detection
```

## The Barge-In Problem

One of the key challenges in voice agents is handling **barge-in** - when the user interrupts the agent while it's speaking.

### How It Works

1. **VAD runs continuously** - even during agent speech
2. **Speech detected during SPEAKING state** = barge-in detected
3. **Immediate cancellation** of LLM generation and TTS output
4. **State transition** to LISTENING to capture user's new input

```java
// Simplified barge-in handling
if (vadResult.speechStarted() && currentState == AgentState.SPEAKING) {
    brain.cancelGeneration();      // Stop LLM
    ttsSession.cancel();           // Stop TTS
    transitionTo(AgentState.LISTENING);
}
```

## Tech Stack

| Component | Technology | Purpose |
|-----------|------------|---------|
| **Language** | Java 21 | Modern Java with preview features |
| **Build** | Maven | Dependency management |
| **AI Framework** | LangChain4j | LLM orchestration |
| **LLM** | Gemini / OpenAI | Response generation |
| **STT** | Deepgram | Speech-to-text streaming |
| **TTS** | ElevenLabs | Text-to-speech streaming |
| **RTC** | LiveKit | WebRTC communication |
| **Reactive** | Project Reactor | Non-blocking streams |
| **HTTP/WS** | OkHttp | WebSocket client |
| **JSON** | Jackson | JSON processing |

## API Keys Setup

### Gemini (FREE)
1. Go to [Google AI Studio](https://aistudio.google.com/)
2. Create an API key
3. Set `GEMINI_API_KEY` environment variable

### Deepgram
1. Sign up at [Deepgram](https://deepgram.com/)
2. Create an API key in the console
3. Set `DEEPGRAM_API_KEY` environment variable

### ElevenLabs
1. Sign up at [ElevenLabs](https://elevenlabs.io/)
2. Get your API key from the profile section
3. Set `ELEVENLABS_API_KEY` environment variable

### OpenAI (Optional)
1. Sign up at [OpenAI](https://platform.openai.com/)
2. Create an API key
3. Set `OPENAI_API_KEY` environment variable

## Scripts

| Script | Description |
|--------|-------------|
| `run.sh` | Build and run in production mode |
| `demo.sh` | Build and run in demo mode (no API calls) |
| `mic-test.sh` | Run local microphone test |
| `websocket-server.sh` | Start WebSocket server with web UI |
| `generate-token.sh` | Generate LiveKit tokens |

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [LangChain4j](https://docs.langchain4j.dev/) - AI orchestration framework
- [LiveKit](https://livekit.io/) - Real-time communication
- [Deepgram](https://deepgram.com/) - Speech recognition
- [ElevenLabs](https://elevenlabs.io/) - Voice synthesis
- [Project Reactor](https://projectreactor.io/) - Reactive programming

---

**Author**: Arpit Shrivastava

**GitHub**: [github.com/arpit783]((https://github.com/arpit783))
