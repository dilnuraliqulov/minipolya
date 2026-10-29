package uz.minipolya.minipolya_backend.repository;

import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;

/**
 * A specification runs twice for a page: once for the rows, once for the count. The rows
 * fetch their associations in the same statement (no N+1); the count cannot fetch, so it
 * gets a plain join of the same kind, which the filters can use just the same.
 */
final class FetchJoins {

    private FetchJoins() {
    }

    static boolean isCount(CriteriaQuery<?> query) {
        return query != null && (query.getResultType() == Long.class || query.getResultType() == long.class);
    }

    /**
     * The row's stadium, with its amenities in the same statement: {@code Stadium.amenities}
     * is the inverse side of a one-to-one, which Hibernate always loads eagerly, so leaving
     * it out costs one query per row.
     */
    static <X> void fetchStadium(Root<X> root, CriteriaQuery<?> query) {
        if (isCount(query)) {
            root.join("stadium", JoinType.INNER);
        } else {
            root.fetch("stadium", JoinType.INNER).fetch("amenities", JoinType.LEFT);
        }
    }

    /** Fetches the association on the row query, joins it on the count query. */
    @SuppressWarnings("unchecked")
    static <X, Y> Join<X, Y> fetchOrJoin(Root<X> root, CriteriaQuery<?> query, String attribute, JoinType type) {
        return isCount(query)
                ? root.join(attribute, type)
                : (Join<X, Y>) root.<X, Y>fetch(attribute, type);
    }
}
