# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Install Maven
RUN apk add --no-cache maven

# Copy pom.xml first for better caching
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Add non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy the built JAR from builder stage
COPY --from=builder /app/target/MiniVoiceAgent-1.0-SNAPSHOT.jar app.jar

# Copy configuration files
COPY --from=builder /app/src/main/resources/logback.xml /app/config/
COPY --from=builder /app/src/main/resources/voice-agent.properties /app/config/

# Create logs directory
RUN mkdir -p /app/logs && chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Environment variables (to be provided at runtime)
ENV JAVA_OPTS=""
ENV GEMINI_API_KEY=""
ENV OPENAI_API_KEY=""
ENV DEEPGRAM_API_KEY=""
ENV ELEVENLABS_API_KEY=""
ENV LIVEKIT_API_KEY=""
ENV LIVEKIT_API_SECRET=""
ENV LIVEKIT_URL=""

# Expose WebSocket server port (if running websocket mode)
EXPOSE 8765
EXPOSE 3000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD pgrep -f "java.*app.jar" || exit 1

# Default command
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar $@"]
CMD [""]
