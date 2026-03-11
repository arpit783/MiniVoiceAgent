package org.example.vad;

import org.example.core.model.AudioFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Energy-based Voice Activity Detection implementation.
 * Uses audio energy levels with adaptive thresholding for robust speech detection.
 * 
 * This is critical for the "barge-in" problem - detecting when the user
 * starts speaking while the agent is still talking.
 */
public class EnergyBasedVAD implements VoiceActivityDetector {
    private static final Logger log = LoggerFactory.getLogger(EnergyBasedVAD.class);

    private final VADConfig config;
    private final Deque<Float> energyHistory;
    private final Deque<Float> backgroundEnergyHistory;

    private boolean speaking = false;
    private float currentEnergy = 0;
    private float adaptiveThreshold;
    private long speechStartTime = 0;
    private int consecutiveSpeechFrames = 0;
    private int consecutiveSilenceFrames = 0;

    public EnergyBasedVAD() {
        this(VADConfig.defaults());
    }

    public EnergyBasedVAD(VADConfig config) {
        this.config = config;
        this.energyHistory = new ArrayDeque<>(config.energyHistorySize());
        this.backgroundEnergyHistory = new ArrayDeque<>(config.backgroundHistorySize());
        this.adaptiveThreshold = config.initialThreshold();
    }

    @Override
    public VADResult process(AudioFrame frame) {
        currentEnergy = calculateEnergy(frame);
        energyHistory.addLast(currentEnergy);

        if (energyHistory.size() > config.energyHistorySize()) {
            energyHistory.removeFirst();
        }

        float smoothedEnergy = calculateSmoothedEnergy();

        updateAdaptiveThreshold(smoothedEnergy);

        boolean voiceDetected = smoothedEnergy > adaptiveThreshold;

        return updateState(voiceDetected, smoothedEnergy);
    }

    private float calculateEnergy(AudioFrame frame) {
        byte[] data = frame.data();
        if (data.length == 0) {
            return 0;
        }

        long sumSquares = 0;
        int sampleCount = data.length / 2;

        for (int i = 0; i < data.length - 1; i += 2) {
            short sample = (short) ((data[i + 1] << 8) | (data[i] & 0xFF));
            sumSquares += (long) sample * sample;
        }

        double rms = Math.sqrt((double) sumSquares / sampleCount);

        return (float) (20 * Math.log10(rms + 1));
    }

    private float calculateSmoothedEnergy() {
        if (energyHistory.isEmpty()) {
            return 0;
        }

        float sum = 0;
        for (float e : energyHistory) {
            sum += e;
        }
        return sum / energyHistory.size();
    }

    private void updateAdaptiveThreshold(float currentEnergy) {
        if (!speaking) {
            backgroundEnergyHistory.addLast(currentEnergy);
            if (backgroundEnergyHistory.size() > config.backgroundHistorySize()) {
                backgroundEnergyHistory.removeFirst();
            }

            if (backgroundEnergyHistory.size() >= config.backgroundHistorySize() / 2) {
                float avgBackground = 0;
                for (float e : backgroundEnergyHistory) {
                    avgBackground += e;
                }
                avgBackground /= backgroundEnergyHistory.size();

                adaptiveThreshold = avgBackground + config.thresholdMargin();

                adaptiveThreshold = Math.max(adaptiveThreshold, config.minThreshold());
                adaptiveThreshold = Math.min(adaptiveThreshold, config.maxThreshold());
            }
        }
    }

    private VADResult updateState(boolean voiceDetected, float energy) {
        if (voiceDetected) {
            consecutiveSpeechFrames++;
            consecutiveSilenceFrames = 0;
        } else {
            consecutiveSilenceFrames++;
            consecutiveSpeechFrames = 0;
        }

        boolean wasSpeaking = speaking;

        if (!speaking && consecutiveSpeechFrames >= config.speechStartFrames()) {
            speaking = true;
            speechStartTime = System.currentTimeMillis();
            log.debug("Speech started - energy: {}, threshold: {}", energy, adaptiveThreshold);
            return VADResult.speechStart(energy, adaptiveThreshold);
        }

        if (speaking && consecutiveSilenceFrames >= config.speechEndFrames()) {
            speaking = false;
            long duration = System.currentTimeMillis() - speechStartTime;
            log.debug("Speech ended - duration: {}ms", duration);
            return VADResult.speechEnd(energy, adaptiveThreshold, duration);
        }

        if (speaking) {
            long duration = System.currentTimeMillis() - speechStartTime;
            return VADResult.speaking(energy, adaptiveThreshold, duration);
        }

        return VADResult.silence(energy, adaptiveThreshold);
    }

    @Override
    public void reset() {
        speaking = false;
        currentEnergy = 0;
        consecutiveSpeechFrames = 0;
        consecutiveSilenceFrames = 0;
        speechStartTime = 0;
        energyHistory.clear();
        backgroundEnergyHistory.clear();
        adaptiveThreshold = config.initialThreshold();
        log.debug("VAD state reset");
    }

    @Override
    public boolean isSpeaking() {
        return speaking;
    }

    @Override
    public float getCurrentEnergy() {
        return currentEnergy;
    }

    /**
     * Configuration for energy-based VAD
     */
    public record VADConfig(
            float initialThreshold,
            float minThreshold,
            float maxThreshold,
            float thresholdMargin,
            int energyHistorySize,
            int backgroundHistorySize,
            int speechStartFrames,
            int speechEndFrames
    ) {
        public static VADConfig defaults() {
            return new VADConfig(
                    40.0f,   // Initial threshold (dB) - lowered for sensitivity
                    30.0f,   // Minimum threshold - lowered
                    60.0f,   // Maximum threshold
                    8.0f,    // Margin above background noise - reduced
                    3,       // Frames for energy smoothing - faster response
                    30,      // Frames for background noise estimation - faster adaptation
                    2,       // Consecutive speech frames to start - faster detection
                    10       // Consecutive silence frames to end (200ms at 20ms/frame) - faster end detection
            );
        }

        public static VADConfig sensitive() {
            return new VADConfig(
                    40.0f, 30.0f, 60.0f, 8.0f,
                    3, 30, 2, 10
            );
        }

        public static VADConfig robust() {
            return new VADConfig(
                    50.0f, 40.0f, 70.0f, 15.0f,
                    7, 70, 5, 20
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private float initialThreshold = 45.0f;
            private float minThreshold = 35.0f;
            private float maxThreshold = 65.0f;
            private float thresholdMargin = 10.0f;
            private int energyHistorySize = 5;
            private int backgroundHistorySize = 50;
            private int speechStartFrames = 3;
            private int speechEndFrames = 15;

            public Builder initialThreshold(float threshold) {
                this.initialThreshold = threshold;
                return this;
            }

            public Builder minThreshold(float threshold) {
                this.minThreshold = threshold;
                return this;
            }

            public Builder maxThreshold(float threshold) {
                this.maxThreshold = threshold;
                return this;
            }

            public Builder thresholdMargin(float margin) {
                this.thresholdMargin = margin;
                return this;
            }

            public Builder energyHistorySize(int size) {
                this.energyHistorySize = size;
                return this;
            }

            public Builder backgroundHistorySize(int size) {
                this.backgroundHistorySize = size;
                return this;
            }

            public Builder speechStartFrames(int frames) {
                this.speechStartFrames = frames;
                return this;
            }

            public Builder speechEndFrames(int frames) {
                this.speechEndFrames = frames;
                return this;
            }

            public VADConfig build() {
                return new VADConfig(
                        initialThreshold, minThreshold, maxThreshold, thresholdMargin,
                        energyHistorySize, backgroundHistorySize, speechStartFrames, speechEndFrames
                );
            }
        }
    }
}
