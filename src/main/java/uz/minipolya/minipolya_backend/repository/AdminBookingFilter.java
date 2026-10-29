package uz.minipolya.minipolya_backend.repository;

import uz.minipolya.minipolya_backend.util.TashkentDays;

import java.util.UUID;

/** The admin booking list's filters; every one optional, combined with AND. */
public record AdminBookingFilter(String search, UUID stadiumId, String status,
                                 TashkentDays.Range startsAt, UUID userId) {
}
