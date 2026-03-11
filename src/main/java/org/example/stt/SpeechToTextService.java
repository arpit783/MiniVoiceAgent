package org.example.stt;

import org.example.core.model.AudioFrame;
import org.example.core.model.TranscriptSegment;
import reactor.core.publisher.Flux;

/**
 * Interface for Speech-to-Text services.
 * Implementations should support real-time streaming transcription.
 */
public interface SpeechToTextService {

    /**
     * Start the STT service and establish connection
     */
    void start();

    /**
     * Stop the STT service and close connection
     */
    void stop();

    /**
     * Send an audio frame to be transcribed
     * @param frame The audio frame to transcribe
     */
    void sendAudio(AudioFrame frame);

    /**
     * Get a stream of transcript segments (both partial and final)
     * @return Flux of transcript segments
     */
    Flux<TranscriptSegment> transcripts();

    /**
     * Check if the service is currently connected
     */
    boolean isConnected();

    /**
     * Signal end of speech (flush any remaining audio)
     */
    void endOfSpeech();
}
