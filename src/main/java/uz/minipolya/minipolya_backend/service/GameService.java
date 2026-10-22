package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.CreateGameRequest;
import uz.minipolya.minipolya_backend.dto.response.GameDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.GameParticipantResponse;
import uz.minipolya.minipolya_backend.dto.response.GameResponse;
import uz.minipolya.minipolya_backend.dto.response.PaymentInitResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.exception.SlotNotAvailableException;
import uz.minipolya.minipolya_backend.mapper.GameMapper;
import uz.minipolya.minipolya_backend.mapper.GameParticipantMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_OPEN = "open";
    private static final String TYPE_TEAM_VS_TEAM = "team_vs_team";
    private static final String TYPE_OPEN = "open";

    private final GameRepository gameRepository;
    private final GameParticipantRepository participantRepository;
    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final PriceCalculator priceCalculator;
    private final BookingRepository bookingRepository;
    private final GameMapper gameMapper;
    private final GameParticipantMapper participantMapper;
    private final AppProperties appProperties;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    @Transactional
    public GameDetailResponse createGame(CreateGameRequest request, UUID creatorUserId) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(request.getStadiumId(), STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not available"));
        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String type = request.getType();
        int duration = request.getDuration();
        OffsetDateTime startsAt = request.getStartsAt();
        OffsetDateTime endsAt = startsAt.plusHours(duration);

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
                .totalPrice(calculatePrice(stadium, startsAt, duration))
                .serviceFeePerPerson(appProperties.getGame().getDefaultServiceFeePerPerson())
                .creatorSharePct(appProperties.getGame().getDefaultCreatorSharePct())
                .isPublic(TYPE_OPEN.equals(type))
                .build();

        GameParticipant.Side creatorSide = null;
        if (TYPE_TEAM_VS_TEAM.equals(type)) {
            if (request.getTeamAId() == null) {
                throw new BadRequestException("teamAId is required for a team_vs_team game");
            }
            Team teamA = teamRepository.findById(request.getTeamAId())
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

        auditService.log(creatorUserId, "GAME_CREATED", "game", saved.getId(), "{\"type\":\"" + type + "\"}");
        log.info("Game created: id={} type={} stadiumId={} creatorId={}",
                saved.getId(), type, stadium.getId(), creatorUserId);
        return toDetail(saved);
    }

    @Transactional
    public GameParticipantResponse joinGame(UUID gameId, UUID userId, String side) {
        Game game = requireGame(gameId);
        if (!STATUS_OPEN.equals(game.getStatus())) {
            throw new BadRequestException("Game is not open for joining");
        }
        if (participantRepository.findByGameIdAndUserId(gameId, userId).isPresent()) {
            throw new BadRequestException("You have already joined this game");
        }

        GameParticipant.Side assignedSide = null;
        if (TYPE_TEAM_VS_TEAM.equals(game.getType())) {
            // A leader joins with an explicit side; invited members join without one (no payment).
            assignedSide = parseSide(side);
        } else {
            long current = participantRepository.findByGameId(gameId).size();
            if (current >= game.getMaxPlayers()) {
                throw new BadRequestException("Game is already full");
            }
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        GameParticipant participant = participantRepository.save(GameParticipant.builder()
                .game(game).user(user).side(assignedSide).status(GameParticipant.Status.JOINED).build());
        safeNotify(() -> notificationService.notify(game.getCreator().getId(),
                Notification.Type.GAME_PLAYER_JOINED, "Новый игрок",
                user.getName() + " присоединился к вашей игре.", Notification.ReferenceType.GAME, game.getId()));
        log.info("Game joined: gameId={} userId={} side={}", gameId, userId, assignedSide);
        return participantMapper.toResponse(participant);
    }

    @Transactional
    public PaymentInitResponse payForGame(UUID gameId, UUID userId, String provider) {
        return paymentService.initiateGamePayment(gameId, provider, userId);
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
            paymentService.refundFullPayment(participant.getPayment().getId(), "Left the game");
        }
        participantRepository.delete(participant);
        safeNotify(() -> notificationService.notify(game.getCreator().getId(),
                Notification.Type.GAME_PLAYER_LEFT, "Игрок вышел",
                participant.getUser().getName() + " покинул вашу игру.",
                Notification.ReferenceType.GAME, game.getId()));
        log.info("Game left: gameId={} userId={}", gameId, userId);
    }

    @Transactional(readOnly = true)
    public GameDetailResponse getGameDetail(UUID gameId, UUID userId) {
        return toDetail(requireGame(gameId));
    }

    @Transactional(readOnly = true)
    public Page<GameResponse> getOpenGames(UUID stadiumId, Pageable pageable) {
        Page<Game> games = stadiumId == null
                ? gameRepository.findByIsPublicTrueAndStatus(STATUS_OPEN, pageable)
                : gameRepository.findByStadiumIdAndIsPublicTrueAndStatus(stadiumId, STATUS_OPEN, pageable);
        return games.map(gameMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<GameResponse> getMyGames(UUID userId, Pageable pageable) {
        return gameRepository.findMyGames(userId, pageable).map(gameMapper::toResponse);
    }

    @Transactional
    public void cancelGame(UUID gameId, UUID userId) {
        Game game = requireGame(gameId);
        if (!game.getCreator().getId().equals(userId)) {
            throw new ForbiddenException("Only the creator can cancel this game");
        }
        if (!STATUS_OPEN.equals(game.getStatus())) {
            throw new BadRequestException("Only an open game can be cancelled");
        }
        game.setStatus("cancelled_by_creator");
        gameRepository.save(game);
        refundPaidParticipants(game, "Game cancelled by creator");
        notifyParticipants(game, Notification.Type.GAME_CANCELLED, "Игра отменена",
                "Игра в «" + game.getStadium().getName() + "» отменена создателем.");
        auditService.log(userId, "GAME_CANCELLED", "game", gameId, "{\"reason\":\"cancelled_by_creator\"}");
        log.info("Game cancelled by creator: id={} userId={}", gameId, userId);
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
            if (overlaps(game.getStartsAt(), game.getEndsAt(), startsAt, endsAt)) {
                game.setStatus("cancelled_slot_taken");
                gameRepository.save(game);
                refundPaidParticipants(game, "Slot taken by another booking");
                notifyParticipants(game, Notification.Type.GAME_CANCELLED, "Игра отменена",
                        "Слот игры в «" + game.getStadium().getName() + "» занят другим бронированием.");
                auditService.log(null, "GAME_CANCELLED", "game", game.getId(), "{\"reason\":\"slot_taken\"}");
                log.info("Game {} cancelled: slot taken on stadium {}", game.getId(), stadiumId);
            }
        }
    }

    /** Called by GameTimeoutJob: cancel open games whose start time has passed. */
    @Transactional
    public int cancelUnfilledGames() {
        List<Game> expired = gameRepository.findByStatusAndStartsAtBefore(
                STATUS_OPEN, OffsetDateTime.now(ZoneOffset.UTC));
        for (Game game : expired) {
            game.setStatus("cancelled_not_filled");
            gameRepository.save(game);
            refundPaidParticipants(game, "Game did not fill in time");
            notifyParticipants(game, Notification.Type.GAME_CANCELLED, "Игра отменена",
                    "Игра в «" + game.getStadium().getName() + "» не набрала игроков вовремя.");
            auditService.log(null, "GAME_CANCELLED", "game", game.getId(), "{\"reason\":\"not_filled\"}");
        }
        if (!expired.isEmpty()) {
            log.info("Cancelled {} unfilled game(s)", expired.size());
        }
        return expired.size();
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
                .gameId(game.getId())
                .build();
        try {
            Booking saved = bookingRepository.saveAndFlush(booking);
            game.setBookingId(saved.getId());
            game.setStatus("confirmed");
            assignSides(game);
            gameRepository.save(game);
            notifyParticipants(game, Notification.Type.GAME_CONFIRMED, "Игра подтверждена",
                    "Игра в «" + game.getStadium().getName() + "» подтверждена. Слот забронирован.");
            auditService.log(game.getCreator().getId(), "GAME_CONFIRMED", "game", game.getId());
            log.info("Game confirmed: id={} bookingId={}", game.getId(), saved.getId());
        } catch (DataIntegrityViolationException ex) {
            game.setStatus("cancelled_slot_taken");
            gameRepository.save(game);
            refundPaidParticipants(game, "Slot was taken before the game was confirmed");
            log.info("Game {} could not get its slot and was cancelled", game.getId());
        }
    }

    private boolean isFullyFunded(Game game) {
        if (TYPE_TEAM_VS_TEAM.equals(game.getType())) {
            return sideHasPaid(game.getId(), GameParticipant.Side.A)
                    && sideHasPaid(game.getId(), GameParticipant.Side.B);
        }
        return participantRepository.countByGameIdAndStatus(game.getId(), GameParticipant.Status.PAID)
                >= game.getMaxPlayers();
    }

    private boolean sideHasPaid(UUID gameId, GameParticipant.Side side) {
        return participantRepository.findByGameIdAndSide(gameId, side).stream()
                .anyMatch(p -> p.getStatus() == GameParticipant.Status.PAID);
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
                paymentService.refundFullPayment(participant.getPayment().getId(), reason);
                participant.setStatus(GameParticipant.Status.REFUNDED);
                participantRepository.save(participant);
            }
        }
    }

    private GameDetailResponse toDetail(Game game) {
        GameDetailResponse dto = gameMapper.toDetailResponse(game);
        List<GameParticipant> participants = participantRepository.findByGameId(game.getId());
        dto.setParticipants(participants.stream().map(participantMapper::toResponse).toList());
        dto.setCurrentPlayers(participants.size());
        dto.setInviteLink(inviteLink(game.getId()));
        return dto;
    }

    private String inviteLink(UUID gameId) {
        return "t.me/" + appProperties.getBot().getUsername() + "?start=game_" + gameId;
    }

    private int calculatePrice(Stadium stadium, OffsetDateTime startsAt, int duration) {
        ZonedDateTime local = startsAt.atZoneSameInstant(TASHKENT);
        return priceCalculator.calculateTotalPrice(
                pricingRuleRepository.findByStadiumIdAndIsActiveTrue(stadium.getId()),
                local.toLocalDate(),
                local.toLocalTime(),
                duration,
                stadium.getPricePerHour());
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
    private void safeNotify(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("Notification failed: {}", ex.toString());
        }
    }
}
