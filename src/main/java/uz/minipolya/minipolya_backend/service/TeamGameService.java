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
import uz.minipolya.minipolya_backend.dto.response.GameChallengeResponse;
import uz.minipolya.minipolya_backend.dto.response.GameTeamResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.exception.SlotAlreadyBookedException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.repository.TeamMemberRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;
import uz.minipolya.minipolya_backend.util.TeamGameSplit;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The money side of a team-vs-team game, which does not look like any other
 * game flow and so lives apart from {@link GameService}.
 *
 * <p>A team game has three states. <b>WAITING</b> — one team in, looking for an
 * opponent; the slot is <em>not</em> booked and stays free for everyone, and
 * the waiting team's money is only on hold. <b>CONFIRMED</b> — two teams in and
 * a {@code source = game} booking exists. <b>CANCELLED</b>. A confirmed game
 * can fall back to WAITING when one team leaves, which is why a team's money
 * hangs off a {@link GameTeam} row rather than off the booking: the booking is
 * cancelled and later recreated, while the remaining team's payments stay put.
 *
 * <p>Each team pays the platform fee plus an owner advance in the app when it
 * joins, and settles its remainder later — see {@link TeamGameSplit} for the
 * arithmetic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamGameService {

    /** Fixed copy: an opponent arriving inside the window could not get there in time. */
    static final String TOO_LATE_TO_JOIN =
            "O'yin boshlanishiga 3 soatdan kam qoldi, qo'shilib bo'lmaydi";

    /** Shown to the team that is still in after its opponent walked out. */
    static final String OPPONENT_LEFT = "Raqib bekor qildi, yangi raqib qidirilmoqda";

    /** Date filters are Tashkent days; games are stored in UTC. */
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    /**
     * Stand-in id for "no filter" / "no team". The query never receives a null
     * uuid, and no real row can carry this one.
     */
    private static final UUID NO_TEAM = new UUID(0L, 0L);

    /** How far ahead an unfiltered challenge search looks. */
    private static final int FAR_FUTURE_YEARS = 5;

    private static final String STATUS_WAITING = "open";
    private static final String STATUS_CONFIRMED = "confirmed";

    private final GameRepository gameRepository;
    private final GameTeamRepository gameTeamRepository;
    private final GameParticipantRepository participantRepository;
    private final BookingRepository bookingRepository;
    private final PaymentService paymentService;
    private final SavedCardService savedCardService;
    private final PlatformSettingsService platformSettingsService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final CancellationPolicy cancellationPolicy;
    private final AppProperties appProperties;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;

    /**
     * Puts the creating team on side A and holds its fee and advance. No
     * booking is written: a waiting game holds no slot.
     *
     * @throws BadRequestException when no card was given or the hold is declined —
     *                             either way the surrounding transaction rolls back
     *                             and no game survives
     */
    @Transactional
    public GameTeam createWaitingGame(Game game, Team teamA, User leader, UUID cardId) {
        if (cardId == null) {
            throw new BadRequestException("cardId is required to create a team game");
        }
        SavedCard card = savedCardService.requireOwnedCard(leader.getId(), cardId);
        int holdMinutes = holdMinutes(game, OffsetDateTime.now(ZoneOffset.UTC));

        GameTeam sideA = gameTeamRepository.saveAndFlush(
                teamEntry(game, teamA, leader, Side.A, splitFor(game)));

        Payment hold = paymentService.holdForTeamGame(sideA, card, holdMinutes);
        if (hold.getStatus() == Payment.Status.FAILED) {
            throw new BadRequestException("Card payment was declined; the game was not created");
        }

        log.info("Team game created: gameId={} teamId={} held={}",
                game.getId(), teamA.getId(), sideA.getAdvanceAmount() + sideA.getFeeAmount());
        return sideA;
    }

    /**
     * An opponent takes the free side of a waiting game. This is where the slot
     * is finally claimed, so the order is load-bearing:
     *
     * <ol>
     *   <li>record the joining team, so the partial unique index rejects a
     *       second opponent before anyone is charged;</li>
     *   <li>book the slot — if the overlap constraint rejects it, the slot has
     *       gone and the game is over: the waiting team gets everything back
     *       and the joining team is never charged;</li>
     *   <li>charge the joining team — if the card declines, the booking is
     *       removed again and the game goes on waiting;</li>
     *   <li>capture the waiting team's hold, last, because it is the only step
     *       that cannot be undone cheaply;</li>
     *   <li>confirm the booking and the game.</li>
     * </ol>
     */
    @Transactional(noRollbackFor = uz.minipolya.minipolya_backend.exception.PaymentStepFailedException.class)
    public GameTeam join(Game game, Team teamB, User leader, UUID cardId) {
        if (teamB == null) {
            throw new BadRequestException("teamId is required to join as team B");
        }
        if (cardId == null) {
            throw new BadRequestException("cardId is required to join a team game");
        }
        if (!teamB.getLeader().getId().equals(leader.getId())) {
            throw new ForbiddenException("Only the team leader can accept");
        }
        if (game.getTeamA() != null && game.getTeamA().getId().equals(teamB.getId())) {
            throw new BadRequestException("A team cannot play against itself");
        }
        if (OffsetDateTime.now(ZoneOffset.UTC).isAfter(opponentDeadline(game))) {
            throw new BadRequestException(TOO_LATE_TO_JOIN);
        }
        SavedCard card = savedCardService.requireOwnedCard(leader.getId(), cardId);
        requireWaitingHoldsInPlace(game);

        // Not always side B. When the creating team walks out of a confirmed
        // game the team left behind is on side B, so the newcomer takes A.
        Side freeSide = freeSideOf(game);

        GameTeam joining;
        try {
            joining = gameTeamRepository.saveAndFlush(
                    teamEntry(game, teamB, leader, freeSide, splitFor(game)));
        } catch (DataIntegrityViolationException ex) {
            throw new BadRequestException("This game already has an opponent");
        }

        Booking booking = bookGameSlot(game);
        chargeJoiner(game, joining, card, booking);
        captureWaitingTeam(game, joining, booking);

        confirmBooking(game, booking);
        if (freeSide == Side.A) {
            game.setTeamA(teamB);
        } else {
            game.setTeamB(teamB);
        }
        String oldStatus = game.getStatus();
        game.setBookingId(booking.getId());
        game.setStatus(STATUS_CONFIRMED);
        gameRepository.save(game);

        notifyConfirmed(game);
        // The leader's own act is GAME_JOINED (GameService); the confirmation it set off is SYSTEM.
        auditService.record(AuditEntry.system(AuditAction.GAME_CONFIRMED)
                .entity(AuditEntityType.GAME, game.getId(), AuditLabels.game(game))
                .change("status", oldStatus, game.getStatus())
                .created("team" + freeSide.getValue().toUpperCase(), teamB.getName())
                .created("bookingId", booking.getId())
                .reason("An opponent joined"));
        log.info("Team game confirmed: gameId={} teamBId={} bookingId={}",
                game.getId(), teamB.getId(), booking.getId());
        return joining;
    }

    /**
     * A leader pulls their team out. What happens depends on how close to
     * kick-off it is, and the bands are checked in this order so they behave
     * sensibly even on a stadium whose cancellation window is shorter than the
     * opponent deadline:
     *
     * <ol>
     *   <li><b>Past the 3-hour point</b> — nobody could replace them in time,
     *       so the game is over. The team that stays gets everything back; the
     *       one that walked gets nothing. For a game still waiting there is no
     *       other team, and the timeout job would have refunded it anyway, so
     *       beating the job by hand must not cost it its money.</li>
     *   <li><b>Before the stadium's cancellation deadline</b> — everything
     *       back.</li>
     *   <li><b>After it</b> — nothing back.</li>
     * </ol>
     */
    @Transactional
    public void cancelByLeader(Game game, UUID userId) {
        GameTeam leaving = gameTeamRepository
                .findByGameIdAndLeaderIdAndStatus(game.getId(), userId, GameTeam.Status.ACTIVE)
                .orElseThrow(() -> new ForbiddenException("Only a team leader in this game can cancel it"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean waiting = STATUS_WAITING.equals(game.getStatus());

        // Nothing is cancellable once kick-off has passed. The pitch was there
        // to be used, and cancelling would reverse a booking that may already
        // be completed and refund the opponent for a game they actually got.
        if (now.isAfter(game.getStartsAt())) {
            throw new BadRequestException("The game has already started and cannot be cancelled");
        }

        // A confirmed game holds a slot; freeing it is the first thing to do,
        // whichever band we are in.
        if (!waiting) {
            freeBooking(game, "A team cancelled and the game is looking for a new opponent");
        }

        if (now.isAfter(opponentDeadline(game))) {
            if (waiting) {
                // The timeout job would have refunded this team within the
                // minute; racing it by hand must not cost them their money.
                endWaitingGame(game, "cancelled_not_filled", "No opponent was found in time", userId);
                return;
            }
            // Nobody can replace them in time, so the game is over: the leaver
            // forfeits and the team that stayed is made whole.
            paymentService.keepAllTeamPayments(leaving.getId());
            releaseTeam(leaving, now);
            endWaitingGame(game, "cancelled_not_filled",
                    "The opponent cancelled too close to kick-off", userId);
            log.info("Confirmed team game ended: gameId={} leaverForfeited=true", game.getId());
            return;
        }

        boolean refundable = refundableNow(game, now);
        if (refundable) {
            paymentService.giveBackAllTeamPayments(leaving.getId(), "Team cancelled in time");
        } else {
            paymentService.keepAllTeamPayments(leaving.getId());
        }
        releaseTeam(leaving, now);

        if (waiting) {
            String oldStatus = game.getStatus();
            game.setStatus("cancelled_by_creator");
            gameRepository.save(game);
            notifyCancelled(game, "Игра отменена создателем.");
            auditService.record(AuditEntry.player(userId, AuditAction.GAME_CANCELLED)
                    .entity(AuditEntityType.GAME, game.getId(), AuditLabels.game(game))
                    .change("status", oldStatus, game.getStatus())
                    .reason(refundOutcome(refundable)));
            log.info("Waiting team game cancelled by its leader: gameId={} refunded={}",
                    game.getId(), refundable);
            return;
        }
        reopenWithRemainingTeam(game, leaving, userId, refundable);
    }

    /**
     * The owner cancelled the booking, or an admin blocked the stadium. Neither
     * is any team's fault, so both get everything back whatever the window
     * says, and the game is over.
     */
    @Transactional
    public long cancelForBookingCancelled(UUID gameId, String reason) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found"));
        if (isOver(game)) {
            return 0L; // already cancelled or played: nothing to give back twice
        }
        game.setBookingId(null);
        return endWaitingGame(game, "cancelled_slot_taken", reason);
    }

    /**
     * What a cancellation right now would actually return to this team — the
     * same bands {@link #cancelByLeader} applies, so the figure a leader is
     * shown is the figure they get.
     */
    @Transactional(readOnly = true)
    public long refundIfCancelledNow(Game game, GameTeam team, OffsetDateTime now) {
        if (now.isAfter(game.getStartsAt())) {
            return 0L; // nothing is cancellable any more, so nothing would come back
        }
        if (now.isAfter(opponentDeadline(game))) {
            // Past this point the canceller forfeits, unless it is the lone
            // waiting team, which the timeout job would refund anyway.
            boolean lone = STATUS_WAITING.equals(game.getStatus());
            return lone ? paymentService.refundableTeamTotal(team.getId()) : 0L;
        }
        return refundableNow(game, now) ? paymentService.refundableTeamTotal(team.getId()) : 0L;
    }

    /** The stadium's cancellation window, applied to a game rather than a booking. */
    private boolean refundableNow(Game game, OffsetDateTime now) {
        return cancellationPolicy.refundable(
                game.getStartsAt(), game.getStadium().getCancellationHours(), now);
    }

    /** Ends a game nobody can play and gives every team still in it everything back. SYSTEM. */
    @Transactional
    public long endWaitingGame(Game game, String cancelStatus, String reason) {
        return endWaitingGame(game, cancelStatus, reason, null);
    }

    /**
     * As above, but ended by a leader's own cancellation when {@code cancelledBy} is set:
     * then it is that player's GAME_CANCELLED, not the system's GAME_AUTO_CANCELLED.
     */
    private long endWaitingGame(Game game, String cancelStatus, String reason, UUID cancelledBy) {
        long given = endGame(game, cancelStatus, reason, cancelledBy);
        notifyCancelled(game, reason);
        log.info("Team game ended: gameId={} status={} reason={} givenBack={}",
                game.getId(), cancelStatus, reason, given);
        return given;
    }

    /**
     * A leader settles their own team's remainder from a saved card. Allowed
     * before or after the game is played — paying late is still paying.
     *
     * @throws BadRequestException 400 "Already paid" when this team owes
     *                             nothing, whether because it already settled
     *                             or because its advance covered the pitch
     */
    @Transactional(noRollbackFor = uz.minipolya.minipolya_backend.exception.PaymentStepFailedException.class)
    public GameTeam payRemainder(UUID gameId, UUID userId, UUID cardId) {
        Game game = requireGame(gameId);
        GameTeam team = gameTeamRepository
                .findByGameIdAndLeaderIdAndStatus(gameId, userId, GameTeam.Status.ACTIVE)
                .orElseThrow(() -> new ForbiddenException("You do not lead a team in this game"));
        requireOwing(team);

        SavedCard card = savedCardService.requireOwnedCard(userId, cardId);
        Payment payment = paymentService.chargeForTeamGame(team, card, Payment.PaymentType.REMAINDER);
        if (payment.getStatus() == Payment.Status.FAILED) {
            throw new BadRequestException("Card payment was declined; the remainder is still owed");
        }
        if (payment.getStatus() == Payment.Status.PROCESSING) {
            // Kept (committed) for the reconcile job, which settles the remainder if it went through.
            throw new uz.minipolya.minipolya_backend.exception.PaymentStepFailedException(
                    "The card payment is still being confirmed; the remainder will be marked paid once it is");
        }

        GameTeam.RemainderStatus statusBefore = team.getRemainderStatus();
        settleRemainder(game, team);
        String side = team.getSide().getValue().toUpperCase();
        auditService.record(AuditEntry.player(userId, AuditAction.GAME_REMAINDER_PAID)
                .entity(AuditEntityType.GAME, gameId, AuditLabels.game(game))
                .change("team" + side + ".remainderStatus", statusBefore, team.getRemainderStatus())
                .created("team" + side + ".cardAmount", team.getRemainderAmount()));
        log.info("Team remainder paid by card: gameId={} side={} amount={}",
                gameId, team.getSide().getValue(), team.getRemainderAmount());
        return team;
    }

    /**
     * The owner marks one team's remainder as received in cash. The card and
     * the cash race each other, and whichever lands first wins — the other gets
     * 400 "Already paid".
     */
    @Transactional
    public GameTeam recordCashRemainder(UUID gameId, UUID ownerUserId, Side side) {
        Game game = requireGame(gameId);
        if (!game.getStadium().getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        GameTeam team = gameTeamRepository
                .findByGameIdAndSideAndStatus(gameId, side, GameTeam.Status.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("No team is on side " + side.getValue()));
        requireOwing(team);

        GameTeam.RemainderStatus statusBefore = team.getRemainderStatus();
        paymentService.recordCashTeamRemainder(team, game.getStadium().getOwner());
        settleRemainder(game, team);
        auditService.record(AuditEntry.owner(ownerUserId, AuditAction.CASH_RECEIVED)
                .entity(AuditEntityType.GAME, gameId, AuditLabels.game(game))
                .change("team" + side.getValue().toUpperCase() + ".remainderStatus",
                        statusBefore, team.getRemainderStatus())
                .created("team" + side.getValue().toUpperCase() + ".cashAmount", team.getRemainderAmount()));
        log.info("Team remainder taken in cash: gameId={} side={} amount={}",
                gameId, side.getValue(), team.getRemainderAmount());
        return team;
    }

    /**
     * The challenge board. Only waiting team games before their 3-hour point —
     * a game nobody could reach in time is not an offer — and never one the
     * viewer's own team is already waiting on.
     *
     * @param date      a Tashkent calendar day, or null for any
     * @param format    the pitch format, or null for any
     * @param stadiumId one stadium, or null for any
     */
    @Transactional(readOnly = true)
    public Page<GameChallengeResponse> challenges(UUID userId, LocalDate date, String format,
                                                  UUID stadiumId, Pageable pageable) {
        // The window is always a real range, never a pair of nulls: the query
        // cannot take a null bind (see GameRepository.findChallenges). Its lower
        // bound never goes behind the 3-hour cutoff, so a stale date cannot
        // surface a game nobody could still join.
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC)
                .plusHours(appProperties.getGame().getOpponentDeadlineHours());
        OffsetDateTime from = cutoff;
        OffsetDateTime to = cutoff.plusYears(FAR_FUTURE_YEARS);
        if (date != null) {
            OffsetDateTime dayStart = date.atStartOfDay(TASHKENT).toOffsetDateTime();
            from = dayStart.isAfter(cutoff) ? dayStart : cutoff;
            to = date.plusDays(1).atStartOfDay(TASHKENT).toOffsetDateTime();
        }

        return gameRepository
                .findChallenges(from, to,
                        stadiumId == null, stadiumId == null ? NO_TEAM : stadiumId,
                        format == null, format == null ? "" : format,
                        pageable, viewersTeamIds(userId), GameTeam.Status.ACTIVE)
                .map(this::toChallenge);
    }

    /** The card fee with the settings in force now; a quote never asks the payment service. */
    private CardPricing cardPricing() {
        return new CardPricing(platformSettingsService::current);
    }

    /** Each team's money, for the game response. */
    @Transactional(readOnly = true)
    public List<GameTeamResponse> teamsOf(Game game) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return gameTeamRepository.findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE).stream()
                .map(team -> GameTeamResponse.builder()
                        .side(team.getSide())
                        .teamId(team.getTeam().getId())
                        .teamName(team.getTeam().getName())
                        .leaderId(team.getLeader().getId())
                        .leaderName(team.getLeader().getName())
                        .feeAmount(team.getFeeAmount())
                        .advanceAmount(team.getAdvanceAmount())
                        .remainderAmount(team.getRemainderAmount())
                        .remainderStatus(team.getRemainderStatus().getValue())
                        // What is on the card now, gross: holds and settled charges not given back.
                        .paidInApp(paymentService.refundableTeamTotal(team.getId()))
                        .price(cardPricing().joinSplit(team).playerAmount())
                        .cardFee(cardPricing().joinSplit(team).cardFee())
                        .remainderByCard(team.owesRemainder()
                                ? cardPricing().playerAmount(team.getRemainderAmount()) : 0L)
                        .remainderInCash(team.owesRemainder() ? team.getRemainderAmount() : 0L)
                        .cancellationDeadline(cancellationPolicy.deadline(
                                game.getStartsAt(), game.getStadium().getCancellationHours()))
                        .refundIfCancelledNow(refundIfCancelledNow(game, team, now))
                        .build())
                .toList();
    }

    /** The last moment a new opponent can still arrive in time to play. */
    public OffsetDateTime opponentDeadline(Game game) {
        return game.getStartsAt().minusHours(appProperties.getGame().getOpponentDeadlineHours());
    }

    /** Slack past the 3-hour point, so the timeout job can release the hold before it lapses. */
    static final int HOLD_SLACK_MINUTES = 120;

    /**
     * How long the creating team's hold must last: until the 3-hour point, when the game
     * either has an opponent (and the hold is captured) or is over (and it is released),
     * plus {@value #HOLD_SLACK_MINUTES} minutes of slack. Capped by
     * {@code app.payments.hold-max-minutes}; a game that would need longer cannot be created.
     */
    int holdMinutes(Game game, OffsetDateTime now) {
        long minutes = Math.max(0, Duration.between(now, opponentDeadline(game)).toMinutes()) + HOLD_SLACK_MINUTES;
        int max = appProperties.getPayments().getHoldMaxMinutes();
        if (minutes > max) {
            throw new BadRequestException("A team game can be created at most "
                    + (max - HOLD_SLACK_MINUTES) / (24 * 60) + " days before it is played");
        }
        return (int) minutes;
    }

    // ---- Helpers ----

    /**
     * Takes the slot with a pending {@code source = game} booking. A rejection
     * from the overlap constraint means somebody booked the pitch while the
     * game waited: the game is over and the waiting team gets everything back.
     */
    private Booking bookGameSlot(Game game) {
        Booking booking = Booking.builder()
                .stadium(game.getStadium())
                .user(game.getCreator())
                .source("game")
                .status("pending")
                .paymentStatus(Booking.PaymentStatus.UNPAID)
                .paidAmount(0L)
                .startsAt(game.getStartsAt())
                .endsAt(game.getEndsAt())
                .durationHours((int) Duration.between(game.getStartsAt(), game.getEndsAt()).toHours())
                .price(game.getTotalPrice())
                .gameId(game.getId())
                .build();
        try {
            return bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            // Deliberately no compensation here. A failed flush poisons this
            // EntityManager, and anything we did next would roll back with the
            // throw below — while provider calls would NOT roll back, leaving
            // money moved with no record of it. Whoever took the slot cancels
            // this game and refunds its waiting team from their own committed
            // transaction (BookingService.createBooking and
            // TeamGameService.join both call cancelGamesOnSlot), and
            // GameTimeoutJob is the backstop.
            log.info("Game {} lost its slot to another booking while joining", game.getId());
            throw new SlotAlreadyBookedException("That time slot is already booked");
        }
    }

    /** A declined joiner costs nobody anything: the booking goes, the game waits on. */
    /**
     * Step 2: the joining team's charge. Anything but a confirmed charge removes the
     * booking and leaves the game waiting. A decline moved no money, so it may roll back;
     * an unanswered charge may have, so its row is committed and the reconcile job gives it
     * back if it went through (its team has left the game by then).
     */
    private void chargeJoiner(Game game, GameTeam joining, SavedCard card, Booking booking) {
        Payment charge = paymentService.chargeForTeamGame(joining, card, Payment.PaymentType.TEAM_GAME);
        if (charge.getStatus() == Payment.Status.PAID) {
            return;
        }
        bookingRepository.delete(booking);
        joining.setStatus(GameTeam.Status.CANCELLED);
        joining.setLeftAt(OffsetDateTime.now(ZoneOffset.UTC));
        gameTeamRepository.save(joining);
        if (charge.getStatus() == Payment.Status.FAILED) {
            throw new BadRequestException(
                    "Card payment was declined; the game is still looking for an opponent");
        }
        log.warn("Joining charge unanswered: gameId={} paymentId={}; the game goes on waiting",
                game.getId(), charge.getId());
        throw new uz.minipolya.minipolya_backend.exception.PaymentStepFailedException(
                "The card payment could not be confirmed; the game is still looking for an opponent. "
                        + "If the money was taken it will be returned in full");
    }

    /** A join must not go ahead while the waiting team's hold is still unconfirmed. */
    private void requireWaitingHoldsInPlace(Game game) {
        for (GameTeam waiting : gameTeamRepository.findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE)) {
            boolean unsettled = paymentService.teamPayments(waiting.getId()).stream()
                    .anyMatch(p -> p.getStatus() == Payment.Status.PROCESSING);
            if (unsettled) {
                throw new BadRequestException(
                        "The other team's payment is still being confirmed; try again in a few minutes");
            }
        }
    }

    /**
     * The last money step, and the only one that cannot be walked back: if the
     * waiting team's hold can no longer be captured, nobody plays, so the
     * joining team is refunded and the game ends.
     */
    /**
     * Step 3, last: take the waiting team's hold. If it lapsed, was let go, or the answer
     * was lost, the game cannot go ahead, and everything is undone in this order: the
     * joiner's charge is reversed and the waiting team's hold given back (endGame), the
     * booking is deleted, the game is cancelled, both teams are told — and all of that is
     * committed. A hold whose capture went unanswered is left return_pending, which the
     * reconcile job reverses if it was taken or releases if it was not.
     */
    private void captureWaitingTeam(Game game, GameTeam joining, Booking booking) {
        boolean captured = true;
        for (GameTeam waiting : gameTeamRepository.findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE)) {
            if (!waiting.getId().equals(joining.getId())) {
                captured &= paymentService.captureAllHeldTeamPayments(waiting.getId());
            }
        }
        if (captured) {
            return;
        }
        log.error("Could not capture the waiting team's hold on game {}; cancelling it and returning both teams' money",
                game.getId());
        // endGame gives every team still in the game its money back — the joiner included,
        // since its row is active by now.
        endGame(game, "cancelled_slot_taken", "The other team's payment could not be settled", null);
        bookingRepository.delete(booking);
        notifyCancelled(game, "Raqib jamoaning to'lovini yechib bo'lmadi, o'yin bekor qilindi. To'lov to'liq qaytariladi.");
        throw new uz.minipolya.minipolya_backend.exception.PaymentStepFailedException(
                "The other team's payment could not be taken, so the game is cancelled; "
                        + "your payment is being returned in full");
    }

    /** Both advances are on the pitch now, so the booking is part-paid (or paid outright). */
    private void confirmBooking(Game game, Booking booking) {
        booking.setStatus("confirmed");
        booking.setExpiresAt(null);
        refreshBookingPayment(game, booking);
    }

    /**
     * Recomputes what the booking has received from the teams currently in the
     * game: their advances, plus any remainder already settled. The platform
     * fee is never part of it — it is not money for the pitch.
     */
    void refreshBookingPayment(Game game, Booking booking) {
        long paid = gameTeamRepository.findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE).stream()
                .mapToLong(GameTeam::paidTowardsPitch)
                .sum();
        booking.setPaidAmount(paid);
        booking.setPaymentStatus(paid >= booking.getPrice()
                ? Booking.PaymentStatus.PAID
                : Booking.PaymentStatus.PARTIALLY_PAID);
        bookingRepository.save(booking);
    }

    /** Cancels the game and gives every team still in it everything back. */
    private long endGame(Game game, String status, String reason, UUID cancelledBy) {
        String oldStatus = game.getStatus();
        game.setStatus(status);
        gameRepository.save(game);
        long given = 0L;
        for (GameTeam entry : gameTeamRepository.findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE)) {
            given += paymentService.giveBackAllTeamPayments(entry.getId(), reason);
            releaseTeam(entry, OffsetDateTime.now(ZoneOffset.UTC));
        }
        AuditEntry audit = cancelledBy == null
                ? AuditEntry.system(AuditAction.GAME_AUTO_CANCELLED)
                : AuditEntry.player(cancelledBy, AuditAction.GAME_CANCELLED);
        auditService.record(audit
                .entity(AuditEntityType.GAME, game.getId(), AuditLabels.game(game))
                .change("status", oldStatus, game.getStatus())
                .reason(reason));
        return given;
    }

    /**
     * Gives the slot back. {@code cancelled_opponent_left} rather than one of
     * the ordinary cancel statuses, so the owner's schedule says why the pitch
     * is free again instead of blaming a player or the owner.
     */
    private void freeBooking(Game game, String reason) {
        if (game.getBookingId() == null) {
            return;
        }
        bookingRepository.findById(game.getBookingId()).ifPresent(booking -> {
            booking.setStatus("cancelled_opponent_left");
            booking.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC));
            booking.setCancellationReason(reason);
            bookingRepository.save(booking);
            notifyOwner(game, "Слот освободился: одна из команд отменила игру.");
        });
        game.setBookingId(null);
    }

    /**
     * One team is gone but the other is still in and there is still time to
     * find a replacement, so the game goes back to WAITING rather than ending.
     */
    private void reopenWithRemainingTeam(Game game, GameTeam leaving, UUID userId, boolean refunded) {
        String oldStatus = game.getStatus();
        String side = leaving.getSide().getValue().toUpperCase();
        String leavingTeam = leaving.getSide() == Side.A
                ? (game.getTeamA() == null ? null : game.getTeamA().getName())
                : (game.getTeamB() == null ? null : game.getTeamB().getName());
        if (leaving.getSide() == Side.A) {
            game.setTeamA(null);
        } else {
            game.setTeamB(null);
        }
        game.setStatus(STATUS_WAITING);
        gameRepository.save(game);

        for (GameTeam staying : gameTeamRepository.findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE)) {
            UUID leaderId = staying.getLeader().getId();
            safeNotify(() -> notificationService.notify(leaderId, Notification.Type.GAME_OPPONENT_LEFT,
                    "Raqib bekor qildi", OPPONENT_LEFT,
                    Notification.ReferenceType.GAME, game.getId()));
        }
        UUID leaverId = leaving.getLeader().getId();
        String leaverMessage = refunded
                ? "Siz o'yinni bekor qildingiz. To'lovingiz to'liq qaytarildi."
                : "Siz o'yinni bekor qildingiz. Bekor qilish muddati o'tgani uchun to'lov qaytarilmaydi.";
        safeNotify(() -> notificationService.notify(leaverId, Notification.Type.GAME_CANCELLED,
                "Игра отменена", leaverMessage, Notification.ReferenceType.GAME, game.getId()));

        auditService.record(AuditEntry.player(userId, AuditAction.GAME_OPPONENT_LEFT)
                .entity(AuditEntityType.GAME, game.getId(), AuditLabels.game(game))
                .change("status", oldStatus, game.getStatus())
                .change("team" + side, leavingTeam, null)
                .reason(refundOutcome(refunded)));
        log.info("Team game reopened after a team left: gameId={} side={} refunded={}",
                game.getId(), leaving.getSide().getValue(), refunded);
    }

    private static String refundOutcome(boolean refunded) {
        return refunded ? "Cancelled in time: everything back" : "Cancelled after the deadline: nothing back";
    }

    private void notifyOwner(Game game, String message) {
        if (game.getStadium().getOwner() == null) {
            return;
        }
        UUID ownerId = game.getStadium().getOwner().getId();
        safeNotify(() -> notificationService.notify(ownerId, Notification.Type.BOOKING_CANCELLED,
                "Слот освободился", message, Notification.ReferenceType.GAME, game.getId()));
    }

    /** True once a game can no longer move: played, or already cancelled. */
    private boolean isOver(Game game) {
        return !STATUS_WAITING.equals(game.getStatus()) && !STATUS_CONFIRMED.equals(game.getStatus());
    }

    /**
     * A team that owes nothing cannot pay — whether because it already settled
     * or because its advance covered the whole pitch. Both answer the same way,
     * which is also what the owner's cash call gets once the card has landed.
     */
    private void requireOwing(GameTeam team) {
        if (!team.owesRemainder()) {
            throw new BadRequestException("Already paid");
        }
    }

    /** Marks this team settled and brings the booking's paid amount up to date. */
    private void settleRemainder(Game game, GameTeam team) {
        team.setRemainderStatus(GameTeam.RemainderStatus.PAID);
        gameTeamRepository.save(team);
        if (game.getBookingId() != null) {
            bookingRepository.findById(game.getBookingId())
                    .ifPresent(booking -> refreshBookingPayment(game, booking));
        }
    }

    private Game requireGame(UUID gameId) {
        return gameRepository.findById(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found"));
    }

    /**
     * Takes a team out of the game. Its {@code game_teams} row is kept, so its
     * payments stay explainable, and its players are taken out with it — a
     * leader whose team walked out must not keep reading the chat of the teams
     * still playing. Invited members carry no side and are left alone.
     */
    private void releaseTeam(GameTeam entry, OffsetDateTime now) {
        entry.setStatus(GameTeam.Status.CANCELLED);
        entry.setLeftAt(now);
        gameTeamRepository.save(entry);

        for (GameParticipant participant : participantRepository.findByGameId(entry.getGame().getId())) {
            if (participant.getSide() == entry.getSide()) {
                participant.setStatus(GameParticipant.Status.CANCELLED);
                participantRepository.save(participant);
            }
        }
    }

    private void notifyCancelled(Game game, String message) {
        for (GameTeam entry : gameTeamRepository.findByGameId(game.getId())) {
            if (entry.getLeader() == null) {
                continue;
            }
            UUID leaderId = entry.getLeader().getId();
            safeNotify(() -> notificationService.notify(leaderId, Notification.Type.GAME_CANCELLED,
                    "Игра отменена", message, Notification.ReferenceType.GAME, game.getId()));
        }
    }

    private void notifyConfirmed(Game game) {
        String message = "O'yin «" + game.getStadium().getName() + "» maydonida tasdiqlandi.";
        for (GameTeam entry : gameTeamRepository.findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE)) {
            UUID leaderId = entry.getLeader().getId();
            safeNotify(() -> notificationService.notify(leaderId, Notification.Type.GAME_CONFIRMED,
                    "Игра подтверждена", message, Notification.ReferenceType.GAME, game.getId()));
        }
    }

    /** Notifications are best-effort — a failure must never undo a payment. */
    private void safeNotify(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("Notification failed: {}", ex.toString());
        }
    }

    /**
     * Every team the viewer belongs to or leads, so their own waiting games are
     * not offered back to them. Padded with a sentinel because an empty
     * {@code IN (...)} is not valid SQL.
     */
    private List<UUID> viewersTeamIds(UUID userId) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(NO_TEAM);
        teamMemberRepository.findByUserId(userId).forEach(m -> ids.add(m.getTeam().getId()));
        teamRepository.findByLeaderId(userId).forEach(team -> ids.add(team.getId()));
        return List.copyOf(ids);
    }

    private GameChallengeResponse toChallenge(Game game) {
        GameTeam waiting = gameTeamRepository
                .findByGameIdAndSideAndStatus(game.getId(), Side.A, GameTeam.Status.ACTIVE)
                .or(() -> gameTeamRepository
                        .findByGameIdAndSideAndStatus(game.getId(), Side.B, GameTeam.Status.ACTIVE))
                .orElse(null);
        // The joining team takes whichever side is free, and the split gives
        // each side its own half, so quote the free one.
        Side freeSide = waiting != null && waiting.getSide() == Side.A ? Side.B : Side.A;
        TeamGameSplit split = splitFor(game);
        Stadium stadium = game.getStadium();

        return GameChallengeResponse.builder()
                .gameId(game.getId())
                .title(game.getTitle())
                .waitingTeamId(waiting == null ? null : waiting.getTeam().getId())
                .waitingTeamName(waiting == null ? null : waiting.getTeam().getName())
                .stadiumId(stadium.getId())
                .stadiumName(stadium.getName())
                .stadiumAddress(stadium.getAddress())
                .format(stadium.getFormat())
                .startsAt(game.getStartsAt())
                .endsAt(game.getEndsAt())
                .durationHours((int) Duration.between(game.getStartsAt(), game.getEndsAt()).toHours())
                .totalPrice(game.getTotalPrice())
                .feeAmount(split.fee())
                .advanceAmount(split.advanceFor(freeSide))
                .amountToPayNow(cardPricing().playerAmount(split.joinAmountFor(freeSide)))
                .cardFee(cardPricing().cardFee(split.joinAmountFor(freeSide)))
                .remainderAmount(split.remainderFor(freeSide))
                .build();
    }

    /**
     * The side a newcomer takes: whichever has no live occupant. A game with
     * both sides taken is not looking for anyone.
     */
    private Side freeSideOf(Game game) {
        Set<Side> taken = gameTeamRepository
                .findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE).stream()
                .map(GameTeam::getSide)
                .collect(java.util.stream.Collectors.toSet());
        if (!taken.contains(Side.A)) {
            return Side.A;
        }
        if (!taken.contains(Side.B)) {
            return Side.B;
        }
        throw new BadRequestException("This game already has an opponent");
    }

    /** How this game's price divides, given the stadium's minimum and the current platform fee. */
    TeamGameSplit splitFor(Game game) {
        Stadium stadium = game.getStadium();
        return TeamGameSplit.of(game.getTotalPrice(), stadium.getMinBookingAmount(),
                platformSettingsService.current().getTeamGamePlatformFee());
    }

    private GameTeam teamEntry(Game game, Team team, User leader, Side side, TeamGameSplit split) {
        return GameTeam.builder()
                .game(game)
                .team(team)
                .leader(leader)
                .side(side)
                .status(GameTeam.Status.ACTIVE)
                .feeAmount(split.fee())
                .advanceAmount(split.advanceFor(side))
                .remainderAmount(split.remainderFor(side))
                .remainderStatus(GameTeam.RemainderStatus.UNPAID)
                .build();
    }
}
