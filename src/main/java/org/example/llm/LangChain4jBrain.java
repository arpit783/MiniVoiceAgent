package org.example.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import org.example.core.model.ConversationTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LangChain4j-based implementation of the AgentBrain.
 * Supports streaming responses for low-latency voice interactions.
 */
public class LangChain4jBrain implements AgentBrain {
    private static final Logger log = LoggerFactory.getLogger(LangChain4jBrain.class);

    private final StreamingChatLanguageModel streamingModel;
    private final ChatLanguageModel blockingModel;
    private final BrainConfig config;
    private final List<ChatMessage> conversationHistory;
    private final AtomicBoolean generating;
    private final AtomicReference<Sinks.Many<String>> currentSink;

    private String systemPrompt;

    public LangChain4jBrain(String openAiApiKey) {
        this(openAiApiKey, BrainConfig.defaults());
    }

    public LangChain4jBrain(String openAiApiKey, BrainConfig config) {
        this.config = config;
        this.conversationHistory = new ArrayList<>();
        this.generating = new AtomicBoolean(false);
        this.currentSink = new AtomicReference<>();
        this.systemPrompt = config.defaultSystemPrompt();

        this.streamingModel = OpenAiStreamingChatModel.builder()
                .apiKey(openAiApiKey)
                .modelName(config.modelName())
                .temperature(config.temperature())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();

        this.blockingModel = OpenAiChatModel.builder()
                .apiKey(openAiApiKey)
                .modelName(config.modelName())
                .temperature(config.temperature())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();

        log.info("LangChain4jBrain initialized with model: {}", config.modelName());
    }

    @Override
    public Flux<String> generateResponse(String userMessage) {
        return generateResponse(userMessage, List.of());
    }

    @Override
    public Flux<String> generateResponse(String userMessage, List<ConversationTurn> history) {
        if (generating.get()) {
            log.warn("Already generating a response, cancelling previous");
            cancelGeneration();
        }

        generating.set(true);

        return Flux.create(sink -> {
            Sinks.Many<String> reactiveSink = Sinks.many().unicast().onBackpressureBuffer();
            currentSink.set(reactiveSink);

            List<ChatMessage> messages = buildMessageList(userMessage, history);

            streamingModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
                private final StringBuilder fullResponse = new StringBuilder();

                @Override
                public void onNext(String token) {
                    if (!generating.get()) {
                        return;
                    }
                    fullResponse.append(token);
                    sink.next(token);
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    generating.set(false);
                    currentSink.set(null);

                    if (config.maintainHistory()) {
                        conversationHistory.add(UserMessage.from(userMessage));
                        conversationHistory.add(AiMessage.from(fullResponse.toString()));

                        trimHistoryIfNeeded();
                    }

                    log.debug("LLM generation complete: {} tokens",
                            fullResponse.length());
                    sink.complete();
                }

                @Override
                public void onError(Throwable error) {
                    generating.set(false);
                    currentSink.set(null);
                    log.error("LLM generation error", error);
                    sink.error(error);
                }
            });
        });
    }

    @Override
    public String generateResponseBlocking(String userMessage) {
        List<ChatMessage> messages = buildMessageList(userMessage, List.of());
        Response<AiMessage> response = blockingModel.generate(messages);

        String responseText = response.content().text();

        if (config.maintainHistory()) {
            conversationHistory.add(UserMessage.from(userMessage));
            conversationHistory.add(response.content());
            trimHistoryIfNeeded();
        }

        return responseText;
    }

    @Override
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        log.debug("System prompt updated");
    }

    @Override
    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Override
    public void clearContext() {
        conversationHistory.clear();
        log.debug("Conversation context cleared");
    }

    @Override
    public void cancelGeneration() {
        if (generating.compareAndSet(true, false)) {
            Sinks.Many<String> sink = currentSink.getAndSet(null);
            if (sink != null) {
                sink.tryEmitComplete();
            }
            log.debug("LLM generation cancelled");
        }
    }

    @Override
    public boolean isGenerating() {
        return generating.get();
    }

    private List<ChatMessage> buildMessageList(String userMessage, List<ConversationTurn> history) {
        List<ChatMessage> messages = new ArrayList<>();

        messages.add(SystemMessage.from(systemPrompt));

        for (ChatMessage msg : conversationHistory) {
            messages.add(msg);
        }

        for (ConversationTurn turn : history) {
            switch (turn.role()) {
                case USER -> messages.add(UserMessage.from(turn.content()));
                case AGENT -> messages.add(AiMessage.from(turn.content()));
                case SYSTEM -> messages.add(SystemMessage.from(turn.content()));
            }
        }

        messages.add(UserMessage.from(userMessage));

        return messages;
    }

    private void trimHistoryIfNeeded() {
        while (conversationHistory.size() > config.maxHistoryTurns() * 2) {
            conversationHistory.remove(0);
            conversationHistory.remove(0);
        }
    }

    /**
     * Configuration for the LangChain4j Brain
     */
    public record BrainConfig(
            String modelName,
            double temperature,
            int maxTokens,
            int timeoutSeconds,
            boolean maintainHistory,
            int maxHistoryTurns,
            String defaultSystemPrompt
    ) {
        public static BrainConfig defaults() {
            return new BrainConfig(
                    "gpt-4o",
                    0.7,
                    1024,
                    30,
                    true,
                    10,
                    """
                    You are a helpful, friendly voice assistant. Keep your responses concise and conversational.
                    
                    Guidelines:
                    - Respond naturally as if speaking in a conversation
                    - Keep responses brief (1-3 sentences when possible)
                    - Avoid lists and complex formatting that doesn't work well in speech
                    - Use natural speech patterns and contractions
                    - If you don't understand, ask for clarification
                    - Be warm and personable while staying professional
                    """
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String modelName = "gpt-4o";
            private double temperature = 0.7;
            private int maxTokens = 1024;
            private int timeoutSeconds = 30;
            private boolean maintainHistory = true;
            private int maxHistoryTurns = 10;
            private String defaultSystemPrompt = BrainConfig.defaults().defaultSystemPrompt();

            public Builder modelName(String modelName) {
                this.modelName = modelName;
                return this;
            }

            public Builder temperature(double temperature) {
                this.temperature = temperature;
                return this;
            }

            public Builder maxTokens(int maxTokens) {
                this.maxTokens = maxTokens;
                return this;
            }

            public Builder timeoutSeconds(int timeout) {
                this.timeoutSeconds = timeout;
                return this;
            }

            public Builder maintainHistory(boolean maintain) {
                this.maintainHistory = maintain;
                return this;
            }

            public Builder maxHistoryTurns(int turns) {
                this.maxHistoryTurns = turns;
                return this;
            }

            public Builder systemPrompt(String prompt) {
                this.defaultSystemPrompt = prompt;
                return this;
            }

            public BrainConfig build() {
                return new BrainConfig(
                        modelName, temperature, maxTokens, timeoutSeconds,
                        maintainHistory, maxHistoryTurns, defaultSystemPrompt
                );
            }
        }
    }
}
