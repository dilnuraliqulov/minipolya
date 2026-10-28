package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.User;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTelegramId(Long telegramId);

    Optional<User> findByPhone(String phone);

    /**
     * The user holding this username, case-insensitively. Locks the row so concurrent
     * wrong passwords cannot slip past the lockout counter.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username)")
    Optional<User> findByUsernameForUpdate(@Param("username") String username);

    long countByIsOwnerTrue();

    long countByCreatedAtAfter(OffsetDateTime after);

    @Query("""
            SELECT u FROM User u
            WHERE LOWER(u.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR u.phone LIKE CONCAT('%', :search, '%')
            """)
    Page<User> searchUsers(@Param("search") String search, Pageable pageable);
}
