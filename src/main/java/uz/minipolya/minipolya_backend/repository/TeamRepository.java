package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Team;

import java.util.List;
import java.util.UUID;

@Repository
public interface TeamRepository extends JpaRepository<Team, UUID> {

    List<Team> findByLeaderId(UUID leaderId);

    /** Every team a user leads or belongs to, its leader loaded with it, by name. */
    @Query("""
            SELECT t FROM Team t JOIN FETCH t.leader
            WHERE t.leader.id = :userId
               OR t.id IN (SELECT m.team.id FROM TeamMember m WHERE m.user.id = :userId)
            ORDER BY t.name, t.id
            """)
    List<Team> findAllOfUser(@Param("userId") UUID userId);
}
