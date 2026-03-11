package org.example.rtc;

import io.livekit.server.AccessToken;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import org.example.core.model.AudioFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LiveKit RTC participant implementation.
 * Handles WebRTC connection and audio streaming via LiveKit.
 * 
 * Note: This is a simplified implementation. Production use would require
 * the LiveKit Kotlin/Java SDK with full WebRTC support for actual RTC connections.
 * This implementation focuses on the integration pattern and token generation.
 */
public class LiveKitParticipant implements RTCParticipant {
    private static final Logger log = LoggerFactory.getLogger(LiveKitParticipant.class);

    private final String apiKey;
    private final String apiSecret;
    private final String serverUrl;
    private final LiveKitConfig config;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicReference<RoomInfo> roomInfo = new AtomicReference<>();
    private final Sinks.Many<AudioFrame> incomingAudioSink;
    private RTCEventListener eventListener;

    public LiveKitParticipant(String apiKey, String apiSecret, String serverUrl) {
        this(apiKey, apiSecret, serverUrl, LiveKitConfig.defaults());
    }

    public LiveKitParticipant(String apiKey, String apiSecret, String serverUrl, LiveKitConfig config) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.serverUrl = serverUrl;
        this.config = config;
        this.incomingAudioSink = Sinks.many().multicast().onBackpressureBuffer(512);
    }

    @Override
    public void connect(String roomName, String participantName) {
        if (connected.get()) {
            log.warn("Already connected to a room");
            return;
        }

        try {
            String token = generateAccessToken(roomName, participantName);
            log.info("Generated LiveKit access token for room: {}, participant: {}", roomName, participantName);

            // In production, you would use the LiveKit RTC SDK here to:
            // 1. Connect to the LiveKit server using the token
            // 2. Subscribe to audio tracks from other participants
            // 3. Publish your own audio track
            //
            // Example with LiveKit Kotlin SDK:
            // val room = LiveKit.connect(serverUrl, token, roomOptions)
            // room.localParticipant.publishAudioTrack(audioTrack)

            RoomInfo info = new RoomInfo(roomName, generateParticipantId(), participantName, 1);
            roomInfo.set(info);
            connected.set(true);

            log.info("Connected to LiveKit room: {}", roomName);

            if (eventListener != null) {
                eventListener.onConnected(info);
            }

        } catch (Exception e) {
            log.error("Failed to connect to LiveKit room", e);
            if (eventListener != null) {
                eventListener.onError(e);
            }
        }
    }

    @Override
    public void disconnect() {
        if (!connected.get()) {
            return;
        }

        connected.set(false);
        RoomInfo info = roomInfo.getAndSet(null);

        // In production: room.disconnect()

        log.info("Disconnected from LiveKit room");

        if (eventListener != null && info != null) {
            eventListener.onDisconnected("Client disconnect");
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public Flux<AudioFrame> incomingAudio() {
        return incomingAudioSink.asFlux();
    }

    @Override
    public void sendAudio(AudioFrame frame) {
        if (!connected.get()) {
            log.warn("Cannot send audio: not connected");
            return;
        }

        // In production, you would publish the audio frame to your audio track:
        // audioTrack.publishData(frame.data())

        log.trace("Sending audio frame: {} bytes", frame.data().length);
    }

    @Override
    public RoomInfo getRoomInfo() {
        return roomInfo.get();
    }

    @Override
    public void setEventListener(RTCEventListener listener) {
        this.eventListener = listener;
    }

    /**
     * Generate a LiveKit access token for joining a room.
     * This is the actual server-side token generation that would be used in production.
     */
    public String generateAccessToken(String roomName, String participantIdentity) {
        AccessToken token = new AccessToken(apiKey, apiSecret);

        token.setName(participantIdentity);
        token.setIdentity(participantIdentity);
        token.setTtl(config.tokenTtlSeconds());

        token.addGrants(
                new RoomJoin(true),
                new RoomName(roomName)
        );

        return token.toJwt();
    }

    /**
     * Simulate receiving audio from another participant.
     * In production, this would be called by the LiveKit SDK when audio is received.
     */
    public void simulateIncomingAudio(byte[] audioData, String trackId) {
        if (!connected.get()) {
            return;
        }

        AudioFrame frame = new AudioFrame(
                audioData,
                AudioFrame.DEFAULT_SAMPLE_RATE,
                AudioFrame.DEFAULT_CHANNELS,
                AudioFrame.DEFAULT_BITS_PER_SAMPLE,
                Instant.now(),
                trackId
        );

        incomingAudioSink.tryEmitNext(frame);
    }

    private String generateParticipantId() {
        return "agent-" + System.currentTimeMillis();
    }

    /**
     * Configuration for LiveKit participant
     */
    public record LiveKitConfig(
            int tokenTtlSeconds,
            boolean autoSubscribe,
            boolean publishAudio,
            boolean publishVideo,
            int audioSampleRate,
            int audioChannels
    ) {
        public static LiveKitConfig defaults() {
            return new LiveKitConfig(
                    3600,   // 1 hour token TTL
                    true,   // Auto-subscribe to tracks
                    true,   // Publish audio
                    false,  // Don't publish video
                    16000,  // 16kHz sample rate
                    1       // Mono
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int tokenTtlSeconds = 3600;
            private boolean autoSubscribe = true;
            private boolean publishAudio = true;
            private boolean publishVideo = false;
            private int audioSampleRate = 16000;
            private int audioChannels = 1;

            public Builder tokenTtlSeconds(int ttl) {
                this.tokenTtlSeconds = ttl;
                return this;
            }

            public Builder autoSubscribe(boolean subscribe) {
                this.autoSubscribe = subscribe;
                return this;
            }

            public Builder publishAudio(boolean publish) {
                this.publishAudio = publish;
                return this;
            }

            public Builder publishVideo(boolean publish) {
                this.publishVideo = publish;
                return this;
            }

            public Builder audioSampleRate(int rate) {
                this.audioSampleRate = rate;
                return this;
            }

            public Builder audioChannels(int channels) {
                this.audioChannels = channels;
                return this;
            }

            public LiveKitConfig build() {
                return new LiveKitConfig(
                        tokenTtlSeconds, autoSubscribe, publishAudio, publishVideo,
                        audioSampleRate, audioChannels
                );
            }
        }
    }
}
