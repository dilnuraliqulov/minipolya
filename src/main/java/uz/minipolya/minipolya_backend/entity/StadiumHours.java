package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import uz.minipolya.minipolya_backend.util.WeeklyHours;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * One day of a stadium's weekly opening schedule (changeset 053). {@code dayOfWeek} is
 * 0 = Monday … 6 = Sunday, one row per day. A closed day keeps no times; a
 * {@code closeTime} at or before {@code openTime} means the next morning, and 00:00
 * means midnight — see {@code util/WeeklyHours} for the working-day rule these rows feed.
 */
@Entity
@Table(name = "stadium_hours")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StadiumHours {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stadium_id", nullable = false)
    private Stadium stadium;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Builder.Default
    @Column(nullable = false)
    private boolean closed = false;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    /**
     * The schedule those rows describe, or — when they are not all there — the legacy
     * {@code openTime} / {@code closeTime} pair as "the same every day", so a stadium whose
     * rows are missing stays bookable. The one place rows become a {@link WeeklyHours}:
     * {@link Stadium#weeklyHours()} and the stadium list's grouped query both come here.
     */
    public static WeeklyHours toWeekly(List<StadiumHours> rows, LocalTime openTime, LocalTime closeTime) {
        if (rows != null && rows.size() == WeeklyHours.DAYS) {
            return WeeklyHours.of(rows.stream()
                    .map(row -> row.isClosed()
                            ? WeeklyHours.Day.closed(row.getDayOfWeek())
                            : WeeklyHours.Day.open(row.getDayOfWeek(), row.getOpenTime(), row.getCloseTime()))
                    .toList());
        }
        return WeeklyHours.sameEveryDay(openTime, closeTime);
    }
}
