package uz.minipolya.minipolya_backend.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import uz.minipolya.minipolya_backend.entity.AuditLog;
import uz.minipolya.minipolya_backend.entity.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Criteria query behind {@link AuditLogRepositoryCustom#search}. The actor is fetched in
 * the same query, so a page costs two statements (rows + count) however many entries
 * it holds.
 */
@RequiredArgsConstructor
public class AuditLogRepositoryCustomImpl implements AuditLogRepositoryCustom {

    private final EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public Page<AuditLog> search(AuditLogFilter filter, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<AuditLog> query = cb.createQuery(AuditLog.class);
        Root<AuditLog> root = query.from(AuditLog.class);
        // A fetch is also a join, so the actor filters can use it.
        Join<AuditLog, User> user = (Join<AuditLog, User>) root.<AuditLog, User>fetch("user", JoinType.LEFT);
        query.select(root)
                .where(predicates(filter, cb, root, user))
                .orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
        List<AuditLog> rows = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<AuditLog> countRoot = count.from(AuditLog.class);
        Join<AuditLog, User> countUser = countRoot.join("user", JoinType.LEFT);
        count.select(cb.count(countRoot)).where(predicates(filter, cb, countRoot, countUser));
        long total = entityManager.createQuery(count).getSingleResult();

        return new PageImpl<>(rows, pageable, total);
    }

    private Predicate[] predicates(AuditLogFilter filter, CriteriaBuilder cb,
                                   Root<AuditLog> root, Join<AuditLog, User> user) {
        List<Predicate> where = new ArrayList<>();
        if (hasText(filter.actor())) {
            String pattern = "%" + escapeLike(filter.actor().trim().toLowerCase(Locale.ROOT)) + "%";
            where.add(cb.or(
                    like(cb, cb.lower(user.get("name")), pattern),
                    like(cb, cb.lower(user.get("username")), pattern),
                    like(cb, cb.lower(user.get("phone")), pattern),
                    like(cb, cb.lower(root.get("actorName")), pattern)));
        }
        if (hasText(filter.actorType())) {
            where.add(cb.equal(root.get("actorType"), filter.actorType()));
        }
        if (filter.actions() != null && !filter.actions().isEmpty()) {
            where.add(root.get("action").in(filter.actions()));
        }
        if (hasText(filter.entityType())) {
            where.add(cb.equal(root.get("entityType"), filter.entityType()));
        }
        if (filter.entityId() != null) {
            where.add(cb.equal(root.get("entityId"), filter.entityId()));
        }
        if (filter.from() != null) {
            where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
        }
        if (filter.to() != null) {
            where.add(cb.lessThan(root.get("createdAt"), filter.to()));
        }
        return where.toArray(Predicate[]::new);
    }

    private static Predicate like(CriteriaBuilder cb, Expression<String> expression, String pattern) {
        return cb.like(expression, pattern, '\\');
    }

    /** The search text is matched literally: % and _ are not wildcards. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
