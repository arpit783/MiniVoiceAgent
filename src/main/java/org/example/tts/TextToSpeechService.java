package org.example.tts;

import org.example.core.model.AudioFrame;
import reactor.core.publisher.Flux;

/**
 * Interface for Text-to-Speech services.
 * Implementations should support streaming audio generation for low latency.
 */
public interface TextToSpeechService {

    /**
     * Convert text to speech with streaming audio output.
     * Audio chunks are emitted as they become available for ultra-low latency.
     *
     * @param text The text to convert to speech
     * @return Flux of audio frames (streaming)
     */
    Flux<AudioFrame> synthesize(String text);

    /**
     * Convert text to speech and return complete audio.
     * Use synthesize() for streaming - this is for complete utterances.
     *
     * @param text The text to convert
     * @return Complete audio as a byte array
     */
    byte[] synthesizeBlocking(String text);

    /**
     * Start streaming synthesis for incremental text.
     * Use for sentence-by-sentence synthesis from LLM streaming output.
     *
     * @return A streaming session for incremental synthesis
     */
    StreamingSession startStreamingSession();

    /**
     * Check if the service is available
     */
    boolean isAvailable();

    /**
     * Get the configured voice ID
     */
    String getVoiceId();

    /**
     * Session for incremental/streaming text synthesis
     */
    interface StreamingSession {
        /**
         * Add text chunk to be synthesized
         */
        void addText(String textChunk);

        /**
         * Signal no more text will be added
         */
        void complete();

        /**
         * Cancel the session and stop any pending synthesis
         */
        void cancel();

        /**
         * Get the stream of audio frames
         */
        Flux<AudioFrame> audioStream();

        /**
         * Check if the session is active
         */
        boolean isActive();
    }
}
