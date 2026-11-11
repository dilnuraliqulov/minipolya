package uz.minipolya.minipolya_backend.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The time a live booking holds a stadium: all the slot rules need to know about it. */
public record BookedSpan(UUID stadiumId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
}
