package org.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Central configuration for the Voice Agent application.
 * Loads configuration from environment variables or properties file.
 */
public record VoiceAgentConfig(
        String openAiApiKey,
        String geminiApiKey,
        String deepgramApiKey,
        String elevenLabsApiKey,
        String liveKitApiKey,
        String liveKitApiSecret,
        String liveKitServerUrl,
        String agentName,
        String defaultRoom,
        String llmModel,
        String ttsVoiceId,
        String sttModel,
        boolean enableBargeIn,
        int logLevel
) {
    private static final String CONFIG_FILE = "voice-agent.properties";

    public static VoiceAgentConfig fromEnvironment() {
        return new VoiceAgentConfig(
                getEnvOrDefault("OPENAI_API_KEY", ""),
                getEnvOrDefault("GEMINI_API_KEY", ""),
                getEnvOrDefault("DEEPGRAM_API_KEY", ""),
                getEnvOrDefault("ELEVENLABS_API_KEY", ""),
                getEnvOrDefault("LIVEKIT_API_KEY", ""),
                getEnvOrDefault("LIVEKIT_API_SECRET", ""),
                getEnvOrDefault("LIVEKIT_URL", getEnvOrDefault("LIVEKIT_SERVER_URL", "wss://localhost:7880")),
                getEnvOrDefault("AGENT_NAME", "VoiceAgent"),
                getEnvOrDefault("DEFAULT_ROOM", "voice-agent-room"),
                getEnvOrDefault("LLM_MODEL", "gpt-4o"),
                getEnvOrDefault("TTS_VOICE_ID", "21m00Tcm4TlvDq8ikWAM"),
                getEnvOrDefault("STT_MODEL", "nova-2"),
                Boolean.parseBoolean(getEnvOrDefault("ENABLE_BARGE_IN", "true")),
                Integer.parseInt(getEnvOrDefault("LOG_LEVEL", "1"))
        );
    }

    public static VoiceAgentConfig fromProperties() {
        Properties props = new Properties();

        try (InputStream is = VoiceAgentConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not load " + CONFIG_FILE);
        }

        return new VoiceAgentConfig(
                props.getProperty("openai.api.key", getEnvOrDefault("OPENAI_API_KEY", "")),
                props.getProperty("gemini.api.key", getEnvOrDefault("GEMINI_API_KEY", "")),
                props.getProperty("deepgram.api.key", getEnvOrDefault("DEEPGRAM_API_KEY", "")),
                props.getProperty("elevenlabs.api.key", getEnvOrDefault("ELEVENLABS_API_KEY", "")),
                props.getProperty("livekit.api.key", getEnvOrDefault("LIVEKIT_API_KEY", "")),
                props.getProperty("livekit.api.secret", getEnvOrDefault("LIVEKIT_API_SECRET", "")),
                props.getProperty("livekit.server.url", getEnvOrDefault("LIVEKIT_URL", getEnvOrDefault("LIVEKIT_SERVER_URL", "wss://localhost:7880"))),
                props.getProperty("agent.name", getEnvOrDefault("AGENT_NAME", "VoiceAgent")),
                props.getProperty("default.room", getEnvOrDefault("DEFAULT_ROOM", "voice-agent-room")),
                props.getProperty("llm.model", getEnvOrDefault("LLM_MODEL", "gpt-4o")),
                props.getProperty("tts.voice.id", getEnvOrDefault("TTS_VOICE_ID", "21m00Tcm4TlvDq8ikWAM")),
                props.getProperty("stt.model", getEnvOrDefault("STT_MODEL", "nova-2")),
                Boolean.parseBoolean(props.getProperty("enable.barge.in", getEnvOrDefault("ENABLE_BARGE_IN", "true"))),
                Integer.parseInt(props.getProperty("log.level", getEnvOrDefault("LOG_LEVEL", "1")))
        );
    }

    public void validate() {
        StringBuilder errors = new StringBuilder();

        if (openAiApiKey.isBlank() && geminiApiKey.isBlank()) {
            errors.append("- Either OPENAI_API_KEY or GEMINI_API_KEY is required (Gemini is FREE!)\n");
        }
        if (deepgramApiKey.isBlank()) {
            errors.append("- DEEPGRAM_API_KEY is required\n");
        }
        if (elevenLabsApiKey.isBlank()) {
            errors.append("- ELEVENLABS_API_KEY is required\n");
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Configuration validation failed:\n" + errors);
        }
    }

    public boolean isLiveKitConfigured() {
        return !liveKitApiKey.isBlank() && !liveKitApiSecret.isBlank();
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        // First check environment variable
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        // Then check system property (for Maven exec:java)
        value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return defaultValue;
    }

    @Override
    public String toString() {
        return """
                VoiceAgentConfig {
                  OpenAI API Key: %s
                  Gemini API Key: %s (FREE!)
                  Deepgram API Key: %s
                  ElevenLabs API Key: %s
                  LiveKit API Key: %s
                  LiveKit Server: %s
                  Agent Name: %s
                  Default Room: %s
                  LLM Model: %s
                  TTS Voice: %s
                  STT Model: %s
                  Barge-In: %s
                }
                """.formatted(
                mask(openAiApiKey),
                mask(geminiApiKey),
                mask(deepgramApiKey),
                mask(elevenLabsApiKey),
                mask(liveKitApiKey),
                liveKitServerUrl,
                agentName,
                defaultRoom,
                llmModel,
                ttsVoiceId,
                sttModel,
                enableBargeIn
        );
    }

    private String mask(String value) {
        if (value == null || value.length() < 8) {
            return "***";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }
}
