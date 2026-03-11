package org.example.vad;

import org.example.core.model.AudioFrame;

/**
 * Interface for Voice Activity Detection (VAD).
 * Essential for detecting when the user is speaking, especially for barge-in handling.
 */
public interface VoiceActivityDetector {

    /**
     * Process an audio frame and detect voice activity
     *
     * @param frame The audio frame to analyze
     * @return VAD result with speech detection status
     */
    VADResult process(AudioFrame frame);

    /**
     * Reset the VAD state
     */
    void reset();

    /**
     * Get the current speaking state
     */
    boolean isSpeaking();

    /**
     * Get the current energy level (for debugging/visualization)
     */
    float getCurrentEnergy();

    /**
     * Result of VAD processing
     */
    record VADResult(
            boolean isSpeaking,
            boolean speechStarted,
            boolean speechEnded,
            float energy,
            float threshold,
            long speechDurationMs
    ) {
        public static VADResult silence(float energy, float threshold) {
            return new VADResult(false, false, false, energy, threshold, 0);
        }

        public static VADResult speaking(float energy, float threshold, long durationMs) {
            return new VADResult(true, false, false, energy, threshold, durationMs);
        }

        public static VADResult speechStart(float energy, float threshold) {
            return new VADResult(true, true, false, energy, threshold, 0);
        }

        public static VADResult speechEnd(float energy, float threshold, long durationMs) {
            return new VADResult(false, false, true, energy, threshold, durationMs);
        }
    }
}
