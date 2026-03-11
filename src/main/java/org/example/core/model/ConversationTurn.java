package org.example.core.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single turn in the conversation (either user or agent).
 */
public record ConversationTurn(
        String id,
        Role role,
        String content,
        Instant timestamp,
        long durationMs
) {
    public enum Role {
        USER,
        AGENT,
        SYSTEM
    }

    public ConversationTurn {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (content == null) {
            content = "";
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public static ConversationTurn user(String content) {
        return new ConversationTurn(null, Role.USER, content, Instant.now(), 0);
    }

    public static ConversationTurn agent(String content) {
        return new ConversationTurn(null, Role.AGENT, content, Instant.now(), 0);
    }

    public static ConversationTurn system(String content) {
        return new ConversationTurn(null, Role.SYSTEM, content, Instant.now(), 0);
    }
}
