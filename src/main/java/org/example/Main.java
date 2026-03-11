package org.example;

import org.example.agent.VoiceAgentOrchestrator;
import org.example.config.VoiceAgentConfig;
import org.example.core.event.EventBus;
import org.example.core.event.VoiceEvent;
import org.example.core.model.AgentState;
import org.example.llm.LangChain4jBrain;
import org.example.rtc.LiveKitParticipant;
import org.example.rtc.RTCParticipant;
import org.example.stt.DeepgramSTTService;
import org.example.tts.ElevenLabsTTSService;
import org.example.vad.EnergyBasedVAD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

/**
 * Main entry point for the Java Voice Agent.
 * 
 * This voice agent demonstrates a production-grade architecture for real-time
 * voice AI applications using:
 * - LiveKit for WebRTC/RTC transport
 * - Deepgram for streaming Speech-to-Text
 * - LangChain4j + OpenAI for LLM processing
 * - ElevenLabs for streaming Text-to-Speech
 * - Energy-based VAD for barge-in detection
 * 
 * The architecture follows "Deep Systems" thinking with streams, not just API calls.
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        printBanner();

        try {
            VoiceAgentConfig config = VoiceAgentConfig.fromEnvironment();
            log.info("Configuration loaded:\n{}", config);

            if (args.length > 0 && args[0].equals("--validate")) {
                config.validate();
                log.info("Configuration is valid!");
                return;
            }

            if (args.length > 0 && args[0].equals("--demo")) {
                runDemoMode(config);
                return;
            }

            runVoiceAgent(config);

        } catch (IllegalStateException e) {
            log.error("Configuration error: {}", e.getMessage());
            printUsage();
            System.exit(1);
        } catch (Exception e) {
            log.error("Fatal error", e);
            System.exit(1);
        }
    }

    private static void runVoiceAgent(VoiceAgentConfig config) throws InterruptedException {
        log.info("Starting Voice Agent in production mode...");

        config.validate();

        EventBus eventBus = new EventBus();

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

        LangChain4jBrain brain = new LangChain4jBrain(
                config.openAiApiKey(),
                LangChain4jBrain.BrainConfig.builder()
                        .modelName(config.llmModel())
                        .build()
        );

        EnergyBasedVAD vad = new EnergyBasedVAD();

        VoiceAgentOrchestrator orchestrator = new VoiceAgentOrchestrator(
                sttService,
                ttsService,
                brain,
                vad,
                eventBus,
                VoiceAgentOrchestrator.OrchestratorConfig.builder()
                        .enableBargeIn(config.enableBargeIn())
                        .build()
        );

        subscribeToEvents(eventBus);

        orchestrator.start();
        log.info("Voice Agent started successfully");

        if (config.isLiveKitConfigured()) {
            LiveKitParticipant rtcParticipant = new LiveKitParticipant(
                    config.liveKitApiKey(),
                    config.liveKitApiSecret(),
                    config.liveKitServerUrl()
            );

            rtcParticipant.setEventListener(new RTCParticipant.RTCEventListener() {
                @Override
                public void onConnected(RTCParticipant.RoomInfo roomInfo) {
                    log.info("Connected to room: {}", roomInfo.roomName());
                }

                @Override
                public void onDisconnected(String reason) {
                    log.info("Disconnected from room: {}", reason);
                }

                @Override
                public void onParticipantJoined(String participantId, String participantName) {
                    log.info("Participant joined: {}", participantName);
                }

                @Override
                public void onParticipantLeft(String participantId) {
                    log.info("Participant left: {}", participantId);
                }

                @Override
                public void onError(Throwable error) {
                    log.error("RTC error", error);
                }
            });

            rtcParticipant.incomingAudio()
                    .subscribe(orchestrator::processAudio);

            orchestrator.outputAudio()
                    .subscribe(rtcParticipant::sendAudio);

            rtcParticipant.connect(config.defaultRoom(), config.agentName());
        }

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Voice Agent...");
            orchestrator.stop();
            eventBus.close();
            shutdownLatch.countDown();
        }));

        log.info("Voice Agent is running. Press Ctrl+C to stop.");
        shutdownLatch.await();
    }

    /**
     * Demo mode for testing without full API keys.
     * Demonstrates the architecture and component interactions.
     */
    private static void runDemoMode(VoiceAgentConfig config) {
        log.info("Running in DEMO mode (no API calls)");

        EventBus eventBus = new EventBus();

        subscribeToEvents(eventBus);

        log.info("\n=== Voice Agent Demo ===");
        log.info("This demonstrates the streaming pipeline architecture:");
        log.info("  Audio -> VAD -> STT -> LLM -> TTS -> Audio");
        log.info("");

        eventBus.publish(new VoiceEvent.StateChanged(AgentState.IDLE, AgentState.LISTENING));
        sleep(500);

        eventBus.publish(new VoiceEvent.VoiceActivityDetected(true, 55.0f));
        sleep(300);

        eventBus.publish(new VoiceEvent.TranscriptReceived(
                org.example.core.model.TranscriptSegment.partial("Hello")));
        sleep(200);

        eventBus.publish(new VoiceEvent.TranscriptReceived(
                org.example.core.model.TranscriptSegment.partial("Hello, how are")));
        sleep(200);

        eventBus.publish(new VoiceEvent.TranscriptReceived(
                org.example.core.model.TranscriptSegment.finalSegment("Hello, how are you today?", 0.95f)));
        sleep(300);

        eventBus.publish(new VoiceEvent.VoiceActivityDetected(false, 30.0f));
        sleep(200);

        eventBus.publish(new VoiceEvent.StateChanged(AgentState.LISTENING, AgentState.THINKING));
        sleep(500);

        eventBus.publish(new VoiceEvent.LLMResponseChunk("I'm ", true));
        sleep(100);
        eventBus.publish(new VoiceEvent.LLMResponseChunk("doing ", false));
        sleep(100);
        eventBus.publish(new VoiceEvent.LLMResponseChunk("great, ", false));
        sleep(100);
        eventBus.publish(new VoiceEvent.LLMResponseChunk("thanks for asking!", false));
        sleep(200);

        eventBus.publish(new VoiceEvent.StateChanged(AgentState.THINKING, AgentState.SPEAKING));
        sleep(300);

        eventBus.publish(new VoiceEvent.LLMResponseComplete("I'm doing great, thanks for asking!"));
        sleep(500);

        log.info("\n=== Simulating Barge-In ===");
        eventBus.publish(new VoiceEvent.VoiceActivityDetected(true, 60.0f));
        sleep(200);
        eventBus.publish(new VoiceEvent.BargeInDetected(AgentState.SPEAKING));
        sleep(300);

        eventBus.publish(new VoiceEvent.StateChanged(AgentState.SPEAKING, AgentState.INTERRUPTED));
        sleep(200);
        eventBus.publish(new VoiceEvent.StateChanged(AgentState.INTERRUPTED, AgentState.LISTENING));

        sleep(500);
        eventBus.publish(new VoiceEvent.StateChanged(AgentState.LISTENING, AgentState.IDLE));

        log.info("\n=== Demo Complete ===");
        log.info("To run in production mode, set the required API keys:");
        log.info("  export OPENAI_API_KEY=your-key");
        log.info("  export DEEPGRAM_API_KEY=your-key");
        log.info("  export ELEVENLABS_API_KEY=your-key");
        log.info("Then run without --demo flag");

        eventBus.close();
    }

    private static Disposable subscribeToEvents(EventBus eventBus) {
        return eventBus.events().subscribe(event -> {
            switch (event) {
                case VoiceEvent.StateChanged sc ->
                        log.info("📊 State: {} -> {}", sc.previousState(), sc.newState());

                case VoiceEvent.VoiceActivityDetected vad ->
                        log.info("🎤 VAD: {} (energy: {:.1f})",
                                vad.isSpeaking() ? "SPEAKING" : "SILENCE", vad.energy());

                case VoiceEvent.TranscriptReceived tr ->
                        log.info("📝 Transcript [{}]: {}",
                                tr.segment().isFinal() ? "FINAL" : "partial",
                                tr.segment().text());

                case VoiceEvent.LLMResponseChunk chunk ->
                        System.out.print(chunk.text());

                case VoiceEvent.LLMResponseComplete complete ->
                        log.info("\n🤖 Response complete ({} chars)", complete.fullResponse().length());

                case VoiceEvent.BargeInDetected bi ->
                        log.info("⚡ BARGE-IN detected! Interrupting {}", bi.interruptedState());

                case VoiceEvent.ErrorOccurred err ->
                        log.error("❌ Error in {}: {}", err.component(), err.message());

                default ->
                        log.debug("Event: {}", event.getClass().getSimpleName());
            }
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printBanner() {
        System.out.println("""
                
                ╔═══════════════════════════════════════════════════════════╗
                ║                                                           ║
                ║     ██╗   ██╗ ██████╗ ██╗ ██████╗███████╗                 ║
                ║     ██║   ██║██╔═══██╗██║██╔════╝██╔════╝                 ║
                ║     ██║   ██║██║   ██║██║██║     █████╗                   ║
                ║     ╚██╗ ██╔╝██║   ██║██║██║     ██╔══╝                   ║
                ║      ╚████╔╝ ╚██████╔╝██║╚██████╗███████╗                 ║
                ║       ╚═══╝   ╚═════╝ ╚═╝ ╚═════╝╚══════╝                 ║
                ║                                                           ║
                ║      █████╗  ██████╗ ███████╗███╗   ██╗████████╗          ║
                ║     ██╔══██╗██╔════╝ ██╔════╝████╗  ██║╚══██╔══╝          ║
                ║     ███████║██║  ███╗█████╗  ██╔██╗ ██║   ██║             ║
                ║     ██╔══██║██║   ██║██╔══╝  ██║╚██╗██║   ██║             ║
                ║     ██║  ██║╚██████╔╝███████╗██║ ╚████║   ██║             ║
                ║     ╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝  ╚═══╝   ╚═╝             ║
                ║                                                           ║
                ║     Java Voice Agent - Production-Grade AI Assistant      ║
                ║                                                           ║
                ╚═══════════════════════════════════════════════════════════╝
                
                """);
    }

    private static void printUsage() {
        System.out.println("""
                
                Usage: java -jar MiniVoiceAgent.jar [options]
                
                Options:
                  --validate    Validate configuration and exit
                  --demo        Run in demo mode (no API calls)
                
                Environment Variables (required):
                  OPENAI_API_KEY      - OpenAI API key for LLM
                  DEEPGRAM_API_KEY    - Deepgram API key for STT
                  ELEVENLABS_API_KEY  - ElevenLabs API key for TTS
                
                Environment Variables (optional):
                  LIVEKIT_API_KEY     - LiveKit API key
                  LIVEKIT_API_SECRET  - LiveKit API secret
                  LIVEKIT_SERVER_URL  - LiveKit server URL
                  LLM_MODEL           - LLM model (default: gpt-4o)
                  TTS_VOICE_ID        - ElevenLabs voice ID
                  STT_MODEL           - Deepgram model (default: nova-2)
                  ENABLE_BARGE_IN     - Enable barge-in (default: true)
                
                """);
    }
}
