package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.dto.response.AdminGameDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminGameResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminGameTeamResponse;
import uz.minipolya.minipolya_backend.entity.AuditLog;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.AdminActivityMapper;
import uz.minipolya.minipolya_backend.repository.AdminGameFilter;
import uz.minipolya.minipolya_backend.repository.AuditLogRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameSpecifications;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.util.Pagination;
import uz.minipolya.minipolya_backend.util.TashkentDays;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every game on the platform, for the admin panel — read only, no chat and no actions.
 * A page costs a fixed handful of queries: the games with stadium and creator, the
 * count, the teams in them, the participant counts and the cancelling audit entries.
 *
 * <p>Games keep no cancellation time or reason of their own; both come from the audit
 * entry that cancelled the game ({@code GAME_CANCELLED} by a player,
 * {@code GAME_AUTO_CANCELLED} by the system).
 */
@Service
@RequiredArgsConstructor
public class AdminGameService {

    private static final String TEAM_VS_TEAM = "team_vs_team";
    private static final Sort NEWEST_START_FIRST = Sort.by(Sort.Direction.DESC, "startsAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));
    private static final List<String> CANCEL_ACTIONS = List.of(
            AuditAction.GAME_CANCELLED.name(), AuditAction.GAME_AUTO_CANCELLED.name());

    private final GameRepository gameRepository;
    private final GameTeamRepository gameTeamRepository;
    private final GameParticipantRepository participantRepository;
    private final AuditLogRepository auditLogRepository;
    private final TeamGameService teamGameService;
    private final AdminActivityMapper adminActivityMapper;

    @Transactional(readOnly = true)
    public Page<AdminGameResponse> list(String search, UUID stadiumId, String status,
                                        LocalDate from, LocalDate to, UUID userId, int page, int size) {
        TashkentDays.Range startsAt = TashkentDays.range(from, to);
        Pageable pageable = Pagination.of(page, size, NEWEST_START_FIRST);
        Page<Game> games = gameRepository.findAll(GameSpecifications.adminFilter(
                new AdminGameFilter(search, stadiumId, status, startsAt, userId)), pageable);

        List<UUID> ids = games.getContent().stream().map(Game::getId).toList();
        if (ids.isEmpty()) {
            return games.map(adminActivityMapper::toAdminGame);
        }
        Map<UUID, List<GameTeam>> teams = gameTeamRepository
                .findWithTeamByGameIdInAndStatus(ids, GameTeam.Status.ACTIVE).stream()
                .collect(Collectors.groupingBy(t -> t.getGame().getId()));
        Map<UUID, Long> players = participantRepository.countByGameIds(ids).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
        Map<UUID, AuditLog> cancellations = latestCancellations(ids);
        return games.map(game -> toResponse(game, teams.getOrDefault(game.getId(), List.of()),
                players.getOrDefault(game.getId(), 0L).intValue(), cancellations.get(game.getId())));
    }

    @Transactional(readOnly = true)
    public AdminGameDetailResponse get(UUID gameId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found"));
        List<GameParticipant> participants = participantRepository.findWithUserByGameId(gameId);
        List<GameTeam> teams = gameTeamRepository.findByGameIdAndStatus(gameId, GameTeam.Status.ACTIVE);
        AdminGameResponse summary = toResponse(game, teams, participants.size(),
                latestCancellations(List.of(gameId)).get(gameId));

        AdminGameDetailResponse detail = AdminGameDetailResponse.builder().game(summary).build();
        if (TEAM_VS_TEAM.equals(game.getType())) {
            detail.setTeams(teamGameService.teamsOf(game).stream()
                    .map(team -> AdminGameTeamResponse.builder()
                            .team(team)
                            // A team that left took its players with it (marked cancelled).
                            .players(participants.stream()
                                    .filter(p -> p.getSide() == team.getSide())
                                    .filter(p -> p.getStatus() != GameParticipant.Status.CANCELLED)
                                    .map(adminActivityMapper::toPlayer)
                                    .toList())
                            .build())
                    .toList());
        } else {
            detail.setParticipants(participants.stream().map(adminActivityMapper::toPlayer).toList());
            detail.setServiceFeePerPerson(game.getServiceFeePerPerson());
        }
        return detail;
    }

    private AdminGameResponse toResponse(Game game, List<GameTeam> teams, int playerCount, AuditLog cancellation) {
        AdminGameResponse response = adminActivityMapper.toAdminGame(game);
        if (TEAM_VS_TEAM.equals(game.getType())) {
            for (GameTeam team : teams) {
                if (team.getSide() == GameParticipant.Side.A) {
                    response.setTeamAName(team.getTeam().getName());
                } else if (team.getSide() == GameParticipant.Side.B) {
                    response.setTeamBName(team.getTeam().getName());
                }
            }
        } else {
            response.setCurrentPlayers(playerCount);
            response.setMaxPlayers(game.getMaxPlayers());
        }
        if (game.getStatus() != null && game.getStatus().startsWith("cancelled") && cancellation != null) {
            response.setCancelledAt(cancellation.getCreatedAt());
            response.setCancellationReason(cancellation.getReason());
        }
        return response;
    }

    /** The newest cancelling audit entry of each game, in one query. */
    private Map<UUID, AuditLog> latestCancellations(Collection<UUID> gameIds) {
        return auditLogRepository.findByEntityTypeAndEntityIdInAndActionIn(
                        AuditEntityType.GAME, gameIds, CANCEL_ACTIONS).stream()
                .collect(Collectors.toMap(AuditLog::getEntityId, Function.identity(),
                        (a, b) -> Comparator.comparing(AuditLog::getCreatedAt).compare(a, b) >= 0 ? a : b));
    }
}
