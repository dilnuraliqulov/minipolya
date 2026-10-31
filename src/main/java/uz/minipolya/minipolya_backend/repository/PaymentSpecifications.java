package uz.minipolya.minipolya_backend.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;

import java.util.ArrayList;
import java.util.List;

import static uz.minipolya.minipolya_backend.repository.SearchPredicates.containsIgnoreCase;
import static uz.minipolya.minipolya_backend.repository.SearchPredicates.hasText;
import static uz.minipolya.minipolya_backend.repository.SearchPredicates.phoneContains;

/**
 * Criteria for the admin payment list. The payer, and the stadium of the booking or game
 * paid for, come in the same query. Newest first; with {@code type=refund}, failed refunds
 * first — the ones an admin has to act on.
 */
public final class PaymentSpecifications {

    private PaymentSpecifications() {
    }

    @SuppressWarnings("unchecked")
    public static Specification<Payment> adminFilter(AdminPaymentFilter filter) {
        return (root, query, cb) -> {
            Join<Payment, User> user = FetchJoins.fetchOrJoin(root, query, "user", JoinType.INNER);
            Join<Payment, Booking> booking;
            Join<Payment, Game> game;
            Join<?, Stadium> bookingStadium;
            Join<?, Stadium> gameStadium;
            if (FetchJoins.isCount(query)) {
                booking = root.join("booking", JoinType.LEFT);
                game = root.join("game", JoinType.LEFT);
                bookingStadium = booking.join("stadium", JoinType.LEFT);
                gameStadium = game.join("stadium", JoinType.LEFT);
            } else {
                Fetch<Payment, Booking> bookingFetch = root.fetch("booking", JoinType.LEFT);
                Fetch<Payment, Game> gameFetch = root.fetch("game", JoinType.LEFT);
                booking = (Join<Payment, Booking>) bookingFetch;
                game = (Join<Payment, Game>) gameFetch;
                bookingStadium = stadiumWithAmenities(bookingFetch);
                gameStadium = stadiumWithAmenities(gameFetch);
                orderNewestFirst(query, cb, root, filter);
            }

            List<Predicate> where = new ArrayList<>();
            if (hasText(filter.search())) {
                String text = filter.search();
                where.add(cb.or(
                        containsIgnoreCase(cb, user.get("name"), text),
                        phoneContains(cb, user.get("phone"), text),
                        containsIgnoreCase(cb, root.get("providerTxnId"), text)));
            }
            if (filter.type() != null) {
                where.add(cb.equal(root.get("paymentType"), filter.type()));
            }
            if (filter.status() != null) {
                where.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.method() != null) {
                where.add(cb.equal(root.get("method"), filter.method()));
            }
            if (filter.stadiumId() != null) {
                where.add(cb.or(
                        cb.equal(bookingStadium.get("id"), filter.stadiumId()),
                        cb.equal(gameStadium.get("id"), filter.stadiumId())));
            }
            if (filter.createdAt() != null && filter.createdAt().from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.createdAt().from()));
            }
            if (filter.createdAt() != null && filter.createdAt().to() != null) {
                where.add(cb.lessThan(root.get("createdAt"), filter.createdAt().to()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** The stadium, with its eagerly-loaded amenities one-to-one in the same statement. */
    @SuppressWarnings("unchecked")
    private static Join<?, Stadium> stadiumWithAmenities(Fetch<Payment, ?> parent) {
        Fetch<?, Stadium> stadium = parent.fetch("stadium", JoinType.LEFT);
        stadium.fetch("amenities", JoinType.LEFT);
        return (Join<?, Stadium>) stadium;
    }

    /**
     * Set here rather than through the page's Sort, which cannot express "failed first".
     * The service passes an unsorted page, so Spring Data leaves this order alone.
     */
    private static void orderNewestFirst(CriteriaQuery<?> query, CriteriaBuilder cb,
                                         Root<Payment> root, AdminPaymentFilter filter) {
        List<Order> order = new ArrayList<>();
        if (filter.type() == Payment.PaymentType.REFUND) {
            order.add(cb.asc(cb.selectCase()
                    .when(cb.equal(root.get("status"), Payment.Status.FAILED), 0)
                    .otherwise(1)));
        }
        order.add(cb.desc(root.get("createdAt")));
        order.add(cb.desc(root.get("id")));
        query.orderBy(order);
    }
}
