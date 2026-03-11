package org.example.core.model;

import java.time.Instant;
import java.util.Arrays;

/**
 * Represents a single frame of audio data in the voice pipeline.
 * Immutable record for thread-safe passing between components.
 */
public record AudioFrame(
        byte[] data,
        int sampleRate,
        int channels,
        int bitsPerSample,
        Instant timestamp,
        String trackId
) {
    public static final int DEFAULT_SAMPLE_RATE = 16000;
    public static final int DEFAULT_CHANNELS = 1;
    public static final int DEFAULT_BITS_PER_SAMPLE = 16;

    public AudioFrame {
        if (data == null) {
            throw new IllegalArgumentException("Audio data cannot be null");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("Channels must be positive");
        }
        if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
            throw new IllegalArgumentException("Bits per sample must be 8, 16, 24, or 32");
        }
        data = Arrays.copyOf(data, data.length);
    }

    public static AudioFrame of(byte[] data) {
        return new AudioFrame(
                data,
                DEFAULT_SAMPLE_RATE,
                DEFAULT_CHANNELS,
                DEFAULT_BITS_PER_SAMPLE,
                Instant.now(),
                "default"
        );
    }

    public static AudioFrame of(byte[] data, String trackId) {
        return new AudioFrame(
                data,
                DEFAULT_SAMPLE_RATE,
                DEFAULT_CHANNELS,
                DEFAULT_BITS_PER_SAMPLE,
                Instant.now(),
                trackId
        );
    }

    public int durationMs() {
        int bytesPerSample = bitsPerSample / 8;
        int totalSamples = data.length / (bytesPerSample * channels);
        return (int) ((totalSamples * 1000L) / sampleRate);
    }

    public boolean isEmpty() {
        return data.length == 0;
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    @Override
    public String toString() {
        return "AudioFrame[bytes=%d, rate=%d, channels=%d, bits=%d, duration=%dms]"
                .formatted(data.length, sampleRate, channels, bitsPerSample, durationMs());
    }
}
