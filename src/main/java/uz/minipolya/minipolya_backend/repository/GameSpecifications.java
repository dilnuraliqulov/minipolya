package uz.minipolya.minipolya_backend.repository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.User;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static uz.minipolya.minipolya_backend.repository.SearchPredicates.containsIgnoreCase;
import static uz.minipolya.minipolya_backend.repository.SearchPredicates.hasText;

/** Criteria for the admin game list. The stadium and the creator come in the same query. */
public final class GameSpecifications {

    private GameSpecifications() {
    }

    public static Specification<Game> adminFilter(AdminGameFilter filter) {
        return (root, query, cb) -> {
            FetchJoins.fetchStadium(root, query);
            Join<Game, User> creator = FetchJoins.fetchOrJoin(root, query, "creator", JoinType.INNER);

            List<Predicate> where = new ArrayList<>();
            if (hasText(filter.search())) {
                // The creator, or any team that has been in the game.
                Subquery<UUID> teams = query.subquery(UUID.class);
                Root<GameTeam> gameTeam = teams.from(GameTeam.class);
                teams.select(gameTeam.get("game").get("id"))
                        .where(containsIgnoreCase(cb, gameTeam.get("team").get("name"), filter.search()));
                where.add(cb.or(
                        containsIgnoreCase(cb, creator.get("name"), filter.search()),
                        root.get("id").in(teams)));
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
                // Created it, played in it, or led a team in it.
                Subquery<UUID> played = query.subquery(UUID.class);
                Root<GameParticipant> participant = played.from(GameParticipant.class);
                played.select(participant.get("game").get("id"))
                        .where(cb.equal(participant.get("user").get("id"), filter.userId()));
                Subquery<UUID> led = query.subquery(UUID.class);
                Root<GameTeam> team = led.from(GameTeam.class);
                led.select(team.get("game").get("id"))
                        .where(cb.equal(team.get("leader").get("id"), filter.userId()));
                where.add(cb.or(
                        cb.equal(creator.get("id"), filter.userId()),
                        root.get("id").in(played),
                        root.get("id").in(led)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
