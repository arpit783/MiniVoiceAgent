package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.example.agent.VoiceAgentOrchestrator;
import org.example.config.VoiceAgentConfig;
import org.example.core.event.EventBus;
import org.example.core.event.VoiceEvent;
import org.example.core.model.AudioFrame;
import org.example.llm.AgentBrain;
import org.example.llm.GeminiBrain;
import org.example.stt.DeepgramSTTService;
import org.example.tts.ElevenLabsTTSService;
import org.example.vad.EnergyBasedVAD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket-based Voice Agent Server
 * 
 * This provides a simpler approach for real-time voice interaction:
 * 1. Browser connects via WebSocket
 * 2. Browser captures microphone audio and streams it to this server
 * 3. Server processes audio through STT → LLM → TTS pipeline  
 * 4. Server streams synthesized audio back to browser
 * 
 * This avoids the complexity of LiveKit while still enabling real-time voice chat.
 */
public class WebSocketVoiceServer {
    private static final Logger log = LoggerFactory.getLogger(WebSocketVoiceServer.class);
    
    private static final int HTTP_PORT = 3000;
    private static final int WS_PORT = 8765;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private static VoiceAgentConfig config;
    private static EventBus eventBus;
    private static DeepgramSTTService sttService;
    private static ElevenLabsTTSService ttsService;
    private static AgentBrain brain;
    private static VoiceAgentOrchestrator orchestrator;
    
    private static final ConcurrentHashMap<String, WebSocketConnection> connections = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        printBanner();
        
        // Load configuration
        config = VoiceAgentConfig.fromEnvironment();
        config.validate();
        
        log.info("Configuration loaded:");
        log.info(config.toString());
        
        // Initialize services
        initializeServices();
        
        // Start HTTP server for web UI
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/", new WebPageHandler());
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();
        
        // Start WebSocket server
        ExecutorService wsExecutor = Executors.newCachedThreadPool();
        ServerSocket wsServer = new ServerSocket(WS_PORT);
        
        log.info("");
        log.info("╔════════════════════════════════════════════════════════════╗");
        log.info("║  🎤 VOICE AGENT SERVER STARTED                             ║");
        log.info("║                                                            ║");
        log.info("║  Web UI:     http://localhost:{}                         ║", HTTP_PORT);
        log.info("║  WebSocket:  ws://localhost:{}                           ║", WS_PORT);
        log.info("║                                                            ║");
        log.info("║  Open the web UI in your browser to start talking!         ║");
        log.info("╚════════════════════════════════════════════════════════════╝");
        log.info("");
        
        // Accept WebSocket connections
        wsExecutor.submit(() -> {
            while (!wsServer.isClosed()) {
                try {
                    Socket client = wsServer.accept();
                    wsExecutor.submit(() -> handleWebSocketConnection(client));
                } catch (IOException e) {
                    if (!wsServer.isClosed()) {
                        log.error("Error accepting connection", e);
                    }
                }
            }
        });
        
        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            try {
                wsServer.close();
                httpServer.stop(0);
                wsExecutor.shutdownNow();
                if (sttService != null) sttService.stop();
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            }
        }));
        
        // Keep main thread alive
        Thread.currentThread().join();
    }
    
    private static void initializeServices() throws Exception {
        eventBus = new EventBus();
        
        // Initialize STT (but don't connect yet - will connect when client joins)
        sttService = new DeepgramSTTService(config.deepgramApiKey());
        // NOTE: Don't call sttService.start() here - it will be started when client connects
        
        // Initialize TTS with custom config for voice ID
        ElevenLabsTTSService.ElevenLabsConfig ttsConfig = ElevenLabsTTSService.ElevenLabsConfig.builder()
            .voiceId(config.ttsVoiceId())
            .build();
        ttsService = new ElevenLabsTTSService(config.elevenLabsApiKey(), ttsConfig);
        
        // Initialize LLM Brain (prefer Gemini for free tier)
        String geminiKey = config.geminiApiKey();
        String openAiKey = config.openAiApiKey();
        
        if (geminiKey != null && !geminiKey.isBlank()) {
            log.info("Using Gemini LLM (FREE tier)");
            brain = new GeminiBrain(geminiKey);
        } else if (openAiKey != null && !openAiKey.isBlank()) {
            log.info("Using OpenAI LLM");
            brain = new org.example.llm.LangChain4jBrain(openAiKey);
        } else {
            throw new IllegalStateException("No LLM API key configured!");
        }
        
        // Initialize VAD with default config (already optimized)
        EnergyBasedVAD vad = new EnergyBasedVAD();
        
        // Initialize orchestrator (but don't start yet)
        orchestrator = new VoiceAgentOrchestrator(sttService, ttsService, brain, vad, eventBus, config);
        
        log.info("All services initialized (waiting for client connection)");
    }
    
    private static void handleWebSocketConnection(Socket socket) {
        String connectionId = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.info("New WebSocket connection: {}", connectionId);
        
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            
            // Perform WebSocket handshake
            if (!performHandshake(in, out)) {
                socket.close();
                return;
            }
            
            WebSocketConnection conn = new WebSocketConnection(connectionId, socket, out);
            connections.put(connectionId, conn);
            
            // Subscribe to TTS audio events and send to this client
            var audioSubscription = eventBus.eventsOfType(VoiceEvent.TTSAudioChunk.class)
                .subscribe(event -> {
                    try {
                        conn.sendAudio(event.frame().data());
                    } catch (Exception e) {
                        log.error("Error sending audio to client", e);
                    }
                });
            
            var transcriptSubscription = eventBus.eventsOfType(VoiceEvent.TranscriptReceived.class)
                .subscribe(event -> {
                    try {
                        ObjectNode msg = objectMapper.createObjectNode();
                        msg.put("type", "transcript");
                        msg.put("text", event.segment().text());
                        msg.put("isFinal", event.segment().isFinal());
                        conn.sendText(objectMapper.writeValueAsString(msg));
                    } catch (Exception e) {
                        log.error("Error sending transcript", e);
                    }
                });
            
            var responseSubscription = eventBus.eventsOfType(VoiceEvent.LLMResponseChunk.class)
                .subscribe(event -> {
                    try {
                        ObjectNode msg = objectMapper.createObjectNode();
                        msg.put("type", "response");
                        msg.put("text", event.text());
                        conn.sendText(objectMapper.writeValueAsString(msg));
                    } catch (Exception e) {
                        log.error("Error sending response", e);
                    }
                });
            
            var stateSubscription = eventBus.eventsOfType(VoiceEvent.StateChanged.class)
                .subscribe(event -> {
                    try {
                        ObjectNode msg = objectMapper.createObjectNode();
                        msg.put("type", "state");
                        msg.put("state", event.newState().name());
                        conn.sendText(objectMapper.writeValueAsString(msg));
                    } catch (Exception e) {
                        log.error("Error sending state", e);
                    }
                });
            
            // Read WebSocket frames
            byte[] buffer = new byte[65536];
            while (!socket.isClosed() && conn.isActive()) {
                try {
                    int firstByte = in.read();
                    if (firstByte == -1) break;
                    
                    int secondByte = in.read();
                    if (secondByte == -1) break;
                    
                    boolean isFinal = (firstByte & 0x80) != 0;
                    int opcode = firstByte & 0x0F;
                    boolean isMasked = (secondByte & 0x80) != 0;
                    int payloadLength = secondByte & 0x7F;
                    
                    if (payloadLength == 126) {
                        payloadLength = (in.read() << 8) | in.read();
                    } else if (payloadLength == 127) {
                        payloadLength = 0;
                        for (int i = 0; i < 8; i++) {
                            payloadLength = (payloadLength << 8) | in.read();
                        }
                    }
                    
                    byte[] mask = new byte[4];
                    if (isMasked) {
                        in.read(mask);
                    }
                    
                    byte[] payload = new byte[payloadLength];
                    int read = 0;
                    while (read < payloadLength) {
                        int r = in.read(payload, read, payloadLength - read);
                        if (r == -1) break;
                        read += r;
                    }
                    
                    if (isMasked) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] ^= mask[i % 4];
                        }
                    }
                    
                    // Handle different opcodes
                    switch (opcode) {
                        case 0x01 -> { // Text frame
                            String text = new String(payload, StandardCharsets.UTF_8);
                            handleTextMessage(connectionId, text);
                        }
                        case 0x02 -> { // Binary frame (audio data)
                            handleBinaryMessage(connectionId, payload);
                        }
                        case 0x08 -> { // Close
                            log.info("Client {} requested close", connectionId);
                            conn.close();
                        }
                        case 0x09 -> { // Ping
                            conn.sendPong(payload);
                        }
                    }
                } catch (IOException e) {
                    if (conn.isActive()) {
                        log.debug("Connection {} closed: {}", connectionId, e.getMessage());
                    }
                    break;
                }
            }
            
            // Cleanup
            audioSubscription.dispose();
            transcriptSubscription.dispose();
            responseSubscription.dispose();
            stateSubscription.dispose();
            connections.remove(connectionId);
            
        } catch (Exception e) {
            log.error("Error handling WebSocket connection", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
            connections.remove(connectionId);
            log.info("Connection {} closed", connectionId);
        }
    }
    
    private static boolean performHandshake(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line;
        String wsKey = null;
        
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.startsWith("Sec-WebSocket-Key:")) {
                wsKey = line.substring(19).trim();
            }
        }
        
        if (wsKey == null) {
            return false;
        }
        
        // Generate accept key
        String acceptKey;
        try {
            String magic = wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            acceptKey = Base64.getEncoder().encodeToString(md.digest(magic.getBytes()));
        } catch (Exception e) {
            return false;
        }
        
        // Send handshake response
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                         "Upgrade: websocket\r\n" +
                         "Connection: Upgrade\r\n" +
                         "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
        out.write(response.getBytes());
        out.flush();
        
        return true;
    }
    
    private static void handleTextMessage(String connectionId, String text) {
        try {
            JsonNode json = objectMapper.readTree(text);
            String type = json.has("type") ? json.get("type").asText() : "";
            
            switch (type) {
                case "start" -> {
                    log.info("Client {} started session - connecting to STT service", connectionId);
                    // Start STT service first (connects to Deepgram)
                    sttService.start();
                    // Small delay to ensure connection is established
                    Thread.sleep(500);
                    // Then start orchestrator
                    orchestrator.start();
                    log.info("Session started for client {}", connectionId);
                }
                case "stop" -> {
                    log.info("Client {} stopped session", connectionId);
                    orchestrator.stop();
                    // Note: STT service will be stopped by orchestrator.stop()
                }
                case "interrupt" -> {
                    log.info("Client {} requested interrupt (barge-in)", connectionId);
                    orchestrator.handleBargeIn();
                }
                default -> log.debug("Unknown message type: {}", type);
            }
        } catch (Exception e) {
            log.error("Error parsing message", e);
        }
    }
    
    private static int audioPacketCount = 0;
    
    private static void handleBinaryMessage(String connectionId, byte[] audioData) {
        audioPacketCount++;
        
        // Log every 100th packet to avoid spam
        if (audioPacketCount % 100 == 1) {
            log.info("📦 Audio packet #{}: {} bytes from client {}", audioPacketCount, audioData.length, connectionId);
            
            // Log first few bytes for debugging
            if (audioData.length > 10) {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(20, audioData.length); i++) {
                    hex.append(String.format("%02X ", audioData[i]));
                }
                log.debug("First bytes: {}", hex);
            }
        }
        
        // Create audio frame and send to orchestrator
        AudioFrame frame = AudioFrame.of(audioData);
        orchestrator.handleIncomingAudio(frame);
    }
    
    static class WebPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Voice Agent</title>
                    <style>
                        * { box-sizing: border-box; margin: 0; padding: 0; }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            background: linear-gradient(135deg, #1a1a2e 0%%, #16213e 100%%);
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
                            max-width: 600px;
                            width: 90%%;
                        }
                        h1 { margin-bottom: 10px; font-size: 2em; }
                        .subtitle { color: #888; margin-bottom: 30px; }
                        .status {
                            padding: 15px;
                            border-radius: 10px;
                            margin: 20px 0;
                            font-weight: bold;
                            transition: all 0.3s;
                        }
                        .status.disconnected { background: #e74c3c33; color: #e74c3c; }
                        .status.connecting { background: #f39c1233; color: #f39c12; }
                        .status.idle { background: #27ae6033; color: #27ae60; }
                        .status.listening { background: #3498db33; color: #3498db; }
                        .status.thinking { background: #9b59b633; color: #9b59b6; }
                        .status.speaking { background: #e67e2233; color: #e67e22; }
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
                            width: 120px;
                            height: 120px;
                            border-radius: 50%%;
                            background: #333;
                            margin: 30px auto;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            font-size: 50px;
                            transition: all 0.3s;
                        }
                        .mic-indicator.listening {
                            background: #3498db;
                            box-shadow: 0 0 40px #3498db88;
                            animation: pulse 1s infinite;
                        }
                        .mic-indicator.speaking {
                            background: #e67e22;
                            box-shadow: 0 0 40px #e67e2288;
                        }
                        .mic-indicator.thinking {
                            background: #9b59b6;
                            box-shadow: 0 0 40px #9b59b688;
                            animation: spin 2s linear infinite;
                        }
                        @keyframes pulse {
                            0%%, 100%% { transform: scale(1); }
                            50%% { transform: scale(1.1); }
                        }
                        @keyframes spin {
                            from { transform: rotate(0deg); }
                            to { transform: rotate(360deg); }
                        }
                        .conversation {
                            margin-top: 20px;
                            padding: 15px;
                            background: rgba(0,0,0,0.3);
                            border-radius: 10px;
                            max-height: 300px;
                            overflow-y: auto;
                            text-align: left;
                        }
                        .message {
                            margin: 10px 0;
                            padding: 10px 15px;
                            border-radius: 10px;
                        }
                        .message.user { background: #3498db44; margin-left: 20%%; }
                        .message.agent { background: #27ae6044; margin-right: 20%%; }
                        .message.system { background: #55555544; font-size: 12px; text-align: center; }
                        .message-label { font-size: 11px; color: #888; margin-bottom: 5px; }
                        .live-text { 
                            font-style: italic; 
                            color: #888; 
                            min-height: 20px;
                            margin: 10px 0;
                        }
                        .volume-bar {
                            height: 8px;
                            background: #333;
                            border-radius: 4px;
                            margin: 10px 0;
                            overflow: hidden;
                        }
                        .volume-level {
                            height: 100%%;
                            background: linear-gradient(90deg, #27ae60, #f39c12, #e74c3c);
                            width: 0%%;
                            transition: width 0.1s;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>🎤 Voice Agent</h1>
                        <p class="subtitle">Real-time AI Voice Assistant</p>
                        
                        <div class="status disconnected" id="status">Click Connect to Start</div>
                        
                        <div class="mic-indicator" id="micIndicator">🎤</div>
                        
                        <div class="volume-bar">
                            <div class="volume-level" id="volumeLevel"></div>
                        </div>
                        
                        <div>
                            <button id="connectBtn" onclick="connect()">Connect</button>
                            <button id="disconnectBtn" onclick="disconnect()" disabled class="danger">Disconnect</button>
                        </div>
                        
                        <div class="live-text" id="liveText"></div>
                        
                        <div class="conversation" id="conversation">
                            <div class="message system">Conversation will appear here...</div>
                        </div>
                    </div>
                    
                    <script>
                        let ws = null;
                        let audioContext = null;
                        let mediaStream = null;
                        let processor = null;
                        let source = null;
                        let scriptProcessor = null;
                        let isConnected = false;
                        let audioQueue = [];
                        let isPlaying = false;
                        let currentTranscript = '';
                        let currentResponse = '';
                        let agentState = 'IDLE';
                        let micMuted = false;
                        
                        // Half-duplex control: mute mic while agent speaks to prevent echo
                        function setMicMuted(muted) {
                            micMuted = muted;
                            console.log('Microphone ' + (muted ? 'MUTED' : 'UNMUTED'));
                        }
                        
                        async function connect() {
                            updateStatus('connecting', 'Connecting...');
                            
                            try {
                                // Request microphone access with echo cancellation
                                mediaStream = await navigator.mediaDevices.getUserMedia({ 
                                    audio: {
                                        echoCancellation: true,
                                        noiseSuppression: true,
                                        autoGainControl: true
                                    } 
                                });
                                
                                console.log('Microphone access granted');
                                
                                // Create audio context (use default sample rate, we'll resample)
                                audioContext = new AudioContext();
                                console.log('AudioContext sample rate:', audioContext.sampleRate);
                                
                                source = audioContext.createMediaStreamSource(mediaStream);
                                
                                // Use ScriptProcessorNode (deprecated but more reliable)
                                // Buffer size of 4096 at 48kHz = ~85ms of audio
                                scriptProcessor = audioContext.createScriptProcessor(4096, 1, 1);
                                
                                scriptProcessor.onaudioprocess = (e) => {
                                    if (micMuted) return;
                                    
                                    const inputData = e.inputBuffer.getChannelData(0);
                                    
                                    // Resample from audioContext.sampleRate to 16000
                                    const inputSampleRate = audioContext.sampleRate;
                                    const outputSampleRate = 16000;
                                    const ratio = inputSampleRate / outputSampleRate;
                                    const outputLength = Math.floor(inputData.length / ratio);
                                    
                                    const int16 = new Int16Array(outputLength);
                                    for (let i = 0; i < outputLength; i++) {
                                        const srcIndex = Math.floor(i * ratio);
                                        const sample = inputData[srcIndex];
                                        int16[i] = Math.max(-32768, Math.min(32767, Math.floor(sample * 32768)));
                                    }
                                    
                                    if (ws && ws.readyState === WebSocket.OPEN) {
                                        ws.send(int16.buffer);
                                    }
                                    
                                    updateVolume(int16.buffer);
                                };
                                
                                // Connect: source -> scriptProcessor -> destination (required for it to work)
                                source.connect(scriptProcessor);
                                scriptProcessor.connect(audioContext.destination);
                                
                                // Connect WebSocket
                                ws = new WebSocket('ws://localhost:%d');
                                
                                ws.onopen = () => {
                                    isConnected = true;
                                    ws.send(JSON.stringify({ type: 'start' }));
                                    updateStatus('idle', 'Connected - Listening');
                                    document.getElementById('connectBtn').disabled = true;
                                    document.getElementById('disconnectBtn').disabled = false;
                                    addMessage('system', 'Connected to voice agent (half-duplex mode)');
                                };
                                
                                ws.onmessage = async (event) => {
                                    if (event.data instanceof Blob) {
                                        // Audio data from TTS
                                        const arrayBuffer = await event.data.arrayBuffer();
                                        queueAudio(arrayBuffer);
                                    } else {
                                        // JSON message
                                        const msg = JSON.parse(event.data);
                                        handleMessage(msg);
                                    }
                                };
                                
                                ws.onclose = () => {
                                    isConnected = false;
                                    updateStatus('disconnected', 'Disconnected');
                                    document.getElementById('connectBtn').disabled = false;
                                    document.getElementById('disconnectBtn').disabled = true;
                                };
                                
                                ws.onerror = (error) => {
                                    console.error('WebSocket error:', error);
                                    updateStatus('disconnected', 'Connection Error');
                                };
                                
                            } catch (error) {
                                console.error('Connection error:', error);
                                updateStatus('disconnected', 'Error: ' + error.message);
                            }
                        }
                        
                        function disconnect() {
                            if (ws) {
                                ws.send(JSON.stringify({ type: 'stop' }));
                                ws.close();
                            }
                            if (scriptProcessor) {
                                scriptProcessor.disconnect();
                                scriptProcessor = null;
                            }
                            if (source) {
                                source.disconnect();
                                source = null;
                            }
                            if (mediaStream) {
                                mediaStream.getTracks().forEach(track => track.stop());
                            }
                            if (audioContext) {
                                audioContext.close();
                            }
                            isConnected = false;
                            updateStatus('disconnected', 'Disconnected');
                            document.getElementById('connectBtn').disabled = false;
                            document.getElementById('disconnectBtn').disabled = true;
                        }
                        
                        function handleMessage(msg) {
                            switch (msg.type) {
                                case 'state':
                                    updateAgentState(msg.state);
                                    break;
                                case 'transcript':
                                    if (msg.isFinal) {
                                        if (currentTranscript) {
                                            addMessage('user', currentTranscript);
                                        }
                                        currentTranscript = msg.text;
                                    } else {
                                        currentTranscript = msg.text;
                                        document.getElementById('liveText').textContent = '🎤 ' + msg.text;
                                    }
                                    break;
                                case 'response':
                                    currentResponse += msg.text + ' ';
                                    document.getElementById('liveText').textContent = '🤖 ' + currentResponse;
                                    break;
                            }
                        }
                        
                        function updateAgentState(state) {
                            agentState = state;
                            const statusEl = document.getElementById('status');
                            const micEl = document.getElementById('micIndicator');
                            
                            micEl.className = 'mic-indicator ' + state.toLowerCase();
                            
                            // HALF-DUPLEX: Mute mic when agent is speaking or thinking
                            if (state === 'SPEAKING' || state === 'THINKING') {
                                setMicMuted(true);
                            } else {
                                setMicMuted(false);
                            }
                            
                            switch (state) {
                                case 'IDLE':
                                    updateStatus('idle', 'Ready - Start Speaking');
                                    break;
                                case 'LISTENING':
                                    updateStatus('listening', 'Listening...');
                                    break;
                                case 'THINKING':
                                    updateStatus('thinking', 'Thinking...');
                                    if (currentTranscript) {
                                        addMessage('user', currentTranscript);
                                        currentTranscript = '';
                                    }
                                    document.getElementById('liveText').textContent = '';
                                    break;
                                case 'SPEAKING':
                                    updateStatus('speaking', 'Speaking... (mic muted)');
                                    break;
                            }
                            
                            if (state === 'IDLE' && currentResponse) {
                                addMessage('agent', currentResponse.trim());
                                currentResponse = '';
                                document.getElementById('liveText').textContent = '';
                            }
                        }
                        
                        function updateStatus(state, text) {
                            const status = document.getElementById('status');
                            status.className = 'status ' + state;
                            status.textContent = text;
                        }
                        
                        function updateVolume(buffer) {
                            const int16 = new Int16Array(buffer);
                            let sum = 0;
                            for (let i = 0; i < int16.length; i++) {
                                sum += Math.abs(int16[i]);
                            }
                            const avg = sum / int16.length;
                            const percent = micMuted ? 0 : Math.min(100, (avg / 10000) * 100);
                            document.getElementById('volumeLevel').style.width = percent + '%%';
                        }
                        
                        function addMessage(type, text) {
                            const conv = document.getElementById('conversation');
                            const msg = document.createElement('div');
                            msg.className = 'message ' + type;
                            
                            if (type !== 'system') {
                                const label = document.createElement('div');
                                label.className = 'message-label';
                                label.textContent = type === 'user' ? 'You' : 'Agent';
                                msg.appendChild(label);
                            }
                            
                            const content = document.createElement('div');
                            content.textContent = text;
                            msg.appendChild(content);
                            
                            conv.appendChild(msg);
                            conv.scrollTop = conv.scrollHeight;
                        }
                        
                        async function queueAudio(arrayBuffer) {
                            audioQueue.push(arrayBuffer);
                            if (!isPlaying) {
                                playNextAudio();
                            }
                        }
                        
                        async function playNextAudio() {
                            if (audioQueue.length === 0) {
                                isPlaying = false;
                                return;
                            }
                            
                            isPlaying = true;
                            const buffer = audioQueue.shift();
                            
                            try {
                                // Create a new audio context for playback if needed
                                const playbackContext = new AudioContext();
                                const audioBuffer = await playbackContext.decodeAudioData(buffer);
                                const sourceNode = playbackContext.createBufferSource();
                                sourceNode.buffer = audioBuffer;
                                sourceNode.connect(playbackContext.destination);
                                sourceNode.onended = () => {
                                    playbackContext.close();
                                    playNextAudio();
                                };
                                sourceNode.start();
                            } catch (e) {
                                console.error('Audio playback error:', e);
                                playNextAudio();
                            }
                        }
                    </script>
                </body>
                </html>
                """.formatted(WS_PORT);
            
            byte[] response = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }
    }
    
    static class WebSocketConnection {
        private final String id;
        private final Socket socket;
        private final OutputStream out;
        private final AtomicBoolean active = new AtomicBoolean(true);
        
        WebSocketConnection(String id, Socket socket, OutputStream out) {
            this.id = id;
            this.socket = socket;
            this.out = out;
        }
        
        boolean isActive() {
            return active.get() && !socket.isClosed();
        }
        
        synchronized void sendText(String text) throws IOException {
            if (!isActive()) return;
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            sendFrame(0x01, data);
        }
        
        synchronized void sendAudio(byte[] audio) throws IOException {
            if (!isActive()) return;
            sendFrame(0x02, audio);
        }
        
        synchronized void sendPong(byte[] data) throws IOException {
            if (!isActive()) return;
            sendFrame(0x0A, data);
        }
        
        private void sendFrame(int opcode, byte[] data) throws IOException {
            ByteBuffer buffer;
            if (data.length < 126) {
                buffer = ByteBuffer.allocate(2 + data.length);
                buffer.put((byte) (0x80 | opcode));
                buffer.put((byte) data.length);
            } else if (data.length < 65536) {
                buffer = ByteBuffer.allocate(4 + data.length);
                buffer.put((byte) (0x80 | opcode));
                buffer.put((byte) 126);
                buffer.putShort((short) data.length);
            } else {
                buffer = ByteBuffer.allocate(10 + data.length);
                buffer.put((byte) (0x80 | opcode));
                buffer.put((byte) 127);
                buffer.putLong(data.length);
            }
            buffer.put(data);
            out.write(buffer.array());
            out.flush();
        }
        
        void close() {
            active.set(false);
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
    
    private static void printBanner() {
        System.out.println("""
                
                ╔═══════════════════════════════════════════════════════════╗
                ║                                                           ║
                ║           🎤 WEBSOCKET VOICE AGENT SERVER 🎤              ║
                ║                                                           ║
                ║     Real-time voice conversations via WebSocket           ║
                ║                                                           ║
                ╚═══════════════════════════════════════════════════════════╝
                
                """);
    }
}
