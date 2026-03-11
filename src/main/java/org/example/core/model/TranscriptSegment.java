package org.example.core.model;

import java.time.Instant;

/**
 * Represents a segment of transcribed speech from STT.
 * Can be partial (streaming) or final.
 */
public record TranscriptSegment(
        String text,
        boolean isFinal,
        float confidence,
        Instant timestamp,
        long startTimeMs,
        long endTimeMs,
        String speakerId
) {
    public TranscriptSegment {
        if (text == null) {
            text = "";
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Confidence must be between 0 and 1");
        }
    }

    public static TranscriptSegment partial(String text) {
        return new TranscriptSegment(text, false, 0.0f, Instant.now(), 0, 0, "user");
    }

    public static TranscriptSegment finalSegment(String text, float confidence) {
        return new TranscriptSegment(text, true, confidence, Instant.now(), 0, 0, "user");
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }

    public TranscriptSegment withText(String newText) {
        return new TranscriptSegment(newText, isFinal, confidence, timestamp, startTimeMs, endTimeMs, speakerId);
    }
}
