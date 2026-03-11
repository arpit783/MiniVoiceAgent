package org.example.rtc;

import org.example.core.model.AudioFrame;
import reactor.core.publisher.Flux;

/**
 * Interface for RTC (Real-Time Communication) participants.
 * Abstracts the WebRTC/LiveKit layer for the voice agent.
 */
public interface RTCParticipant {

    /**
     * Connect to a room as a participant
     *
     * @param roomName The room to join
     * @param participantName The name/identity for this participant
     */
    void connect(String roomName, String participantName);

    /**
     * Disconnect from the room
     */
    void disconnect();

    /**
     * Check if connected to a room
     */
    boolean isConnected();

    /**
     * Get the stream of incoming audio from other participants
     */
    Flux<AudioFrame> incomingAudio();

    /**
     * Send audio to the room
     *
     * @param frame The audio frame to send
     */
    void sendAudio(AudioFrame frame);

    /**
     * Get room information
     */
    RoomInfo getRoomInfo();

    /**
     * Set a listener for RTC events
     */
    void setEventListener(RTCEventListener listener);

    /**
     * Room information
     */
    record RoomInfo(
            String roomName,
            String participantId,
            String participantName,
            int participantCount
    ) {}

    /**
     * Listener for RTC events
     */
    interface RTCEventListener {
        void onConnected(RoomInfo roomInfo);
        void onDisconnected(String reason);
        void onParticipantJoined(String participantId, String participantName);
        void onParticipantLeft(String participantId);
        void onError(Throwable error);
    }
}
