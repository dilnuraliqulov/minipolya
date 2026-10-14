package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.TeamMember;

import java.util.List;
import java.util.UUID;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {

    List<TeamMember> findByUserId(UUID userId);

    List<TeamMember> findByTeamId(UUID teamId);

    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);
}
