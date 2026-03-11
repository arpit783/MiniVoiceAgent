package org.example;

import org.example.agent.VoiceAgentOrchestrator;
import org.example.config.VoiceAgentConfig;
import org.example.core.event.EventBus;
import org.example.core.event.VoiceEvent;
import org.example.core.model.AgentState;
import org.example.core.model.AudioFrame;
import org.example.llm.AgentBrain;
import org.example.llm.GeminiBrain;
import org.example.llm.LangChain4jBrain;
import org.example.stt.DeepgramSTTService;
import org.example.tts.ElevenLabsTTSService;
import org.example.vad.EnergyBasedVAD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local microphone test for the Voice Agent.
 * Captures audio from your microphone and plays responses through speakers.
 */
public class LocalMicrophoneTest {
    private static final Logger log = LoggerFactory.getLogger(LocalMicrophoneTest.class);

    private static final int SAMPLE_RATE = 16000;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final int BUFFER_SIZE = 3200; // 100ms of audio at 16kHz, 16-bit mono

    public static void main(String[] args) {
        printBanner();

        try {
            VoiceAgentConfig config = VoiceAgentConfig.fromEnvironment();
            config.validate();

            log.info("Configuration loaded successfully");
            log.info("Starting local microphone test...");

            runLocalTest(config);

        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runLocalTest(VoiceAgentConfig config) throws Exception {
        EventBus eventBus = new EventBus();

        // Initialize services
        DeepgramSTTService sttService = new DeepgramSTTService(
                config.deepgramApiKey(),
                DeepgramSTTService.DeepgramConfig.builder()
                        .model(config.sttModel())
                        .interimResults(true)
                        .build()
        );

        ElevenLabsTTSService ttsService = new ElevenLabsTTSService(
                config.elevenLabsApiKey(),
                ElevenLabsTTSService.ElevenLabsConfig.builder()
                        .voiceId(config.ttsVoiceId())
                        .latencyOptimization(3)
                        .build()
        );

        // Use Gemini (FREE) if available, fallback to OpenAI
        AgentBrain brain = createBrain(config);

        EnergyBasedVAD vad = new EnergyBasedVAD();

        VoiceAgentOrchestrator orchestrator = new VoiceAgentOrchestrator(
                sttService, ttsService, brain, vad, eventBus
        );

        // Subscribe to events for logging
        eventBus.events().subscribe(event -> {
            switch (event) {
                case VoiceEvent.StateChanged sc ->
                        log.info("📊 State: {} -> {}", sc.previousState(), sc.newState());
                case VoiceEvent.TranscriptReceived tr -> {
                    if (tr.segment().isFinal()) {
                        log.info("🎤 You said: {}", tr.segment().text());
                    }
                }
                case VoiceEvent.LLMResponseChunk chunk -> System.out.print(chunk.text());
                case VoiceEvent.LLMResponseComplete complete ->
                        System.out.println("\n🤖 Agent response complete");
                case VoiceEvent.BargeInDetected bi ->
                        log.info("⚡ Barge-in detected!");
                case VoiceEvent.ErrorOccurred err ->
                        log.error("❌ Error: {}", err.message());
                default -> {}
            }
        });

        // Audio playback management
        final java.io.ByteArrayOutputStream audioBuffer = new java.io.ByteArrayOutputStream();
        final AtomicReference<Process> currentPlaybackProcess = new AtomicReference<>();
        final Object audioLock = new Object();
        
        // Listen for TTS audio chunks via event bus
        eventBus.eventsOfType(VoiceEvent.TTSAudioChunk.class)
            .subscribe(event -> {
                synchronized (audioLock) {
                    try {
                        byte[] data = event.frame().data();
                        audioBuffer.write(data);
                        log.debug("🎵 Buffered {} bytes (total: {} bytes)", data.length, audioBuffer.size());
                    } catch (Exception e) {
                        log.error("Error buffering audio", e);
                    }
                }
            });
        
        // Play audio when TTS completes
        eventBus.eventsOfType(VoiceEvent.TTSComplete.class)
            .subscribe(event -> {
                synchronized (audioLock) {
                    int totalBytes = audioBuffer.size();
                    log.info("🔊 TTS Complete! Playing {} bytes of audio...", totalBytes);
                    
                    if (totalBytes > 0) {
                        // Stop any currently playing audio first
                        stopCurrentPlayback(currentPlaybackProcess);
                        
                        byte[] allAudio = audioBuffer.toByteArray();
                        audioBuffer.reset();  // Clear buffer for next response
                        
                        // Start playback
                        playAudioBytes(allAudio, currentPlaybackProcess);
                    }
                }
            });
        
        // Handle barge-in - stop audio playback when user interrupts
        eventBus.eventsOfType(VoiceEvent.BargeInDetected.class)
            .subscribe(event -> {
                log.info("⚡ Barge-in! Stopping audio playback...");
                stopCurrentPlayback(currentPlaybackProcess);
                synchronized (audioLock) {
                    audioBuffer.reset();  // Clear any pending audio
                }
            });
        
        // Also stop on state change to LISTENING (user started talking)
        eventBus.eventsOfType(VoiceEvent.StateChanged.class)
            .filter(event -> event.newState() == AgentState.LISTENING && 
                           event.previousState() == AgentState.SPEAKING)
            .subscribe(event -> {
                log.info("🛑 State changed to LISTENING, stopping playback");
                stopCurrentPlayback(currentPlaybackProcess);
            });

        // Start the orchestrator
        orchestrator.start();

        // Start microphone capture
        AtomicBoolean running = new AtomicBoolean(true);
        Thread micThread = startMicrophoneCapture(orchestrator, running);

        log.info("");
        log.info("╔════════════════════════════════════════════════════════════╗");
        log.info("║  🎤 VOICE AGENT IS LISTENING                               ║");
        log.info("║                                                            ║");
        log.info("║  Speak into your microphone to interact with the agent.    ║");
        log.info("║  The agent will respond through your speakers.             ║");
        log.info("║                                                            ║");
        log.info("║  Press Ctrl+C to stop.                                     ║");
        log.info("╚════════════════════════════════════════════════════════════╝");
        log.info("");

        // Wait for shutdown
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            running.set(false);
            orchestrator.stop();
            eventBus.close();
            shutdownLatch.countDown();
        }));

        shutdownLatch.await();
    }

    private static Thread startMicrophoneCapture(VoiceAgentOrchestrator orchestrator, AtomicBoolean running) {
        Thread thread = new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(
                        SAMPLE_RATE,
                        SAMPLE_SIZE_BITS,
                        CHANNELS,
                        true,  // signed
                        false  // little-endian
                );

                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

                if (!AudioSystem.isLineSupported(info)) {
                    log.error("Microphone not supported!");
                    return;
                }

                TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
                microphone.open(format, BUFFER_SIZE * 4);
                microphone.start();

                log.info("Microphone opened: {}", microphone.getFormat());

                byte[] buffer = new byte[BUFFER_SIZE];

                while (running.get()) {
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        byte[] audioData = new byte[bytesRead];
                        System.arraycopy(buffer, 0, audioData, 0, bytesRead);

                        AudioFrame frame = AudioFrame.of(audioData, "microphone");
                        orchestrator.processAudio(frame);
                    }
                }

                microphone.close();
                log.info("Microphone closed");

            } catch (LineUnavailableException e) {
                log.error("Could not access microphone: {}", e.getMessage());
            }
        }, "microphone-capture");

        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void stopCurrentPlayback(AtomicReference<Process> processRef) {
        Process current = processRef.getAndSet(null);
        if (current != null && current.isAlive()) {
            log.info("🛑 Killing current audio playback...");
            current.destroyForcibly();
        }
    }

    private static void playAudioBytes(byte[] audioData, AtomicReference<Process> processRef) {
        // Run audio playback in a separate thread to not block
        new Thread(() -> {
            java.io.File tempFile = null;
            try {
                // Save to MP3 file with unique name
                tempFile = java.io.File.createTempFile("tts_output_", ".mp3");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                    fos.write(audioData);
                }
                
                // Play using macOS afplay command (works with MP3)
                log.info("🔊 Playing audio ({} bytes)...", audioData.length);
                ProcessBuilder pb = new ProcessBuilder("afplay", tempFile.getAbsolutePath());
                Process process = pb.start();
                
                // Store reference so we can kill it on barge-in
                processRef.set(process);
                
                int exitCode = process.waitFor();
                
                if (exitCode == 0) {
                    log.info("🔊 Audio playback complete");
                } else if (process.isAlive() == false && exitCode != 0) {
                    log.debug("Audio playback interrupted (exit code: {})", exitCode);
                }
                
            } catch (InterruptedException e) {
                log.debug("Audio playback thread interrupted");
            } catch (Exception e) {
                log.error("Error playing audio: {}", e.getMessage());
            } finally {
                // Clean up
                processRef.set(null);
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }, "audio-playback").start();
    }

    private static AgentBrain createBrain(VoiceAgentConfig config) {
        String geminiKey = System.getenv("GEMINI_API_KEY");
        
        if (geminiKey != null && !geminiKey.isBlank()) {
            log.info("Using Google Gemini (FREE tier - gemini-2.5-flash)");
            return new GeminiBrain(geminiKey, GeminiBrain.GeminiConfig.builder()
                    .modelName("gemini-2.5-flash")
                    .build());
        }
        
        if (config.openAiApiKey() != null && !config.openAiApiKey().isBlank()) {
            log.info("Using OpenAI GPT-4o");
            return new LangChain4jBrain(config.openAiApiKey(), 
                    LangChain4jBrain.BrainConfig.builder()
                            .modelName(config.llmModel())
                            .build());
        }
        
        throw new IllegalStateException("No LLM API key configured. Set GEMINI_API_KEY (free) or OPENAI_API_KEY");
    }

    private static void printBanner() {
        System.out.println("""
                
                ╔═══════════════════════════════════════════════════════════╗
                ║                                                           ║
                ║          🎤 LOCAL MICROPHONE TEST MODE 🎤                 ║
                ║                                                           ║
                ║     Speak into your microphone to talk to the agent       ║
                ║                                                           ║
                ╚═══════════════════════════════════════════════════════════╝
                
                """);
    }
}
