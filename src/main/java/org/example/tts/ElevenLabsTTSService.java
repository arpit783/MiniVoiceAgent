package org.example.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.BufferedSource;
import org.example.core.model.AudioFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ElevenLabs Text-to-Speech service implementation.
 * Supports streaming audio synthesis for ultra-low latency voice output.
 */
public class ElevenLabsTTSService implements TextToSpeechService {
    private static final Logger log = LoggerFactory.getLogger(ElevenLabsTTSService.class);

    private static final String ELEVENLABS_API_URL = "https://api.elevenlabs.io/v1";
    private static final MediaType JSON = MediaType.parse("application/json");

    private final String apiKey;
    private final ElevenLabsConfig config;
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public ElevenLabsTTSService(String apiKey) {
        this(apiKey, ElevenLabsConfig.defaults());
    }

    public ElevenLabsTTSService(String apiKey, ElevenLabsConfig config) {
        this.apiKey = apiKey;
        this.config = config;
        this.objectMapper = new ObjectMapper();

        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public Flux<AudioFrame> synthesize(String text) {
        return Flux.create(sink -> {
            String url = buildStreamingUrl();

            try {
                Map<String, Object> requestBody = Map.of(
                        "text", text,
                        "model_id", config.modelId(),
                        "voice_settings", Map.of(
                                "stability", config.stability(),
                                "similarity_boost", config.similarityBoost(),
                                "style", config.style(),
                                "use_speaker_boost", config.useSpeakerBoost()
                        )
                );

                String jsonBody = objectMapper.writeValueAsString(requestBody);

                Request request = new Request.Builder()
                        .url(url)
                        .header("xi-api-key", apiKey)
                        .header("Accept", "audio/mpeg")
                        .post(RequestBody.create(jsonBody, JSON))
                        .build();

                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        log.error("ElevenLabs TTS request failed", e);
                        sink.error(e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        if (!response.isSuccessful()) {
                            String errorBody = response.body() != null ? response.body().string() : "No body";
                            log.error("ElevenLabs TTS error {}: {}", response.code(), errorBody);
                            sink.error(new IOException("TTS request failed: " + response.code()));
                            return;
                        }

                        try (ResponseBody body = response.body()) {
                            if (body == null) {
                                sink.error(new IOException("Empty response body"));
                                return;
                            }

                            BufferedSource source = body.source();
                            byte[] buffer = new byte[config.chunkSize()];
                            int bytesRead;
                            boolean isFirst = true;

                            while ((bytesRead = source.read(buffer)) != -1) {
                                if (sink.isCancelled()) {
                                    break;
                                }

                                byte[] chunk = new byte[bytesRead];
                                System.arraycopy(buffer, 0, chunk, 0, bytesRead);

                                AudioFrame frame = new AudioFrame(
                                        chunk,
                                        config.outputSampleRate(),
                                        1,
                                        16,
                                        Instant.now(),
                                        "tts-output"
                                );

                                sink.next(frame);
                                isFirst = false;
                            }

                            sink.complete();
                            log.debug("TTS synthesis complete for text: {}...",
                                    text.substring(0, Math.min(50, text.length())));
                        }
                    }
                });
            } catch (Exception e) {
                log.error("Failed to create TTS request", e);
                sink.error(e);
            }
        });
    }

    @Override
    public byte[] synthesizeBlocking(String text) {
        String url = buildNonStreamingUrl();

        try {
            Map<String, Object> requestBody = Map.of(
                    "text", text,
                    "model_id", config.modelId(),
                    "voice_settings", Map.of(
                            "stability", config.stability(),
                            "similarity_boost", config.similarityBoost(),
                            "style", config.style(),
                            "use_speaker_boost", config.useSpeakerBoost()
                    )
            );

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(url)
                    .header("xi-api-key", apiKey)
                    .header("Accept", "audio/mpeg")
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("TTS request failed: " + response.code());
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("Empty response body");
                }

                return body.bytes();
            }
        } catch (Exception e) {
            log.error("Blocking TTS synthesis failed", e);
            throw new RuntimeException("TTS synthesis failed", e);
        }
    }

    @Override
    public StreamingSession startStreamingSession() {
        return new ElevenLabsStreamingSession();
    }

    @Override
    public boolean isAvailable() {
        try {
            Request request = new Request.Builder()
                    .url(ELEVENLABS_API_URL + "/user")
                    .header("xi-api-key", apiKey)
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.warn("ElevenLabs availability check failed", e);
            return false;
        }
    }

    @Override
    public String getVoiceId() {
        return config.voiceId();
    }

    private String buildStreamingUrl() {
        return ELEVENLABS_API_URL + "/text-to-speech/" + config.voiceId() + "/stream" +
                "?output_format=" + config.outputFormat() +
                "&optimize_streaming_latency=" + config.latencyOptimization();
    }

    private String buildNonStreamingUrl() {
        return ELEVENLABS_API_URL + "/text-to-speech/" + config.voiceId() +
                "?output_format=" + config.outputFormat();
    }

    /**
     * Streaming session for sentence-by-sentence synthesis
     */
    private class ElevenLabsStreamingSession implements StreamingSession {
        private final Sinks.Many<AudioFrame> audioSink;
        private final StringBuilder textBuffer;
        private final AtomicBoolean active;
        private final AtomicBoolean completed;

        public ElevenLabsStreamingSession() {
            this.audioSink = Sinks.many().multicast().onBackpressureBuffer(64);
            this.textBuffer = new StringBuilder();
            this.active = new AtomicBoolean(true);
            this.completed = new AtomicBoolean(false);
        }

        @Override
        public void addText(String textChunk) {
            if (!active.get() || completed.get()) {
                return;
            }

            textBuffer.append(textChunk);

            String currentText = textBuffer.toString();
            int sentenceEnd = findSentenceEnd(currentText);

            if (sentenceEnd > 0) {
                String sentence = currentText.substring(0, sentenceEnd).trim();
                textBuffer.delete(0, sentenceEnd);

                if (!sentence.isEmpty()) {
                    synthesizeSentence(sentence);
                }
            }
        }

        @Override
        public void complete() {
            if (completed.compareAndSet(false, true)) {
                String remaining = textBuffer.toString().trim();
                if (!remaining.isEmpty()) {
                    synthesizeSentence(remaining);
                }
                audioSink.tryEmitComplete();
            }
        }

        @Override
        public void cancel() {
            active.set(false);
            audioSink.tryEmitComplete();
            log.debug("TTS streaming session cancelled");
        }

        @Override
        public Flux<AudioFrame> audioStream() {
            return audioSink.asFlux();
        }

        @Override
        public boolean isActive() {
            return active.get() && !completed.get();
        }

        private void synthesizeSentence(String sentence) {
            synthesize(sentence)
                    .subscribe(
                            frame -> audioSink.tryEmitNext(frame),
                            error -> log.error("Sentence synthesis failed", error)
                    );
        }

        private int findSentenceEnd(String text) {
            int period = text.indexOf(". ");
            int question = text.indexOf("? ");
            int exclaim = text.indexOf("! ");

            int min = Integer.MAX_VALUE;
            if (period > 0) min = Math.min(min, period + 1);
            if (question > 0) min = Math.min(min, question + 1);
            if (exclaim > 0) min = Math.min(min, exclaim + 1);

            return min == Integer.MAX_VALUE ? -1 : min;
        }
    }

    /**
     * Configuration for ElevenLabs TTS
     */
    public record ElevenLabsConfig(
            String voiceId,
            String modelId,
            String outputFormat,
            int outputSampleRate,
            double stability,
            double similarityBoost,
            double style,
            boolean useSpeakerBoost,
            int latencyOptimization,
            int chunkSize
    ) {
        public static ElevenLabsConfig defaults() {
            return new ElevenLabsConfig(
                    "21m00Tcm4TlvDq8ikWAM",  // Rachel voice
                    "eleven_turbo_v2_5",
                    "mp3_44100_128",  // MP3 format - easier to play on macOS
                    44100,
                    0.5,
                    0.75,
                    0.0,
                    true,
                    3,  // Max streaming latency optimization
                    4096
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String voiceId = "21m00Tcm4TlvDq8ikWAM";
            private String modelId = "eleven_turbo_v2_5";
            private String outputFormat = "mp3_44100_128";
            private int outputSampleRate = 44100;
            private double stability = 0.5;
            private double similarityBoost = 0.75;
            private double style = 0.0;
            private boolean useSpeakerBoost = true;
            private int latencyOptimization = 3;
            private int chunkSize = 4096;

            public Builder voiceId(String voiceId) {
                this.voiceId = voiceId;
                return this;
            }

            public Builder modelId(String modelId) {
                this.modelId = modelId;
                return this;
            }

            public Builder outputFormat(String outputFormat) {
                this.outputFormat = outputFormat;
                return this;
            }

            public Builder outputSampleRate(int sampleRate) {
                this.outputSampleRate = sampleRate;
                return this;
            }

            public Builder stability(double stability) {
                this.stability = stability;
                return this;
            }

            public Builder similarityBoost(double similarityBoost) {
                this.similarityBoost = similarityBoost;
                return this;
            }

            public Builder style(double style) {
                this.style = style;
                return this;
            }

            public Builder useSpeakerBoost(boolean use) {
                this.useSpeakerBoost = use;
                return this;
            }

            public Builder latencyOptimization(int level) {
                this.latencyOptimization = level;
                return this;
            }

            public Builder chunkSize(int size) {
                this.chunkSize = size;
                return this;
            }

            public ElevenLabsConfig build() {
                return new ElevenLabsConfig(
                        voiceId, modelId, outputFormat, outputSampleRate,
                        stability, similarityBoost, style, useSpeakerBoost,
                        latencyOptimization, chunkSize
                );
            }
        }
    }
}
