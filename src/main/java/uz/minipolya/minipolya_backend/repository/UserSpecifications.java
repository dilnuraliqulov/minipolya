package uz.minipolya.minipolya_backend.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import uz.minipolya.minipolya_backend.entity.User;

import java.util.ArrayList;
import java.util.List;

import static uz.minipolya.minipolya_backend.repository.SearchPredicates.containsIgnoreCase;
import static uz.minipolya.minipolya_backend.repository.SearchPredicates.hasText;
import static uz.minipolya.minipolya_backend.repository.SearchPredicates.phoneContains;

/** Criteria for the admin user list. Every filter is optional; they combine with AND. */
public final class UserSpecifications {

    /** {@code player}: neither owner nor admin. */
    public enum Role { PLAYER, OWNER, ADMIN }

    public enum Status { ACTIVE, BLOCKED }

    private UserSpecifications() {
    }

    public static Specification<User> adminFilter(String search, Role role, Status status) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (hasText(search)) {
                where.add(cb.or(
                        containsIgnoreCase(cb, root.get("name"), search),
                        phoneContains(cb, root.get("phone"), search)));
            }
            if (role == Role.OWNER) {
                where.add(cb.isTrue(root.get("isOwner")));
            } else if (role == Role.ADMIN) {
                where.add(cb.isTrue(root.get("isAdmin")));
            } else if (role == Role.PLAYER) {
                where.add(cb.isFalse(root.get("isOwner")));
                where.add(cb.isFalse(root.get("isAdmin")));
            }
            if (status != null) {
                where.add(cb.equal(root.get("isBlocked"), status == Status.BLOCKED));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
