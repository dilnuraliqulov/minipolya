package uz.minipolya.minipolya_backend.repository;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.User;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static uz.minipolya.minipolya_backend.repository.SearchPredicates.containsIgnoreCase;
import static uz.minipolya.minipolya_backend.repository.SearchPredicates.hasText;
import static uz.minipolya.minipolya_backend.repository.SearchPredicates.phoneContains;

/** Criteria for booking lists. The stadium (and for admins the booking user) come in the same query. */
public final class BookingSpecifications {

    private BookingSpecifications() {
    }

    /** Bookings that are still live: not cancelled in any way, not expired. */
    public static final List<String> LIVE_STATUSES = List.of("pending", "confirmed", "completed");

    /**
     * R38: one stadium's live bookings with something still owed, optionally starting in
     * {@code [from, to)} (either bound may be null). "Owed" is {@code remainingAmount > 0},
     * computed in the query exactly as {@link Booking#getRemainingAmount()} does:
     * {@code totalDue - paidAmount}, where totalDue is {@code card_price} under the player
     * price and {@code price} otherwise.
     */
    public static Specification<Booking> ownerUnpaid(UUID stadiumId, OffsetDateTime from, OffsetDateTime to) {
        return (root, query, cb) -> {
            FetchJoins.fetchStadium(root, query);
            Expression<Long> due = cb.<Long>selectCase()
                    .when(cb.and(cb.equal(root.get("pricingModel"), Booking.PricingModel.PLAYER_PRICE),
                                    cb.isNotNull(root.get("cardPrice"))),
                            root.<Long>get("cardPrice"))
                    .otherwise(cb.coalesce(root.<Long>get("price"), 0L));
            Expression<Long> paid = cb.coalesce(root.<Long>get("paidAmount"), 0L);

            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("stadium").get("id"), stadiumId));
            where.add(root.get("status").in(LIVE_STATUSES));
            where.add(cb.greaterThan(cb.diff(due, paid), 0L));
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("startsAt"), from));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("startsAt"), to));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    public static Specification<Booking> adminFilter(AdminBookingFilter filter) {
        return (root, query, cb) -> {
            FetchJoins.fetchStadium(root, query);
            Join<Booking, User> user = FetchJoins.fetchOrJoin(root, query, "user", JoinType.LEFT);

            List<Predicate> where = new ArrayList<>();
            if (hasText(filter.search())) {
                String text = filter.search();
                // The booking user, or the walk-in client the owner typed in.
                where.add(cb.or(
                        containsIgnoreCase(cb, user.get("name"), text),
                        phoneContains(cb, user.get("phone"), text),
                        containsIgnoreCase(cb, root.get("clientName"), text),
                        phoneContains(cb, root.get("clientPhone"), text)));
            }
            if (filter.stadiumId() != null) {
                where.add(cb.equal(root.get("stadium").get("id"), filter.stadiumId()));
            }
            if (hasText(filter.status())) {
                where.add(cb.equal(root.get("status"), filter.status().trim()));
            }
            if (filter.startsAt() != null && filter.startsAt().from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("startsAt"), filter.startsAt().from()));
            }
            if (filter.startsAt() != null && filter.startsAt().to() != null) {
                where.add(cb.lessThan(root.get("startsAt"), filter.startsAt().to()));
            }
            if (filter.userId() != null) {
                where.add(cb.equal(user.get("id"), filter.userId()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
