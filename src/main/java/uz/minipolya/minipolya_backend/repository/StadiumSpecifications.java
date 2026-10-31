package uz.minipolya.minipolya_backend.repository;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import uz.minipolya.minipolya_backend.entity.Stadium;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static uz.minipolya.minipolya_backend.repository.SearchPredicates.containsIgnoreCase;
import static uz.minipolya.minipolya_backend.repository.SearchPredicates.hasText;

/**
 * Criteria for the stadium lists: the admin's, every status included, and the players'
 * public search. The owner and the amenities (an eagerly-loaded one-to-one) come in the
 * same query.
 */
public final class StadiumSpecifications {

    private StadiumSpecifications() {
    }

    public static Specification<Stadium> adminFilter(String status, String search, String district,
                                                     String format, UUID ownerId) {
        return (root, query, cb) -> {
            if (FetchJoins.isCount(query)) {
                root.join("owner", JoinType.INNER);
            } else {
                root.fetch("owner", JoinType.INNER);
                root.fetch("amenities", JoinType.LEFT);
            }
            List<Predicate> where = new ArrayList<>();
            if (hasText(status)) {
                where.add(cb.equal(root.get("status"), status.trim()));
            }
            if (hasText(search)) {
                where.add(cb.or(
                        containsIgnoreCase(cb, root.get("name"), search),
                        containsIgnoreCase(cb, root.get("address"), search)));
            }
            if (district != null) {
                where.add(cb.equal(root.get("district"), district));
            }
            if (format != null) {
                where.add(cb.equal(root.get("format"), format));
            }
            if (ownerId != null) {
                where.add(cb.equal(root.get("owner").get("id"), ownerId));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /**
     * The players' search: approved, active stadiums only. Every filter is optional;
     * {@code maxPrice} is an owner price (the caller converts the player's figure).
     */
    public static Specification<Stadium> publicFilter(String q, String format, Long maxPrice, String district) {
        return (root, query, cb) -> {
            if (!FetchJoins.isCount(query)) {
                root.fetch("owner", JoinType.INNER);
                root.fetch("amenities", JoinType.LEFT);
            }
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("status"), "approved"));
            where.add(cb.isTrue(root.get("isActive")));
            if (hasText(q)) {
                where.add(cb.or(
                        containsIgnoreCase(cb, root.get("name"), q),
                        containsIgnoreCase(cb, root.get("address"), q)));
            }
            if (hasText(format)) {
                where.add(cb.equal(root.get("format"), format.trim()));
            }
            if (maxPrice != null) {
                where.add(cb.le(root.get("pricePerHour"), maxPrice));
            }
            if (district != null) {
                where.add(cb.equal(root.get("district"), district));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
