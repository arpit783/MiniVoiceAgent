package org.example.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reactive event bus for communication between voice pipeline components.
 * Uses Project Reactor's Sinks for backpressure-aware event distribution.
 */
public class EventBus {
    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Sinks.Many<VoiceEvent> eventSink;
    private final Flux<VoiceEvent> eventFlux;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public EventBus() {
        this.eventSink = Sinks.many().multicast().onBackpressureBuffer(1024);
        this.eventFlux = eventSink.asFlux()
                .publishOn(Schedulers.parallel())
                .share();
    }

    /**
     * Publish an event to all subscribers
     */
    public void publish(VoiceEvent event) {
        if (closed.get()) {
            log.warn("Attempted to publish event to closed EventBus: {}", event);
            return;
        }

        Sinks.EmitResult result = eventSink.tryEmitNext(event);
        if (result.isFailure()) {
            log.error("Failed to emit event: {} - Result: {}", event, result);
        } else {
            log.debug("Published event: {}", event.getClass().getSimpleName());
        }
    }

    /**
     * Subscribe to all events
     */
    public Flux<VoiceEvent> events() {
        return eventFlux;
    }

    /**
     * Subscribe to events of a specific type
     */
    @SuppressWarnings("unchecked")
    public <T extends VoiceEvent> Flux<T> eventsOfType(Class<T> eventType) {
        return eventFlux
                .filter(eventType::isInstance)
                .map(e -> (T) e);
    }

    /**
     * Subscribe to multiple event types
     */
    public Flux<VoiceEvent> eventsOfTypes(Class<? extends VoiceEvent>... eventTypes) {
        return eventFlux.filter(event -> {
            for (Class<? extends VoiceEvent> type : eventTypes) {
                if (type.isInstance(event)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Close the event bus (no more events will be published)
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            eventSink.tryEmitComplete();
            log.info("EventBus closed");
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
