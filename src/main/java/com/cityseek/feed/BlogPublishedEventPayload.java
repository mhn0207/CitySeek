package com.cityseek.feed;

public record BlogPublishedEventPayload(Long blogId, Long authorId, Long publishedAt) {
}
