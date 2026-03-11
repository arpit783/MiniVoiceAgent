package org.example.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.ByteString;
import org.example.core.model.AudioFrame;
import org.example.core.model.TranscriptSegment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deepgram Speech-to-Text service implementation.
 * Uses WebSocket streaming for real-time transcription with low latency.
 */
public class DeepgramSTTService implements SpeechToTextService {
    private static final Logger log = LoggerFactory.getLogger(DeepgramSTTService.class);

    private static final String DEEPGRAM_WS_URL = "wss://api.deepgram.com/v1/listen";

    private final String apiKey;
    private final DeepgramConfig config;
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final Sinks.Many<TranscriptSegment> transcriptSink;
    private final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public DeepgramSTTService(String apiKey) {
        this(apiKey, DeepgramConfig.defaults());
    }

    public DeepgramSTTService(String apiKey, DeepgramConfig config) {
        this.apiKey = apiKey;
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.transcriptSink = Sinks.many().multicast().onBackpressureBuffer(256);

        this.client = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void start() {
        if (connected.get()) {
            log.warn("DeepgramSTTService is already connected");
            return;
        }

        String url = buildWebSocketUrl();
        log.info("Connecting to Deepgram: {}", url);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Token " + apiKey)
                .build();

        WebSocket ws = client.newWebSocket(request, new DeepgramWebSocketListener());
        webSocketRef.set(ws);

        // Wait for connection (up to 5 seconds)
        for (int i = 0; i < 50 && !connected.get(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!connected.get()) {
            log.error("Failed to connect to Deepgram within 5 seconds");
        }
    }

    @Override
    public void stop() {
        WebSocket ws = webSocketRef.getAndSet(null);
        if (ws != null) {
            ws.close(1000, "Client closing");
        }
        connected.set(false);
        log.info("DeepgramSTTService stopped");
    }

    private int audioFramesSent = 0;
    
    @Override
    public void sendAudio(AudioFrame frame) {
        // Auto-reconnect if disconnected
        if (!connected.get()) {
            ensureConnected();
        }
        
        WebSocket ws = webSocketRef.get();
        if (ws != null && connected.get()) {
            audioFramesSent++;
            if (audioFramesSent % 100 == 1) {
                log.info("🎵 Sending audio frame #{} to Deepgram: {} bytes", audioFramesSent, frame.data().length);
            }
            ws.send(ByteString.of(frame.data()));
        } else {
            log.warn("Cannot send audio: WebSocket not connected");
        }
    }

    @Override
    public Flux<TranscriptSegment> transcripts() {
        return transcriptSink.asFlux();
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void endOfSpeech() {
        // Don't close the stream - keep connection alive for continuous listening
        // The VAD will handle speech segmentation
        log.debug("End of speech detected (keeping connection alive)");
    }

    /**
     * Reconnect to Deepgram if disconnected
     */
    private void ensureConnected() {
        if (!connected.get()) {
            log.info("Reconnecting to Deepgram...");
            start();
        }
    }

    private String buildWebSocketUrl() {
        StringBuilder sb = new StringBuilder(DEEPGRAM_WS_URL);
        sb.append("?encoding=").append(config.encoding());
        sb.append("&sample_rate=").append(config.sampleRate());
        sb.append("&channels=").append(config.channels());
        sb.append("&model=").append(config.model());
        sb.append("&language=").append(config.language());
        sb.append("&punctuate=").append(config.punctuate());
        sb.append("&interim_results=").append(config.interimResults());
        sb.append("&endpointing=").append(config.endpointing());

        if (config.smartFormat()) {
            sb.append("&smart_format=true");
        }

        return sb.toString();
    }

    private class DeepgramWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            connected.set(true);
            log.info("Connected to Deepgram STT service");
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            try {
                JsonNode root = objectMapper.readTree(text);
                String type = root.path("type").asText();

                if ("Results".equals(type)) {
                    processTranscriptResult(root);
                } else if ("Metadata".equals(type)) {
                    log.debug("Received metadata: {}", text);
                } else if ("Error".equals(type)) {
                    String errorMsg = root.path("description").asText("Unknown error");
                    log.error("Deepgram error: {}", errorMsg);
                }
            } catch (Exception e) {
                log.error("Error parsing Deepgram response: {}", text, e);
            }
        }

        private void processTranscriptResult(JsonNode root) {
            JsonNode channel = root.path("channel");
            JsonNode alternatives = channel.path("alternatives");

            log.debug("Processing transcript result: channel={}, alternatives count={}",
                    channel.isMissingNode() ? "missing" : "present",
                    alternatives.isArray() ? alternatives.size() : 0);

            if (alternatives.isArray() && !alternatives.isEmpty()) {
                JsonNode firstAlt = alternatives.get(0);
                String transcript = firstAlt.path("transcript").asText("");

                boolean isFinal = root.path("is_final").asBoolean(false);
                float confidence = (float) firstAlt.path("confidence").asDouble(0.0);

                log.info("📝 Transcript [{}] (confidence: {}): '{}'", 
                        isFinal ? "FINAL" : "partial", String.format("%.2f", confidence), transcript);

                if (!transcript.isBlank()) {
                    float startTime = root.path("start").floatValue();
                    float duration = root.path("duration").floatValue();

                    TranscriptSegment segment = new TranscriptSegment(
                            transcript,
                            isFinal,
                            confidence,
                            Instant.now(),
                            (long) (startTime * 1000),
                            (long) ((startTime + duration) * 1000),
                            "user"
                    );

                    Sinks.EmitResult result = transcriptSink.tryEmitNext(segment);
                    if (result.isFailure()) {
                        log.warn("Failed to emit transcript: {}", result);
                    }
                }
            }
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
            connected.set(false);
            String responseInfo = "";
            if (response != null) {
                responseInfo = " - HTTP " + response.code() + ": " + response.message();
            }
            log.error("Deepgram WebSocket failure{}: {}", responseInfo, t.getMessage());
            log.debug("Full error:", t);
        }

        @Override
        public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            log.info("Deepgram WebSocket closing: {} - {}", code, reason);
            webSocket.close(1000, null);
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            connected.set(false);
            log.info("Deepgram WebSocket closed: {} - {}", code, reason);
        }
    }

    /**
     * Configuration for Deepgram STT
     */
    public record DeepgramConfig(
            String encoding,
            int sampleRate,
            int channels,
            String model,
            String language,
            boolean punctuate,
            boolean interimResults,
            int endpointing,
            boolean smartFormat
    ) {
        public static DeepgramConfig defaults() {
            return new DeepgramConfig(
                    "linear16",
                    16000,
                    1,
                    "nova-2",
                    "en-US",
                    true,
                    true,
                    300,
                    true
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String encoding = "linear16";
            private int sampleRate = 16000;
            private int channels = 1;
            private String model = "nova-2";
            private String language = "en-US";
            private boolean punctuate = true;
            private boolean interimResults = true;
            private int endpointing = 300;
            private boolean smartFormat = true;

            public Builder encoding(String encoding) {
                this.encoding = encoding;
                return this;
            }

            public Builder sampleRate(int sampleRate) {
                this.sampleRate = sampleRate;
                return this;
            }

            public Builder channels(int channels) {
                this.channels = channels;
                return this;
            }

            public Builder model(String model) {
                this.model = model;
                return this;
            }

            public Builder language(String language) {
                this.language = language;
                return this;
            }

            public Builder punctuate(boolean punctuate) {
                this.punctuate = punctuate;
                return this;
            }

            public Builder interimResults(boolean interimResults) {
                this.interimResults = interimResults;
                return this;
            }

            public Builder endpointing(int endpointingMs) {
                this.endpointing = endpointingMs;
                return this;
            }

            public Builder smartFormat(boolean smartFormat) {
                this.smartFormat = smartFormat;
                return this;
            }

            public DeepgramConfig build() {
                return new DeepgramConfig(
                        encoding, sampleRate, channels, model, language,
                        punctuate, interimResults, endpointing, smartFormat
                );
            }
        }
    }
}
