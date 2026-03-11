package org.example.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.Response;
import org.example.core.model.ConversationTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Google Gemini-based implementation of the AgentBrain.
 * Uses Gemini's FREE tier - 15 requests/minute, 1500 requests/day.
 * 
 * Get your free API key at: https://aistudio.google.com/app/apikey
 */
public class GeminiBrain implements AgentBrain {
    private static final Logger log = LoggerFactory.getLogger(GeminiBrain.class);

    private final GoogleAiGeminiChatModel model;
    private final GeminiConfig config;
    private final List<ChatMessage> conversationHistory;
    private final AtomicBoolean generating;

    private String systemPrompt;

    public GeminiBrain(String geminiApiKey) {
        this(geminiApiKey, GeminiConfig.defaults());
    }

    public GeminiBrain(String geminiApiKey, GeminiConfig config) {
        this.config = config;
        this.conversationHistory = new ArrayList<>();
        this.generating = new AtomicBoolean(false);
        this.systemPrompt = config.defaultSystemPrompt();

        this.model = GoogleAiGeminiChatModel.builder()
                .apiKey(geminiApiKey)
                .modelName(config.modelName())
                .temperature(config.temperature())
                .maxOutputTokens(config.maxTokens())
                .build();

        log.info("GeminiBrain initialized with model: {} (FREE tier)", config.modelName());
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

        // Simulate streaming by generating the full response and emitting word by word
        return Flux.<String>create(sink -> {
            try {
                List<ChatMessage> messages = buildMessageList(userMessage, history);
                
                log.debug("Sending request to Gemini...");
                Response<AiMessage> response = model.generate(messages);
                String fullResponse = response.content().text();
                
                log.debug("Gemini response received: {} chars", fullResponse.length());

                if (config.maintainHistory()) {
                    conversationHistory.add(UserMessage.from(userMessage));
                    conversationHistory.add(response.content());
                    trimHistoryIfNeeded();
                }

                // Emit the response in chunks (simulating streaming for TTS)
                String[] words = fullResponse.split("(?<=\\s)");
                for (String word : words) {
                    if (!generating.get()) {
                        break;
                    }
                    sink.next(word);
                }

                generating.set(false);
                sink.complete();

            } catch (Exception e) {
                generating.set(false);
                log.error("Gemini generation error", e);
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String generateResponseBlocking(String userMessage) {
        List<ChatMessage> messages = buildMessageList(userMessage, List.of());
        Response<AiMessage> response = model.generate(messages);

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
        generating.set(false);
        log.debug("Gemini generation cancelled");
    }

    @Override
    public boolean isGenerating() {
        return generating.get();
    }

    private List<ChatMessage> buildMessageList(String userMessage, List<ConversationTurn> history) {
        List<ChatMessage> messages = new ArrayList<>();

        // Add system message first
        messages.add(SystemMessage.from(systemPrompt));

        // Add conversation history
        for (ChatMessage msg : conversationHistory) {
            messages.add(msg);
        }

        // Add provided history
        for (ConversationTurn turn : history) {
            switch (turn.role()) {
                case USER -> messages.add(UserMessage.from(turn.content()));
                case AGENT -> messages.add(AiMessage.from(turn.content()));
                case SYSTEM -> messages.add(SystemMessage.from(turn.content()));
            }
        }

        // Add current user message
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
     * Configuration for Gemini Brain
     */
    public record GeminiConfig(
            String modelName,
            double temperature,
            int maxTokens,
            boolean maintainHistory,
            int maxHistoryTurns,
            String defaultSystemPrompt
    ) {
        public static GeminiConfig defaults() {
            return new GeminiConfig(
                    "gemini-2.5-flash",  // Fast and FREE (March 2026)
                    0.7,
                    1024,
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
            private String modelName = "gemini-2.5-flash";
            private double temperature = 0.7;
            private int maxTokens = 1024;
            private boolean maintainHistory = true;
            private int maxHistoryTurns = 10;
            private String defaultSystemPrompt = GeminiConfig.defaults().defaultSystemPrompt();

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

            public GeminiConfig build() {
                return new GeminiConfig(
                        modelName, temperature, maxTokens,
                        maintainHistory, maxHistoryTurns, defaultSystemPrompt
                );
            }
        }
    }
}
