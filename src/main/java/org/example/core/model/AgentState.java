package org.example.core.model;

/**
 * Represents the current state of the voice agent in the conversation.
 * Used for orchestration and barge-in detection.
 */
public enum AgentState {
    /**
     * Agent is idle, waiting for user input
     */
    IDLE,

    /**
     * Agent is listening to user speech
     */
    LISTENING,

    /**
     * Agent is processing the user's input (STT -> LLM)
     */
    THINKING,

    /**
     * Agent is speaking (TTS output in progress)
     */
    SPEAKING,

    /**
     * Agent speech was interrupted by user (barge-in)
     */
    INTERRUPTED,

    /**
     * Agent is in an error state
     */
    ERROR,

    /**
     * Agent session has ended
     */
    DISCONNECTED;

    public boolean canTransitionTo(AgentState newState) {
        return switch (this) {
            case IDLE -> newState == LISTENING || newState == DISCONNECTED || newState == ERROR;
            case LISTENING -> newState == THINKING || newState == IDLE || newState == DISCONNECTED || newState == ERROR;
            case THINKING -> newState == SPEAKING || newState == LISTENING || newState == IDLE || newState == ERROR;
            case SPEAKING -> newState == IDLE || newState == INTERRUPTED || newState == DISCONNECTED || newState == ERROR;
            case INTERRUPTED -> newState == LISTENING || newState == IDLE || newState == DISCONNECTED;
            case ERROR -> newState == IDLE || newState == DISCONNECTED;
            case DISCONNECTED -> false;
        };
    }

    public boolean isActive() {
        return this == LISTENING || this == THINKING || this == SPEAKING;
    }
}
