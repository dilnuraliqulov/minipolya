package uz.minipolya.minipolya_backend.repository;

import uz.minipolya.minipolya_backend.util.TashkentDays;

import java.util.UUID;

/** The admin game list's filters; every one optional, combined with AND. */
public record AdminGameFilter(String search, UUID stadiumId, String status,
                              TashkentDays.Range startsAt, UUID userId) {
}
