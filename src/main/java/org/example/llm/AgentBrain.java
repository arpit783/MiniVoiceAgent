package org.example.llm;

import org.example.core.model.ConversationTurn;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Interface for the LLM "brain" of the voice agent.
 * Handles conversation context and generates streaming responses.
 */
public interface AgentBrain {

    /**
     * Generate a response to the user's input with streaming output.
     * Each emitted string is a token/chunk for immediate TTS processing.
     *
     * @param userMessage The transcribed user input
     * @return Flux of response tokens (streaming)
     */
    Flux<String> generateResponse(String userMessage);

    /**
     * Generate a response considering full conversation history
     *
     * @param userMessage The current user input
     * @param history Previous conversation turns
     * @return Flux of response tokens (streaming)
     */
    Flux<String> generateResponse(String userMessage, List<ConversationTurn> history);

    /**
     * Generate a complete (non-streaming) response
     *
     * @param userMessage The user input
     * @return Complete response text
     */
    String generateResponseBlocking(String userMessage);

    /**
     * Set the system prompt that defines the agent's personality
     *
     * @param systemPrompt The system prompt
     */
    void setSystemPrompt(String systemPrompt);

    /**
     * Get the current system prompt
     */
    String getSystemPrompt();

    /**
     * Clear conversation memory/context
     */
    void clearContext();

    /**
     * Cancel any ongoing generation
     */
    void cancelGeneration();

    /**
     * Check if the brain is currently generating a response
     */
    boolean isGenerating();
}
