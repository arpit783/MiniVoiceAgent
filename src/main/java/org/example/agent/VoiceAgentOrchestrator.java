package org.example.agent;

import org.example.config.VoiceAgentConfig;
import org.example.core.event.EventBus;
import org.example.core.event.VoiceEvent;
import org.example.core.model.*;
import org.example.llm.AgentBrain;
import org.example.stt.SpeechToTextService;
import org.example.tts.TextToSpeechService;
import org.example.vad.VoiceActivityDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The main orchestrator that coordinates all voice agent components.
 * Implements the streaming pipeline: Audio -> STT -> LLM -> TTS -> Audio
 * 
 * Key responsibilities:
 * - State management (IDLE -> LISTENING -> THINKING -> SPEAKING -> IDLE)
 * - Barge-in detection and handling
 * - Pipeline coordination with backpressure
 * - Conversation history management
 */
public class VoiceAgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(VoiceAgentOrchestrator.class);

    private final SpeechToTextService sttService;
    private final TextToSpeechService ttsService;
    private final AgentBrain brain;
    private final VoiceActivityDetector vad;
    private final EventBus eventBus;
    private final OrchestratorConfig config;

    private final AtomicReference<AgentState> currentState;
    private final List<ConversationTurn> conversationHistory;
    private final Sinks.Many<AudioFrame> outputAudioSink;
    private final List<Disposable> subscriptions;

    private TextToSpeechService.StreamingSession currentTTSSession;
    private StringBuilder currentTranscript;
    private StringBuilder currentResponse;

    public VoiceAgentOrchestrator(
            SpeechToTextService sttService,
            TextToSpeechService ttsService,
            AgentBrain brain,
            VoiceActivityDetector vad,
            EventBus eventBus
    ) {
        this(sttService, ttsService, brain, vad, eventBus, OrchestratorConfig.defaults());
    }

    public VoiceAgentOrchestrator(
            SpeechToTextService sttService,
            TextToSpeechService ttsService,
            AgentBrain brain,
            VoiceActivityDetector vad,
            EventBus eventBus,
            OrchestratorConfig config
    ) {
        this.sttService = sttService;
        this.ttsService = ttsService;
        this.brain = brain;
        this.vad = vad;
        this.eventBus = eventBus;
        this.config = config;

        this.currentState = new AtomicReference<>(AgentState.IDLE);
        this.conversationHistory = new CopyOnWriteArrayList<>();
        this.outputAudioSink = Sinks.many().multicast().onBackpressureBuffer(256);
        this.subscriptions = new ArrayList<>();
        this.currentTranscript = new StringBuilder();
        this.currentResponse = new StringBuilder();
    }

    public VoiceAgentOrchestrator(
            SpeechToTextService sttService,
            TextToSpeechService ttsService,
            AgentBrain brain,
            VoiceActivityDetector vad,
            EventBus eventBus,
            VoiceAgentConfig voiceConfig
    ) {
        this(sttService, ttsService, brain, vad, eventBus, 
             OrchestratorConfig.builder()
                     .enableBargeIn(voiceConfig.enableBargeIn())
                     .build());
    }

    /**
     * Start the voice agent orchestrator
     */
    public void start() {
        log.info("Starting VoiceAgentOrchestrator");

        sttService.start();

        subscriptions.add(
                sttService.transcripts()
                        .publishOn(Schedulers.boundedElastic())
                        .subscribe(this::handleTranscript, this::handleError)
        );

        subscriptions.add(
                eventBus.eventsOfType(VoiceEvent.AudioReceived.class)
                        .publishOn(Schedulers.boundedElastic())
                        .subscribe(this::handleIncomingAudio, this::handleError)
        );

        subscriptions.add(
                eventBus.eventsOfType(VoiceEvent.BargeInDetected.class)
                        .subscribe(this::handleBargeIn, this::handleError)
        );

        transitionTo(AgentState.IDLE);
        log.info("VoiceAgentOrchestrator started successfully");
    }

    /**
     * Stop the voice agent orchestrator
     */
    public void stop() {
        log.info("Stopping VoiceAgentOrchestrator");

        for (Disposable sub : subscriptions) {
            sub.dispose();
        }
        subscriptions.clear();

        sttService.stop();
        cancelCurrentGeneration();
        outputAudioSink.tryEmitComplete();

        transitionTo(AgentState.DISCONNECTED);
        log.info("VoiceAgentOrchestrator stopped");
    }

    /**
     * Process incoming audio from the RTC layer
     */
    public void processAudio(AudioFrame frame) {
        eventBus.publish(new VoiceEvent.AudioReceived(frame));
    }

    /**
     * Directly handle incoming audio (for WebSocket server)
     */
    public void handleIncomingAudio(AudioFrame frame) {
        handleIncomingAudio(new VoiceEvent.AudioReceived(frame));
    }

    /**
     * Trigger barge-in (interrupt agent speech)
     */
    public void handleBargeIn() {
        handleBargeIn(new VoiceEvent.BargeInDetected(currentState.get()));
    }

    /**
     * Get the stream of output audio to send to the user
     */
    public Flux<AudioFrame> outputAudio() {
        return outputAudioSink.asFlux();
    }

    /**
     * Get current agent state
     */
    public AgentState getState() {
        return currentState.get();
    }

    /**
     * Get conversation history
     */
    public List<ConversationTurn> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    private void handleIncomingAudio(VoiceEvent.AudioReceived event) {
        AudioFrame frame = event.frame();
        AgentState state = currentState.get();

        VoiceActivityDetector.VADResult vadResult = vad.process(frame);

        if (vadResult.speechStarted()) {
            log.info("🎙️ Speech STARTED (state: {}, energy: {:.1f})", state, vadResult.energy());
            eventBus.publish(new VoiceEvent.VoiceActivityDetected(true, vadResult.energy()));

            if (state == AgentState.SPEAKING || state == AgentState.THINKING) {
                handleBargeIn(new VoiceEvent.BargeInDetected(state));
            } else if (state == AgentState.IDLE || state == AgentState.INTERRUPTED) {
                transitionTo(AgentState.LISTENING);
                currentTranscript = new StringBuilder();
            }
            // If already LISTENING, just continue
        }

        if (vadResult.speechEnded()) {
            log.info("🔇 Speech ENDED (state: {}, duration: {}ms)", currentState.get(), vadResult.speechDurationMs());
            eventBus.publish(new VoiceEvent.VoiceActivityDetected(false, vadResult.energy()));

            AgentState currentStateNow = currentState.get();
            if (currentStateNow == AgentState.LISTENING && vadResult.speechDurationMs() > config.minSpeechDurationMs()) {
                // Give a small delay for final transcripts to arrive, then process
                final long duration = vadResult.speechDurationMs();
                Schedulers.boundedElastic().schedule(() -> {
                    handleSpeechEnded(duration);
                }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }

        // Always send audio to STT - let it handle the connection state
        sttService.sendAudio(frame);
    }

    private volatile long lastFinalTranscriptTime = 0;
    private volatile boolean pendingProcessing = false;

    private void handleTranscript(TranscriptSegment segment) {
        log.info("🎤 Transcript [{}]: {}", segment.isFinal() ? "FINAL" : "partial", segment.text());

        eventBus.publish(new VoiceEvent.TranscriptReceived(segment));

        if (!segment.isEmpty() && segment.isFinal()) {
            currentTranscript.append(segment.text()).append(" ");
            lastFinalTranscriptTime = System.currentTimeMillis();
            
            // Schedule processing after a pause (if VAD doesn't trigger it)
            if (!pendingProcessing) {
                pendingProcessing = true;
                Schedulers.boundedElastic().schedule(() -> {
                    checkAndProcessAfterPause();
                }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
    }

    private void checkAndProcessAfterPause() {
        pendingProcessing = false;
        
        long timeSinceLastTranscript = System.currentTimeMillis() - lastFinalTranscriptTime;
        AgentState state = currentState.get();
        String transcript = currentTranscript.toString().trim();
        
        log.debug("⏱️ Checking after pause: state={}, timeSince={}ms, transcript='{}'", 
                state, timeSinceLastTranscript, transcript.length() > 30 ? transcript.substring(0, 30) + "..." : transcript);
        
        // If we have a transcript, we're in LISTENING state, and it's been quiet for 1+ seconds
        if (!transcript.isEmpty() && 
            (state == AgentState.LISTENING || state == AgentState.IDLE) && 
            timeSinceLastTranscript >= 1000) {
            
            log.info("⏱️ Processing after pause ({}ms of silence)", timeSinceLastTranscript);
            currentTranscript = new StringBuilder();
            
            if (state == AgentState.IDLE) {
                transitionTo(AgentState.LISTENING);
            }
            processUserInput(transcript);
        }
    }

    private void handleSpeechEnded(long speechDurationMs) {
        AgentState state = currentState.get();
        String transcript = currentTranscript.toString().trim();
        
        log.info("📋 handleSpeechEnded called - state: {}, transcript: '{}' ({} chars)", 
                state, transcript.length() > 50 ? transcript.substring(0, 50) + "..." : transcript, transcript.length());
        
        // Only process if we're still in LISTENING state
        if (state != AgentState.LISTENING) {
            log.info("⏭️ Skipping - not in LISTENING state (current: {})", state);
            return;
        }
        
        // Clear transcript for next utterance
        currentTranscript = new StringBuilder();
        
        if (!transcript.isEmpty()) {
            log.info("💬 Processing transcript: '{}'", transcript);
            processUserInput(transcript);
        } else {
            log.info("🔕 No transcript captured, returning to IDLE");
            transitionTo(AgentState.IDLE);
        }
    }

    private boolean shouldProcessInput() {
        if (currentState.get() != AgentState.LISTENING) {
            return false;
        }
        return !vad.isSpeaking();
    }

    private void processUserInput(String input) {
        if (input.isBlank()) {
            transitionTo(AgentState.IDLE);
            return;
        }

        log.info("🧠 Processing user input: {}", input);
        transitionTo(AgentState.THINKING);

        conversationHistory.add(ConversationTurn.user(input));

        currentResponse = new StringBuilder();

        // Collect full LLM response first, then synthesize TTS
        brain.generateResponse(input, conversationHistory)
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(token -> {
                    currentResponse.append(token);
                    System.out.print(token); // Print tokens as they arrive
                })
                .doOnError(this::handleLLMError)
                .doOnComplete(() -> {
                    String fullResponse = currentResponse.toString();
                    log.info("\n🤖 LLM complete: {}", fullResponse);
                    
                    if (!fullResponse.isBlank()) {
                        conversationHistory.add(ConversationTurn.agent(fullResponse));
                        
                        // Now synthesize TTS for the complete response
                        transitionTo(AgentState.SPEAKING);
                        synthesizeAndPlayResponse(fullResponse);
                    } else {
                        transitionTo(AgentState.IDLE);
                    }
                })
                .subscribe();
    }

    private void synthesizeAndPlayResponse(String text) {
        log.info("🔊 Synthesizing TTS for: {}...", text.substring(0, Math.min(50, text.length())));
        
        ttsService.synthesize(text)
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        frame -> {
                            log.debug("TTS audio chunk: {} bytes", frame.data().length);
                            sendOutputAudio(frame);
                        },
                        error -> {
                            log.error("TTS error", error);
                            eventBus.publish(new VoiceEvent.ErrorOccurred("TTS", error.getMessage(), error));
                            transitionTo(AgentState.IDLE);
                        },
                        () -> {
                            log.info("🔊 TTS playback complete");
                            eventBus.publish(new VoiceEvent.TTSComplete(0));
                            transitionTo(AgentState.IDLE);
                        }
                );
    }

    private void handleLLMToken(String token) {
        if (currentState.get() == AgentState.INTERRUPTED) {
            return;
        }

        currentResponse.append(token);
        eventBus.publish(new VoiceEvent.LLMResponseChunk(token, currentResponse.length() == token.length()));

        if (currentTTSSession != null && currentTTSSession.isActive()) {
            currentTTSSession.addText(token);
        }

        if (currentState.get() == AgentState.THINKING) {
            transitionTo(AgentState.SPEAKING);
        }
    }

    private void handleLLMComplete() {
        String fullResponse = currentResponse.toString();
        log.info("LLM response complete: {}...", 
                fullResponse.substring(0, Math.min(100, fullResponse.length())));

        eventBus.publish(new VoiceEvent.LLMResponseComplete(fullResponse));

        if (currentTTSSession != null) {
            currentTTSSession.complete();
        }

        if (!fullResponse.isBlank()) {
            conversationHistory.add(ConversationTurn.agent(fullResponse));
        }
    }

    private void handleLLMError(Throwable error) {
        log.error("LLM generation error", error);
        eventBus.publish(new VoiceEvent.ErrorOccurred("LLM", error.getMessage(), error));

        if (currentTTSSession != null) {
            currentTTSSession.cancel();
        }

        transitionTo(AgentState.IDLE);
    }

    private void sendOutputAudio(AudioFrame frame) {
        if (currentState.get() == AgentState.INTERRUPTED) {
            return;
        }

        outputAudioSink.tryEmitNext(frame);
        eventBus.publish(new VoiceEvent.TTSAudioChunk(frame, false));
    }

    private void handleTTSError(Throwable error) {
        log.error("TTS error", error);
        eventBus.publish(new VoiceEvent.ErrorOccurred("TTS", error.getMessage(), error));
        transitionTo(AgentState.IDLE);
    }

    private void handleTTSComplete() {
        log.debug("TTS playback complete");
        eventBus.publish(new VoiceEvent.TTSComplete(0));

        if (currentState.get() == AgentState.SPEAKING) {
            transitionTo(AgentState.IDLE);
        }
    }

    private void handleBargeIn(VoiceEvent.BargeInDetected event) {
        log.info("⚡ Barge-in detected! Interrupting agent speech.");

        // Only handle barge-in if we're currently speaking or thinking
        AgentState previousState = currentState.get();
        if (previousState != AgentState.SPEAKING && previousState != AgentState.THINKING) {
            log.debug("Ignoring barge-in - not in SPEAKING/THINKING state");
            return;
        }

        transitionTo(AgentState.INTERRUPTED);

        // Cancel LLM and TTS
        cancelCurrentGeneration();

        // Publish barge-in event (triggers audio stop in LocalMicrophoneTest)
        eventBus.publish(event);

        // Reset for new input
        currentTranscript = new StringBuilder();
        currentResponse = new StringBuilder();
        
        // Small delay before transitioning to LISTENING to let audio stop
        Schedulers.boundedElastic().schedule(() -> {
            if (currentState.get() == AgentState.INTERRUPTED) {
                transitionTo(AgentState.LISTENING);
                vad.reset();  // Reset VAD for fresh detection
            }
        }, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void cancelCurrentGeneration() {
        brain.cancelGeneration();

        if (currentTTSSession != null) {
            currentTTSSession.cancel();
            currentTTSSession = null;
        }
    }

    private void transitionTo(AgentState newState) {
        AgentState previous = currentState.getAndSet(newState);
        if (previous != newState) {
            log.info("📊 State: {} -> {}", previous, newState);
            eventBus.publish(new VoiceEvent.StateChanged(previous, newState));
        }
    }

    private void handleError(Throwable error) {
        log.error("Pipeline error", error);
        eventBus.publish(new VoiceEvent.ErrorOccurred("Pipeline", error.getMessage(), error));
        transitionTo(AgentState.ERROR);

        Schedulers.parallel().schedule(() -> {
            if (currentState.get() == AgentState.ERROR) {
                transitionTo(AgentState.IDLE);
            }
        }, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Configuration for the orchestrator
     */
    public record OrchestratorConfig(
            int minSpeechDurationMs,
            int maxConversationTurns,
            boolean enableBargeIn,
            int silenceTimeoutMs
    ) {
        public static OrchestratorConfig defaults() {
            return new OrchestratorConfig(
                    300,    // Minimum speech duration to process
                    20,     // Max conversation turns to keep
                    true,   // Enable barge-in
                    5000    // Silence timeout
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int minSpeechDurationMs = 300;
            private int maxConversationTurns = 20;
            private boolean enableBargeIn = true;
            private int silenceTimeoutMs = 5000;

            public Builder minSpeechDurationMs(int ms) {
                this.minSpeechDurationMs = ms;
                return this;
            }

            public Builder maxConversationTurns(int turns) {
                this.maxConversationTurns = turns;
                return this;
            }

            public Builder enableBargeIn(boolean enable) {
                this.enableBargeIn = enable;
                return this;
            }

            public Builder silenceTimeoutMs(int ms) {
                this.silenceTimeoutMs = ms;
                return this;
            }

            public OrchestratorConfig build() {
                return new OrchestratorConfig(
                        minSpeechDurationMs, maxConversationTurns, enableBargeIn, silenceTimeoutMs
                );
            }
        }
    }
}
