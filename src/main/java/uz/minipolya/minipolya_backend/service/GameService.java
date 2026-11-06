package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.request.CreateGameRequest;
import uz.minipolya.minipolya_backend.dto.response.GameChallengeResponse;
import uz.minipolya.minipolya_backend.dto.response.GameDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.GameParticipantResponse;
import uz.minipolya.minipolya_backend.dto.response.GameResponse;
import uz.minipolya.minipolya_backend.dto.response.PaymentInitResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.CardDeclinedException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.exception.SlotNotAvailableException;
import uz.minipolya.minipolya_backend.mapper.GameMapper;
import uz.minipolya.minipolya_backend.mapper.GameParticipantMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Games across the three flows (casual, open, team_vs_team). A game holds no
 * slot until it is fully funded — at that point a confirmed {@code source=game}
 * booking is created. The {@code bookings_no_overlap} EXCLUDE constraint still
 * guards that auto-booking; if the slot was taken meanwhile, the game is
 * cancelled and participants refunded.
 *
 * <p>Creates game bookings via {@link BookingRepository} directly (not
 * BookingService) to keep the dependency graph acyclic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_OPEN = "open";
    private static final String TYPE_TEAM_VS_TEAM = "team_vs_team";
    static final String ONLY_LEADERS_JOIN =
            "Only the joining team's leader can join a team game, with their team and a card";
    private static final String TYPE_OPEN = "open";

    private final GameRepository gameRepository;
    private final GameParticipantRepository participantRepository;
    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final BookingPriceCalculator bookingPriceCalculator;
    private final BookingRepository bookingRepository;
    private final GameMapper gameMapper;
    private final GameParticipantMapper participantMapper;
    private final AppProperties appProperties;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final BookingWindowValidator bookingWindowValidator;
    private final PlatformSettingsService platformSettingsService;
    private final TeamGameService teamGameService;
    private final SavedCardService savedCardService;
    /** For the joins that must not run inside one big transaction (see {@link #joinGame}). */
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Transactional
    public GameDetailResponse createGame(CreateGameRequest request, UUID creatorUserId) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(request.getStadiumId(), STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not available"));
        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String type = request.getType();
        // A game is one slot, like every booking.
        BookingWindowValidator.requireOneHour(request.getDuration());
        OffsetDateTime startsAt = request.getStartsAt();
        OffsetDateTime endsAt = startsAt.plusHours(Booking.HOURS);

        // A confirmed game becomes a source=game booking, so the slot must already
        // satisfy the booking window rules (on the hour, inside open-close, no closure).
        bookingWindowValidator.validate(stadium, startsAt);

        if (bookingRepository.existsActiveOverlap(stadium.getId(), startsAt, endsAt)) {
            throw new SlotNotAvailableException("That time slot is already booked");
        }

        Game game = Game.builder()
                .creator(creator)
                .stadium(stadium)
                .type(type)
                .status(STATUS_OPEN)
                .title(request.getTitle())
                .maxPlayers(request.getMaxPlayers())
                .startsAt(startsAt)
                .endsAt(endsAt)
                // Same calculation as a direct booking, promotions included, so a game
                // costs exactly what booking the same slot costs.
                .totalPrice(bookingPriceCalculator.hourPrice(stadium, startsAt))
                // Fees are platform settings, not static config: a team game is charged the
                // per-team platform fee, any other game the per-player service fee.
                .serviceFeePerPerson(serviceFeeFor(type))
                .isPublic(TYPE_OPEN.equals(type))
                .build();

        GameParticipant.Side creatorSide = null;
        Team teamA = null;
        if (TYPE_TEAM_VS_TEAM.equals(type)) {
            if (request.getTeamAId() == null) {
                throw new BadRequestException("teamAId is required for a team_vs_team game");
            }
            teamA = teamRepository.findById(request.getTeamAId())
                    .orElseThrow(() -> new ResourceNotFoundException("Team not found"));
            if (!teamA.getLeader().getId().equals(creatorUserId)) {
                throw new ForbiddenException("Only the team leader can create a team game");
            }
            game.setTeamA(teamA);
            creatorSide = GameParticipant.Side.A;
        }

        Game saved = gameRepository.save(game);
        participantRepository.save(GameParticipant.builder()
                .game(saved).user(creator).side(creatorSide).status(GameParticipant.Status.JOINED).build());

        // A team game is paid for as it is created: the creating team's fee and
        // advance go on hold. A declined card throws, rolling the game back.
        if (TYPE_TEAM_VS_TEAM.equals(type)) {
            teamGameService.createWaitingGame(saved, teamA, creator, request.getCardId(),
                    teamOption(request.getPaymentOption()));
        }

        auditService.record(AuditEntry.player(creatorUserId, AuditAction.GAME_CREATED)
                .entity(AuditEntityType.GAME, saved.getId(), AuditLabels.game(saved))
                .created("type", saved.getType())
                .created("status", saved.getStatus())
                .created("startsAt", saved.getStartsAt())
                .created("endsAt", saved.getEndsAt())
                .created("maxPlayers", saved.getMaxPlayers())
                .created("totalPrice", saved.getTotalPrice())
                .created("teamA", saved.getTeamA() == null ? null : saved.getTeamA().getName()));
        log.info("Game created: id={} type={} stadiumId={} creatorId={}",
                saved.getId(), type, stadium.getId(), creatorUserId);
        return toDetail(saved);
    }

    /**
     * Joins a game. Deliberately not {@code @Transactional}: a team game's join commits after
     * each of its steps (see {@link TeamGameService#join}), which a surrounding transaction
     * would undo all together; a casual join is one transaction of its own.
     */
    public GameParticipantResponse joinGame(UUID gameId, UUID userId, String side, UUID teamId, UUID cardId) {
        return joinGame(gameId, userId, side, teamId, cardId, null);
    }

    /** As above, with a team's payment option (T6; null = minimum). */
    public GameParticipantResponse joinGame(UUID gameId, UUID userId, String side, UUID teamId, UUID cardId,
                                            uz.minipolya.minipolya_backend.dto.request.PaymentOption option) {
        Game game = requireGame(gameId);
        if (!STATUS_OPEN.equals(game.getStatus())) {
            throw new BadRequestException("Game is not open for joining");
        }
        if (participantRepository.findByGameIdAndUserId(gameId, userId).isPresent()) {
            throw new BadRequestException("You have already joined this game");
        }
        if (TYPE_TEAM_VS_TEAM.equals(game.getType())) {
            return joinTeamGame(game, userId, side, teamId, cardId, teamOption(option));
        }
        return inTransaction(() -> joinCasualGame(gameId, userId));
    }

    /**
     * The server decides, never the client's side: only the joining team's leader joins a
     * team game, always on the free side, and always pays. Nobody joins the other side as a
     * plain, non-paying member.
     */
    /** The request's option as a team stores it; absent means minimum (T6). */
    static GameTeam.PaymentOption teamOption(uz.minipolya.minipolya_backend.dto.request.PaymentOption option) {
        return option == uz.minipolya.minipolya_backend.dto.request.PaymentOption.FULL
                ? GameTeam.PaymentOption.FULL
                : GameTeam.PaymentOption.MINIMUM;
    }

    private GameParticipantResponse joinTeamGame(Game game, UUID userId, String side, UUID teamId, UUID cardId,
                                                 GameTeam.PaymentOption option) {
        Team joiningTeam = teamId == null ? null : teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found"));
        if (joiningTeam == null || !joiningTeam.getLeader().getId().equals(userId)) {
            throw new ForbiddenException(ONLY_LEADERS_JOIN);
        }
        if (cardId == null) {
            throw new BadRequestException("cardId is required to join a team game");
        }
        GameParticipant.Side free = teamGameService.freeSide(game);
        GameParticipant.Side requested = parseSide(side);
        // "b" stays accepted from older clients, which always sent it for the opponent.
        if (requested != null && requested != free && requested != GameParticipant.Side.B) {
            throw new BadRequestException("The opposing team joins as side " + free.getValue());
        }
        User leader = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        // Books the slot, charges the team, captures the waiting team's hold and confirms
        // the game — the leader's participant row, notifications and audit included.
        teamGameService.join(game, joiningTeam, leader, cardId, option);
        // The game has just taken the slot, so any other team still waiting on it must be
        // cleared and refunded — the same rule an ordinary booking follows. This game is
        // 'confirmed' by now, so it cannot cancel itself.
        inTransaction(() -> {
            cancelGamesOnSlot(game.getStadium().getId(), game.getStartsAt(), game.getEndsAt());
            return null;
        });
        log.info("Game joined: gameId={} userId={} team={}", game.getId(), userId, joiningTeam.getName());
        return participantMapper.toResponse(participantRepository.findByGameIdAndUserId(game.getId(), userId)
                .orElseThrow(() -> new IllegalStateException("Confirmed join without its participant")));
    }

    /**
     * The reconcile job's second pass: joins left half-way (a crash, an unclear answer)
     * since before {@code startedBefore} are carried on to confirmed or undone.
     *
     * @return how many were looked at
     */
    public int resumeStuckJoins(OffsetDateTime startedBefore) {
        List<UUID> stuck = teamGameService.stuckJoins(startedBefore);
        for (UUID gameTeamId : stuck) {
            try {
                if (teamGameService.resumeJoin(gameTeamId) == TeamGameService.JoinState.CONFIRMED) {
                    inTransaction(() -> {
                        Game game = gameRepository.findById(teamGameService.gameIdOf(gameTeamId)).orElseThrow();
                        cancelGamesOnSlot(game.getStadium().getId(), game.getStartsAt(), game.getEndsAt());
                        return null;
                    });
                }
            } catch (RuntimeException ex) {
                log.error("Could not resume join {}: {}", gameTeamId, ex.toString());
            }
        }
        return stuck.size();
    }

    private GameParticipantResponse joinCasualGame(UUID gameId, UUID userId) {
        Game game = requireGame(gameId);
        GameParticipant.Side assignedSide = null;
        String joinedTeam = null;
        long playersBefore = participantRepository.findByGameId(gameId).size();
        if (playersBefore >= game.getMaxPlayers()) {
            throw new BadRequestException("Game is already full");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        GameParticipant participant = participantRepository.save(GameParticipant.builder()
                .game(game).user(user).side(assignedSide).status(GameParticipant.Status.JOINED).build());
        safeNotify(() -> notificationService.notify(game.getCreator().getId(),
                Notification.Type.GAME_PLAYER_JOINED, "Новый игрок",
                user.getName() + " присоединился к вашей игре.", Notification.ReferenceType.GAME, game.getId()));
        auditService.record(AuditEntry.player(userId, AuditAction.GAME_JOINED)
                .entity(AuditEntityType.GAME, gameId, AuditLabels.game(game))
                .change("players", playersBefore, playersBefore + 1)
                .created("side", assignedSide)
                .created("team", joinedTeam));
        log.info("Game joined: gameId={} userId={} side={}", gameId, userId, assignedSide);
        return participantMapper.toResponse(participant);
    }

    /** Waiting team games an opponent could still join. */
    @Transactional(readOnly = true)
    public Page<GameChallengeResponse> getChallenges(UUID userId, LocalDate date, String format,
                                                     UUID stadiumId, Pageable pageable) {
        return teamGameService.challenges(userId, date, format, stadiumId, pageable);
    }

    /** A team leader settles their own team's remainder from a saved card. */
    @Transactional(noRollbackFor = uz.minipolya.minipolya_backend.exception.PaymentStepFailedException.class)
    public void payTeamRemainder(UUID gameId, UUID userId, UUID cardId) {
        teamGameService.payRemainder(gameId, userId, cardId);
    }

    /** The stadium owner marks one team's remainder as received in cash. */
    @Transactional
    public void recordTeamCashRemainder(UUID gameId, UUID ownerUserId, String side) {
        teamGameService.recordCashRemainder(gameId, ownerUserId, parseSide(side));
    }

    /** T10: the stadium owner marks one team's remainder as not paid. */
    @Transactional
    public void markTeamNotPaid(UUID gameId, UUID ownerUserId, String side) {
        teamGameService.markNotPaid(gameId, ownerUserId, parseSide(side));
    }

    /** T10: the stadium owner undoes their mark on one team's remainder (same Tashkent day). */
    @Transactional
    public void undoTeamRemainderMark(UUID gameId, UUID ownerUserId, String side) {
        teamGameService.undoRemainderMarkByOwner(gameId, ownerUserId, parseSide(side));
    }

    /** T10: an admin undoes the mark on one team's remainder, at any time. */
    @Transactional
    public void undoTeamRemainderMarkByAdmin(UUID gameId, UUID adminUserId, String side) {
        teamGameService.undoRemainderMarkByAdmin(gameId, adminUserId, parseSide(side));
    }

    /**
     * Casual games only — the hosted checkout. A team game is paid for when it
     * is created and when an opponent joins, from a saved card.
     */
    @Transactional
    public PaymentInitResponse payForGame(UUID gameId, UUID userId, String provider) {
        return paymentService.initiateGamePayment(gameId, provider, userId);
    }

    /**
     * A casual player pays their share from a saved card through ATMOS: charged now,
     * grossed up by the card fee, and reversed in full if the game is called off. The
     * existing rule is unchanged — each player pays as they go, and the slot is booked once
     * everyone has; nothing is held.
     */
    @Transactional
    public PaymentInitResponse payForGameByCard(UUID gameId, UUID userId, UUID cardId) {
        SavedCard card = savedCardService.requireOwnedCard(userId, cardId);
        Payment payment = paymentService.chargeGameShare(gameId, userId, card);
        if (payment.getStatus() == Payment.Status.FAILED) {
            throw CardDeclinedException.of(payment, "Card payment was declined");
        }
        return PaymentInitResponse.builder()
                .paymentId(payment.getId())
                .provider(Payment.Provider.ATMOS.getValue())
                .status(payment.getStatus().getValue())
                .build();
    }

    @Transactional
    public void leaveGame(UUID gameId, UUID userId) {
        Game game = requireGame(gameId);
        if (!STATUS_OPEN.equals(game.getStatus())) {
            throw new BadRequestException("You can only leave a game that is still open");
        }
        if (game.getCreator().getId().equals(userId)) {
            throw new BadRequestException("The creator should cancel the game instead of leaving");
        }
        GameParticipant participant = participantRepository.findByGameIdAndUserId(gameId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("You are not in this game"));
        if (participant.getStatus() == GameParticipant.Status.PAID && participant.getPayment() != null) {
            paymentService.refundInFull(participant.getPayment(), "Left the game");
        }
        long playersBefore = participantRepository.findByGameId(gameId).size();
        participantRepository.delete(participant);
        auditService.record(AuditEntry.player(userId, AuditAction.GAME_LEFT)
                .entity(AuditEntityType.GAME, gameId, AuditLabels.game(game))
                .change("players", playersBefore, playersBefore - 1));
        safeNotify(() -> notificationService.notify(game.getCreator().getId(),
                Notification.Type.GAME_PLAYER_LEFT, "Игрок вышел",
                participant.getUser().getName() + " покинул вашу игру.",
                Notification.ReferenceType.GAME, game.getId()));
        log.info("Game left: gameId={} userId={}", gameId, userId);
    }

    @Transactional(readOnly = true)
    public GameDetailResponse getGameDetail(UUID gameId, UUID userId) {
        return toDetail(requireGame(gameId), userId);
    }

    /**
     * T7: {@code GET /api/stadiums/{id}/team-game-quote?startsAt=} — what the creating team may
     * pay for a team game at this hour (side A). The hour must be bookable as a game would be:
     * on the hour, inside opening hours, no closure, free, in the future — else 400; an unknown
     * or unavailable stadium 404.
     */
    @Transactional(readOnly = true)
    public uz.minipolya.minipolya_backend.dto.response.TeamGameQuoteResponse teamGameQuote(UUID stadiumId,
                                                                                         OffsetDateTime startsAt) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(stadiumId, STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (startsAt == null) {
            throw new BadRequestException("startsAt is required");
        }
        if (!startsAt.isAfter(OffsetDateTime.now(java.time.ZoneOffset.UTC))) {
            throw new BadRequestException("startsAt must be in the future");
        }
        bookingWindowValidator.validate(stadium, startsAt);
        if (bookingRepository.existsActiveOverlap(stadium.getId(), startsAt, startsAt.plusHours(Booking.HOURS))) {
            throw new BadRequestException("That time slot is already booked");
        }
        long price = bookingPriceCalculator.hourPrice(stadium, startsAt);
        return teamGameService.quoteResponse(price, stadium, GameParticipant.Side.A, startsAt);
    }

    /** T7: {@code GET /api/games/{id}/join-quote} — see {@link TeamGameService#joinQuote}. */
    public uz.minipolya.minipolya_backend.dto.response.TeamGameQuoteResponse joinQuote(UUID gameId) {
        return teamGameService.joinQuote(gameId);
    }

    @Transactional(readOnly = true)
    public Page<GameResponse> getOpenGames(UUID stadiumId, Pageable pageable) {
        Page<Game> games = stadiumId == null
                ? gameRepository.findByIsPublicTrueAndStatus(STATUS_OPEN, pageable)
                : gameRepository.findByStadiumIdAndIsPublicTrueAndStatus(stadiumId, STATUS_OPEN, pageable);
        return games.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<GameResponse> getMyGames(UUID userId, Pageable pageable) {
        return gameRepository.findMyGames(userId, pageable).map(this::toResponse);
    }

    @Transactional
    public void cancelGame(UUID gameId, UUID userId) {
        Game game = requireGame(gameId);
        if (TYPE_TEAM_VS_TEAM.equals(game.getType())) {
            // Either leader may pull their team out, waiting or confirmed, and
            // what that costs them depends on how close kick-off is.
            teamGameService.cancelByLeader(game, userId);
            return;
        }
        if (!game.getCreator().getId().equals(userId)) {
            throw new ForbiddenException("Only the creator can cancel this game");
        }
        if (!STATUS_OPEN.equals(game.getStatus())) {
            throw new BadRequestException("Only an open game can be cancelled");
        }
        String oldStatus = game.getStatus();
        game.setStatus("cancelled_by_creator");
        gameRepository.save(game);
        refundPaidParticipants(game, "Game cancelled by creator");
        auditService.record(AuditEntry.player(userId, AuditAction.GAME_CANCELLED)
                .entity(AuditEntityType.GAME, gameId, AuditLabels.game(game))
                .change("status", oldStatus, game.getStatus()));
        notifyParticipants(game, Notification.Type.GAME_CANCELLED, "Игра отменена",
                "Игра в «" + game.getStadium().getName() + "» отменена создателем.");
        log.info("Game cancelled by creator: id={} userId={}", gameId, userId);
    }

    /**
     * Called when the stadium owner cancels a {@code source = game} booking, or
     * an admin blocks the stadium. The booking is not where a team game's money
     * lives, so the game itself has to give it back.
     *
     * @return the tiyin the game gave back, or null when there is no such game
     *         and the caller should fall back to refunding the booking itself
     */
    @Transactional
    public Long cancelGameForBooking(UUID gameId, String reason) {
        Game game = gameRepository.findById(gameId).orElse(null);
        if (game == null) {
            return null;
        }
        if (TYPE_TEAM_VS_TEAM.equals(game.getType())) {
            return teamGameService.cancelForBookingCancelled(gameId, reason);
        }
        if (STATUS_OPEN.equals(game.getStatus()) || "confirmed".equals(game.getStatus())) {
            String oldStatus = game.getStatus();
            game.setStatus("cancelled_slot_taken");
            gameRepository.save(game);
            refundPaidParticipants(game, reason);
            auditAutoCancelled(game, oldStatus, reason);
            notifyParticipants(game, Notification.Type.GAME_CANCELLED, "Игра отменена",
                    "Игра в «" + game.getStadium().getName() + "» отменена.");
        }
        // A casual game's refunds go through its participants' own payment
        // rows, which the booking does not own either.
        return 0L;
    }

    /** Called by PaymentService when a game share payment completes. */
    @Transactional
    public void handleGamePaymentConfirmed(UUID gameId, UUID userId) {
        Game game = requireGame(gameId);
        GameParticipant participant = participantRepository.findByGameIdAndUserId(gameId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found"));
        participant.setStatus(GameParticipant.Status.PAID);
        participantRepository.save(participant);

        if (STATUS_OPEN.equals(game.getStatus()) && isFullyFunded(game)) {
            confirmGame(game);
        }
    }

    /** Called by BookingService when a regular booking takes a slot. */
    @Transactional
    public void cancelGamesOnSlot(UUID stadiumId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        for (Game game : gameRepository.findByStadiumIdAndStatus(stadiumId, STATUS_OPEN)) {
            if (!overlaps(game.getStartsAt(), game.getEndsAt(), startsAt, endsAt)) {
                continue;
            }
            if (TYPE_TEAM_VS_TEAM.equals(game.getType())) {
                // A team's money hangs off game_teams, so the team flow has to
                // give it back — the participant rows hold none of it.
                teamGameService.endWaitingGame(game, "cancelled_slot_taken",
                        "Slot taken by another booking");
                continue;
            }
            String oldStatus = game.getStatus();
            game.setStatus("cancelled_slot_taken");
            gameRepository.save(game);
            refundPaidParticipants(game, "Slot taken by another booking");
            auditAutoCancelled(game, oldStatus, "Slot taken by another booking");
            notifyParticipants(game, Notification.Type.GAME_CANCELLED, "Игра отменена",
                    "Слот игры в «" + game.getStadium().getName() + "» занят другим бронированием.");
            log.info("Game {} cancelled: slot taken on stadium {}", game.getId(), stadiumId);
        }
    }

    /**
     * Called by GameTimeoutJob. Two cutoffs, because the two kinds of game run
     * out of time at different moments: a team game is over once no opponent
     * could still arrive in time — the 3-hour point — while a casual game can
     * fill right up to kick-off.
     */
    @Transactional
    public int cancelUnfilledGames() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        List<Game> teamGames = gameRepository.findByStatusAndTypeAndStartsAtBefore(
                STATUS_OPEN, TYPE_TEAM_VS_TEAM,
                now.plusHours(appProperties.getGame().getOpponentDeadlineHours()));
        for (Game game : teamGames) {
            teamGameService.endWaitingGame(game, "cancelled_not_filled",
                    "No opponent was found in time");
        }

        List<Game> others = gameRepository.findByStatusAndTypeNotAndStartsAtBefore(
                STATUS_OPEN, TYPE_TEAM_VS_TEAM, now);
        for (Game game : others) {
            String oldStatus = game.getStatus();
            game.setStatus("cancelled_not_filled");
            gameRepository.save(game);
            refundPaidParticipants(game, "Game did not fill in time");
            auditAutoCancelled(game, oldStatus, "Game did not fill in time");
            notifyParticipants(game, Notification.Type.GAME_CANCELLED, "Игра отменена",
                    "Игра в «" + game.getStadium().getName() + "» не набрала игроков вовремя.");
        }

        int cancelled = teamGames.size() + others.size();
        if (cancelled > 0) {
            log.info("Cancelled {} unfilled game(s)", cancelled);
        }
        return cancelled;
    }

    // ---- Helpers ----

    private void confirmGame(Game game) {
        Booking booking = Booking.builder()
                .stadium(game.getStadium())
                .user(game.getCreator())
                .source("game")
                .status("confirmed")
                .startsAt(game.getStartsAt())
                .endsAt(game.getEndsAt())
                .durationHours((int) Duration.between(game.getStartsAt(), game.getEndsAt()).toHours())
                .price(game.getTotalPrice())
                // A game's money is its players'; the booking keeps the owner's terms.
                .pricingModel(Booking.PricingModel.NET)
                .gameId(game.getId())
                .build();
        try {
            Booking saved = bookingRepository.saveAndFlush(booking);
            String oldStatus = game.getStatus();
            game.setBookingId(saved.getId());
            game.setStatus("confirmed");
            assignSides(game);
            gameRepository.save(game);
            notifyParticipants(game, Notification.Type.GAME_CONFIRMED, "Игра подтверждена",
                    "Игра в «" + game.getStadium().getName() + "» подтверждена. Слот забронирован.");
            auditService.record(AuditEntry.system(AuditAction.GAME_CONFIRMED)
                    .entity(AuditEntityType.GAME, game.getId(), AuditLabels.game(game))
                    .change("status", oldStatus, game.getStatus())
                    .created("bookingId", saved.getId())
                    .reason("Every player has paid"));
            log.info("Game confirmed: id={} bookingId={}", game.getId(), saved.getId());
        } catch (DataIntegrityViolationException ex) {
            String oldStatus = game.getStatus();
            game.setStatus("cancelled_slot_taken");
            gameRepository.save(game);
            refundPaidParticipants(game, "Slot was taken before the game was confirmed");
            auditAutoCancelled(game, oldStatus, "Slot was taken before the game was confirmed");
            log.info("Game {} could not get its slot and was cancelled", game.getId());
        }
    }

    /** Team games are charged the per-team platform fee; everything else the casual service fee. */
    /** A casual game the system had to call off: a SYSTEM entry, never one of the players'. */
    private void auditAutoCancelled(Game game, String oldStatus, String reason) {
        auditService.record(AuditEntry.system(AuditAction.GAME_AUTO_CANCELLED)
                .entity(AuditEntityType.GAME, game.getId(), AuditLabels.game(game))
                .change("status", oldStatus, game.getStatus())
                .reason(reason));
    }

    private long serviceFeeFor(String type) {
        PlatformSettings settings = platformSettingsService.current();
        return TYPE_TEAM_VS_TEAM.equals(type)
                ? settings.getTeamGamePlatformFee()
                : settings.getCasualGameServiceFee();
    }

    /** Casual games only: a team game is funded per team, by TeamGameService. */
    private boolean isFullyFunded(Game game) {
        return participantRepository.countByGameIdAndStatus(game.getId(), GameParticipant.Status.PAID)
                >= game.getMaxPlayers();
    }

    private void assignSides(Game game) {
        if (TYPE_TEAM_VS_TEAM.equals(game.getType())) {
            return; // sides already set by team
        }
        List<GameParticipant> participants = participantRepository.findByGameId(game.getId());
        int index = 0;
        for (GameParticipant participant : participants) {
            participant.setSide(index % 2 == 0 ? GameParticipant.Side.A : GameParticipant.Side.B);
            participantRepository.save(participant);
            index++;
        }
    }

    private void refundPaidParticipants(Game game, String reason) {
        for (GameParticipant participant : participantRepository.findByGameId(game.getId())) {
            if (participant.getStatus() == GameParticipant.Status.PAID && participant.getPayment() != null) {
                paymentService.refundInFull(participant.getPayment(), reason);
                participant.setStatus(GameParticipant.Status.REFUNDED);
                participantRepository.save(participant);
            }
        }
    }

    /** The player price with the settings in force now; a quote never asks the payment service. */
    private CardPricing cardPricing() {
        return new CardPricing(platformSettingsService::current);
    }

    private GameDetailResponse toDetail(Game game) {
        return toDetail(game, null);
    }

    /** The detail as one viewer sees it (the stadium's owner gets the mark's undo deadline, T9). */
    private GameDetailResponse toDetail(Game game, UUID viewerId) {
        GameDetailResponse dto = gameMapper.toDetailResponse(game);
        List<GameParticipant> participants = participantRepository.findByGameId(game.getId());
        dto.setParticipants(participants.stream().map(participantMapper::toResponse).toList());
        dto.setCurrentPlayers(participants.size());
        dto.setInviteLink(inviteLink(game.getId()));
        if (TYPE_TEAM_VS_TEAM.equals(game.getType())) {
            dto.setTeams(viewerId == null ? teamGameService.teamsOf(game) : teamGameService.teamsOf(game, viewerId));
            dto.setJoinPending(teamGameService.joinPending(game.getId()));
        } else if (game.getTotalPrice() != null) {
            dto.setPrice(cardPricing().gameShareSplit(game).playerAmount());
        }
        dto.setTotalPrice(playerTotal(game));
        return dto;
    }

    /** A game in a player's list: its total as the players pay it. */
    private GameResponse toResponse(Game game) {
        GameResponse dto = gameMapper.toResponse(game);
        dto.setTotalPrice(playerTotal(game));
        return dto;
    }

    /**
     * What the players pay for a game altogether: every share of a casual or open game,
     * both teams' joining prices and remainders of a team game. Null without a price.
     */
    private Long playerTotal(Game game) {
        if (game.getTotalPrice() == null) {
            return null;
        }
        if (TYPE_TEAM_VS_TEAM.equals(game.getType())) {
            return teamGameService.playerTotal(game);
        }
        int players = game.getMaxPlayers() == null || game.getMaxPlayers() < 1 ? 1 : game.getMaxPlayers();
        return cardPricing().gameShareSplit(game).playerAmount() * players;
    }

    /**
     * Telegram deep link for inviting friends, or null when {@code app.bot.username}
     * is not configured — a link to "t.me/null" is worse than no link at all, and
     * GameDetailResponse omits null fields.
     */
    private String inviteLink(UUID gameId) {
        String username = appProperties.getBot().getUsername();
        if (username == null || username.isBlank()) {
            log.warn("app.bot.username (BOT_USERNAME) is not configured; game invite link omitted");
            return null;
        }
        return "t.me/" + username + "?start=game_" + gameId;
    }

    private GameParticipant.Side parseSide(String side) {
        if (side == null) {
            return null;
        }
        try {
            return GameParticipant.Side.fromValue(side);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("side must be 'a' or 'b'");
        }
    }

    private static boolean overlaps(OffsetDateTime aStart, OffsetDateTime aEnd,
                                    OffsetDateTime bStart, OffsetDateTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    private Game requireGame(UUID gameId) {
        return gameRepository.findById(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found"));
    }

    private void notifyParticipants(Game game, Notification.Type type, String title, String message) {
        for (GameParticipant participant : participantRepository.findByGameId(game.getId())) {
            if (participant.getUser() != null) {
                UUID uid = participant.getUser().getId();
                safeNotify(() -> notificationService.notify(uid, type, title, message,
                        Notification.ReferenceType.GAME, game.getId()));
            }
        }
    }

    /** Notifications are best-effort — a failure must never break the game flow. */
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> work.get());
    }

    private void safeNotify(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("Notification failed: {}", ex.toString());
        }
    }
}
