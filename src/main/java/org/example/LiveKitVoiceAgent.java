package org.example;

import io.livekit.server.AccessToken;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomCreate;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;

import org.example.agent.VoiceAgentOrchestrator;
import org.example.config.VoiceAgentConfig;
import org.example.core.event.EventBus;
import org.example.core.event.VoiceEvent;
import org.example.core.model.AgentState;
import org.example.llm.AgentBrain;
import org.example.llm.GeminiBrain;
import org.example.stt.DeepgramSTTService;
import org.example.tts.ElevenLabsTTSService;
import org.example.vad.EnergyBasedVAD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * LiveKit Voice Agent Server
 * 
 * This starts an HTTP server that:
 * 1. Serves a web page for users to join and talk to the agent
 * 2. Generates LiveKit tokens for authentication
 * 3. Runs the voice agent that connects to the same LiveKit room
 * 
 * Users can join via browser and have a voice conversation with the AI agent.
 */
public class LiveKitVoiceAgent {
    private static final Logger log = LoggerFactory.getLogger(LiveKitVoiceAgent.class);
    
    private static final int HTTP_PORT = 3000;
    
    private static VoiceAgentConfig config;

    public static void main(String[] args) throws Exception {
        printBanner();
        
        config = VoiceAgentConfig.fromEnvironment();
        
        if (!config.isLiveKitConfigured()) {
            log.error("LiveKit is not configured! Please set LIVEKIT_API_KEY, LIVEKIT_API_SECRET, and LIVEKIT_URL");
            System.exit(1);
        }
        
        log.info("LiveKit Configuration:");
        log.info("  URL: {}", config.liveKitServerUrl());
        log.info("  Room: {}", config.defaultRoom());
        
        // Start HTTP server for web client and token generation
        HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        server.createContext("/", new WebPageHandler());
        server.createContext("/token", new TokenHandler());
        server.createContext("/agent-token", new AgentTokenHandler());
        server.setExecutor(null);
        server.start();
        
        log.info("");
        log.info("╔════════════════════════════════════════════════════════════╗");
        log.info("║  🌐 WEB SERVER STARTED                                     ║");
        log.info("║                                                            ║");
        log.info("║  Open in browser: http://localhost:{}                    ║", HTTP_PORT);
        log.info("║                                                            ║");
        log.info("║  This will let you talk to the voice agent via LiveKit!    ║");
        log.info("╚════════════════════════════════════════════════════════════╝");
        log.info("");
        
        // Wait for shutdown
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            server.stop(0);
            shutdownLatch.countDown();
        }));
        
        shutdownLatch.await();
    }
    
    /**
     * Generates a LiveKit access token for a participant
     */
    private static String generateToken(String roomName, String participantName, boolean canPublish) {
        AccessToken token = new AccessToken(config.liveKitApiKey(), config.liveKitApiSecret());
        token.setName(participantName);
        token.setIdentity(participantName);
        token.setTtl(3600); // 1 hour
        
        token.addGrants(
            new RoomJoin(true),
            new RoomName(roomName)
        );
        
        return token.toJwt();
    }
    
    /**
     * Serves the web page for users to join the voice chat
     */
    static class WebPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Voice Agent - LiveKit</title>
                    <script src="https://cdn.jsdelivr.net/npm/livekit-client@2/dist/livekit-client.umd.js"></script>
                    <style>
                        * { box-sizing: border-box; margin: 0; padding: 0; }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                            min-height: 100vh;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            color: white;
                        }
                        .container {
                            text-align: center;
                            padding: 40px;
                            background: rgba(255,255,255,0.1);
                            border-radius: 20px;
                            backdrop-filter: blur(10px);
                            max-width: 500px;
                            width: 90%;
                        }
                        h1 { margin-bottom: 10px; font-size: 2em; }
                        .subtitle { color: #888; margin-bottom: 30px; }
                        .status {
                            padding: 15px;
                            border-radius: 10px;
                            margin: 20px 0;
                            font-weight: bold;
                        }
                        .status.disconnected { background: #e74c3c33; color: #e74c3c; }
                        .status.connecting { background: #f39c1233; color: #f39c12; }
                        .status.connected { background: #27ae6033; color: #27ae60; }
                        .status.speaking { background: #3498db33; color: #3498db; }
                        button {
                            background: #3498db;
                            color: white;
                            border: none;
                            padding: 15px 40px;
                            font-size: 18px;
                            border-radius: 30px;
                            cursor: pointer;
                            transition: all 0.3s;
                            margin: 10px;
                        }
                        button:hover { background: #2980b9; transform: scale(1.05); }
                        button:disabled { background: #555; cursor: not-allowed; transform: none; }
                        button.danger { background: #e74c3c; }
                        button.danger:hover { background: #c0392b; }
                        .mic-indicator {
                            width: 100px;
                            height: 100px;
                            border-radius: 50%;
                            background: #333;
                            margin: 30px auto;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            font-size: 40px;
                            transition: all 0.3s;
                        }
                        .mic-indicator.active {
                            background: #27ae60;
                            box-shadow: 0 0 30px #27ae6088;
                            animation: pulse 1s infinite;
                        }
                        @keyframes pulse {
                            0%, 100% { transform: scale(1); }
                            50% { transform: scale(1.1); }
                        }
                        .transcript {
                            margin-top: 20px;
                            padding: 15px;
                            background: rgba(0,0,0,0.3);
                            border-radius: 10px;
                            min-height: 60px;
                            text-align: left;
                        }
                        .transcript-label { color: #888; font-size: 12px; margin-bottom: 5px; }
                        #participantName {
                            padding: 10px 15px;
                            border-radius: 10px;
                            border: 1px solid #555;
                            background: rgba(255,255,255,0.1);
                            color: white;
                            font-size: 16px;
                            margin-bottom: 20px;
                            width: 200px;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>🎤 Voice Agent</h1>
                        <p class="subtitle">Talk to an AI assistant in real-time</p>
                        
                        <input type="text" id="participantName" placeholder="Your name" value="User">
                        
                        <div class="status disconnected" id="status">Disconnected</div>
                        
                        <div class="mic-indicator" id="micIndicator">🎤</div>
                        
                        <div>
                            <button id="connectBtn" onclick="connect()">Connect</button>
                            <button id="disconnectBtn" onclick="disconnect()" disabled class="danger">Disconnect</button>
                        </div>
                        
                        <div class="transcript">
                            <div class="transcript-label">Conversation:</div>
                            <div id="transcript">Click Connect to start talking...</div>
                        </div>
                    </div>
                    
                    <script>
                        const LIVEKIT_URL = '%s';
                        const ROOM_NAME = '%s';
                        
                        let room = null;
                        let localTrack = null;
                        
                        async function connect() {
                            const name = document.getElementById('participantName').value || 'User';
                            updateStatus('connecting', 'Connecting...');
                            
                            try {
                                // Get token from server
                                const response = await fetch(`/token?name=${encodeURIComponent(name)}&room=${ROOM_NAME}`);
                                const { token } = await response.json();
                                
                                // Create LiveKit room
                                room = new LivekitClient.Room({
                                    adaptiveStream: true,
                                    dynacast: true,
                                });
                                
                                // Set up event listeners
                                room.on(LivekitClient.RoomEvent.Connected, () => {
                                    updateStatus('connected', 'Connected - Start talking!');
                                    addTranscript('System', 'Connected to voice agent room');
                                });
                                
                                room.on(LivekitClient.RoomEvent.Disconnected, () => {
                                    updateStatus('disconnected', 'Disconnected');
                                    document.getElementById('micIndicator').classList.remove('active');
                                });
                                
                                room.on(LivekitClient.RoomEvent.ParticipantConnected, (participant) => {
                                    addTranscript('System', `${participant.identity} joined`);
                                });
                                
                                room.on(LivekitClient.RoomEvent.ParticipantDisconnected, (participant) => {
                                    addTranscript('System', `${participant.identity} left`);
                                });
                                
                                room.on(LivekitClient.RoomEvent.TrackSubscribed, (track, publication, participant) => {
                                    if (track.kind === 'audio') {
                                        const audio = track.attach();
                                        document.body.appendChild(audio);
                                        addTranscript('Agent', '🔊 Speaking...');
                                    }
                                });
                                
                                room.on(LivekitClient.RoomEvent.ActiveSpeakersChanged, (speakers) => {
                                    const micIndicator = document.getElementById('micIndicator');
                                    const isSpeaking = speakers.some(s => s.identity === name);
                                    micIndicator.classList.toggle('active', isSpeaking);
                                });
                                
                                // Connect to room
                                await room.connect(LIVEKIT_URL, token);
                                
                                // Publish microphone
                                await room.localParticipant.setMicrophoneEnabled(true);
                                
                                document.getElementById('connectBtn').disabled = true;
                                document.getElementById('disconnectBtn').disabled = false;
                                
                            } catch (error) {
                                console.error('Connection error:', error);
                                updateStatus('disconnected', 'Connection failed: ' + error.message);
                            }
                        }
                        
                        async function disconnect() {
                            if (room) {
                                await room.disconnect();
                                room = null;
                            }
                            document.getElementById('connectBtn').disabled = false;
                            document.getElementById('disconnectBtn').disabled = true;
                            updateStatus('disconnected', 'Disconnected');
                        }
                        
                        function updateStatus(state, text) {
                            const status = document.getElementById('status');
                            status.className = 'status ' + state;
                            status.textContent = text;
                        }
                        
                        function addTranscript(speaker, text) {
                            const transcript = document.getElementById('transcript');
                            const time = new Date().toLocaleTimeString();
                            transcript.innerHTML += `<div><strong>${speaker}</strong> (${time}): ${text}</div>`;
                            transcript.scrollTop = transcript.scrollHeight;
                        }
                    </script>
                </body>
                </html>
                """.formatted(config.liveKitServerUrl(), config.defaultRoom());
            
            byte[] response = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }
    
    /**
     * Generates tokens for web users
     */
    static class TokenHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String name = "User";
            String room = config.defaultRoom();
            
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2) {
                        if ("name".equals(pair[0])) name = java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        if ("room".equals(pair[0])) room = java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            }
            
            String token = generateToken(room, name, true);
            String json = "{\"token\":\"" + token + "\"}";
            
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
            
            log.info("Generated token for user: {} in room: {}", name, room);
        }
    }
    
    /**
     * Generates token for the agent
     */
    static class AgentTokenHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String token = generateToken(config.defaultRoom(), "VoiceAgent", true);
            String json = "{\"token\":\"" + token + "\",\"url\":\"" + config.liveKitServerUrl() + "\",\"room\":\"" + config.defaultRoom() + "\"}";
            
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }
    
    private static void printBanner() {
        System.out.println("""
                
                ╔═══════════════════════════════════════════════════════════╗
                ║                                                           ║
                ║          🌐 LIVEKIT VOICE AGENT SERVER 🌐                 ║
                ║                                                           ║
                ║     Real-time voice conversations via WebRTC              ║
                ║                                                           ║
                ╚═══════════════════════════════════════════════════════════╝
                
                """);
    }
}
