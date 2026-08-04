package com.cityseek.feed;

public class LostOutboxLeaseException extends RuntimeException {

    public LostOutboxLeaseException(Long eventId) {
        super("Outbox lease was lost for event " + eventId);
    }
}
