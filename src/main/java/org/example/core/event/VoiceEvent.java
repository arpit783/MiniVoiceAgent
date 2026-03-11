package org.example.core.event;

import org.example.core.model.AgentState;
import org.example.core.model.AudioFrame;
import org.example.core.model.TranscriptSegment;

import java.time.Instant;

/**
 * Sealed interface for all voice pipeline events.
 * Enables type-safe event handling across the reactive pipeline.
 */
public sealed interface VoiceEvent permits
        VoiceEvent.AudioReceived,
        VoiceEvent.TranscriptReceived,
        VoiceEvent.LLMResponseChunk,
        VoiceEvent.LLMResponseComplete,
        VoiceEvent.TTSAudioChunk,
        VoiceEvent.TTSComplete,
        VoiceEvent.StateChanged,
        VoiceEvent.VoiceActivityDetected,
        VoiceEvent.BargeInDetected,
        VoiceEvent.ErrorOccurred {

    Instant timestamp();

    /**
     * Raw audio received from the user via RTC
     */
    record AudioReceived(AudioFrame frame, Instant timestamp) implements VoiceEvent {
        public AudioReceived(AudioFrame frame) {
            this(frame, Instant.now());
        }
    }

    /**
     * Transcript segment from STT (partial or final)
     */
    record TranscriptReceived(TranscriptSegment segment, Instant timestamp) implements VoiceEvent {
        public TranscriptReceived(TranscriptSegment segment) {
            this(segment, Instant.now());
        }
    }

    /**
     * Streaming chunk from LLM response
     */
    record LLMResponseChunk(String text, boolean isFirst, Instant timestamp) implements VoiceEvent {
        public LLMResponseChunk(String text, boolean isFirst) {
            this(text, isFirst, Instant.now());
        }
    }

    /**
     * LLM has completed generating the response
     */
    record LLMResponseComplete(String fullResponse, Instant timestamp) implements VoiceEvent {
        public LLMResponseComplete(String fullResponse) {
            this(fullResponse, Instant.now());
        }
    }

    /**
     * Audio chunk from TTS ready to be sent to user
     */
    record TTSAudioChunk(AudioFrame frame, boolean isFirst, Instant timestamp) implements VoiceEvent {
        public TTSAudioChunk(AudioFrame frame, boolean isFirst) {
            this(frame, isFirst, Instant.now());
        }
    }

    /**
     * TTS has finished generating audio for the response
     */
    record TTSComplete(long totalDurationMs, Instant timestamp) implements VoiceEvent {
        public TTSComplete(long totalDurationMs) {
            this(totalDurationMs, Instant.now());
        }
    }

    /**
     * Agent state has changed
     */
    record StateChanged(AgentState previousState, AgentState newState, Instant timestamp) implements VoiceEvent {
        public StateChanged(AgentState previousState, AgentState newState) {
            this(previousState, newState, Instant.now());
        }
    }

    /**
     * Voice activity detected (user started/stopped speaking)
     */
    record VoiceActivityDetected(boolean isSpeaking, float energy, Instant timestamp) implements VoiceEvent {
        public VoiceActivityDetected(boolean isSpeaking, float energy) {
            this(isSpeaking, energy, Instant.now());
        }
    }

    /**
     * User interrupted the agent (barge-in)
     */
    record BargeInDetected(AgentState interruptedState, Instant timestamp) implements VoiceEvent {
        public BargeInDetected(AgentState interruptedState) {
            this(interruptedState, Instant.now());
        }
    }

    /**
     * Error occurred in the pipeline
     */
    record ErrorOccurred(String component, String message, Throwable cause, Instant timestamp) implements VoiceEvent {
        public ErrorOccurred(String component, String message, Throwable cause) {
            this(component, message, cause, Instant.now());
        }

        public ErrorOccurred(String component, String message) {
            this(component, message, null, Instant.now());
        }
    }
}
