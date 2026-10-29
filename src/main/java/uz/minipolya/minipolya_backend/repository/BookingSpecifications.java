package uz.minipolya.minipolya_backend.repository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.User;

import java.util.ArrayList;
import java.util.List;

import static uz.minipolya.minipolya_backend.repository.SearchPredicates.containsIgnoreCase;
import static uz.minipolya.minipolya_backend.repository.SearchPredicates.hasText;
import static uz.minipolya.minipolya_backend.repository.SearchPredicates.phoneContains;

/** Criteria for the admin booking list. The stadium and the booking user come in the same query. */
public final class BookingSpecifications {

    private BookingSpecifications() {
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
