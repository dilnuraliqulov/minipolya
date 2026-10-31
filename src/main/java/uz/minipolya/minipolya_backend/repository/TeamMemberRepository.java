package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.TeamMember;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

    List<TeamMember> findByUserId(UUID userId);

    List<TeamMember> findByTeamId(UUID teamId);

    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);

    /** {@code [teamId, member count]} for several teams at once. */
    @Query("SELECT m.team.id, COUNT(m) FROM TeamMember m WHERE m.team.id IN :teamIds GROUP BY m.team.id")
    List<Object[]> countByTeamIds(@Param("teamIds") Collection<UUID> teamIds);
}
