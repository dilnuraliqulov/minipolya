package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
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
import uz.minipolya.minipolya_backend.exception.SlotAlreadyBookedException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;
import uz.minipolya.minipolya_backend.util.TeamGameSplit;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

        GameTeam sideA = gameTeamRepository.saveAndFlush(
                teamEntry(game, teamA, leader, Side.A, splitFor(game)));

        Payment hold = paymentService.holdForTeamGame(sideA, card);
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
    @Transactional
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

        GameTeam sideB;
        try {
            sideB = gameTeamRepository.saveAndFlush(
                    teamEntry(game, teamB, leader, Side.B, splitFor(game)));
        } catch (DataIntegrityViolationException ex) {
            throw new BadRequestException("This game already has an opponent");
        }

        Booking booking = bookGameSlot(game);
        chargeJoiner(game, sideB, card, booking);
        captureWaitingTeam(game, sideB, booking);

        confirmBooking(game, booking);
        game.setTeamB(teamB);
        game.setBookingId(booking.getId());
        game.setStatus(STATUS_CONFIRMED);
        gameRepository.save(game);

        notifyConfirmed(game);
        auditService.log(leader.getId(), "GAME_CONFIRMED", "game", game.getId(),
                "{\"teamBId\":\"" + teamB.getId() + "\",\"bookingId\":\"" + booking.getId() + "\"}");
        log.info("Team game confirmed: gameId={} teamBId={} bookingId={}",
                game.getId(), teamB.getId(), booking.getId());
        return sideB;
    }

    /** The last moment a new opponent can still arrive in time to play. */
    public OffsetDateTime opponentDeadline(Game game) {
        return game.getStartsAt().minusHours(appProperties.getGame().getOpponentDeadlineHours());
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
            endGame(game, "cancelled_slot_taken", "The slot was booked before an opponent arrived");
            throw new SlotAlreadyBookedException("That time slot is already booked");
        }
    }

    /** A declined joiner costs nobody anything: the booking goes, the game waits on. */
    private void chargeJoiner(Game game, GameTeam sideB, SavedCard card, Booking booking) {
        Payment charge = paymentService.chargeForTeamGame(sideB, card, Payment.PaymentType.TEAM_GAME);
        if (charge.getStatus() != Payment.Status.FAILED) {
            return;
        }
        bookingRepository.delete(booking);
        sideB.setStatus(GameTeam.Status.CANCELLED);
        sideB.setLeftAt(OffsetDateTime.now(ZoneOffset.UTC));
        gameTeamRepository.save(sideB);
        throw new BadRequestException(
                "Card payment was declined; the game is still looking for an opponent");
    }

    /**
     * The last money step, and the only one that cannot be walked back: if the
     * waiting team's hold can no longer be captured, nobody plays, so the
     * joining team is refunded and the game ends.
     */
    private void captureWaitingTeam(Game game, GameTeam sideB, Booking booking) {
        try {
            for (GameTeam waiting : gameTeamRepository.findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE)) {
                if (!waiting.getId().equals(sideB.getId())) {
                    paymentService.captureAllHeldTeamPayments(waiting.getId());
                }
            }
        } catch (RuntimeException ex) {
            log.error("Could not capture the waiting team's hold on game {}: {}", game.getId(), ex.toString());
            bookingRepository.delete(booking);
            // endGame gives every team still in the game its money back — the
            // joiner included, since its row is active by now.
            endGame(game, "cancelled_slot_taken", "The other team's payment could not be settled");
            throw ex;
        }
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
    private void endGame(Game game, String status, String reason) {
        game.setStatus(status);
        gameRepository.save(game);
        for (GameTeam entry : gameTeamRepository.findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE)) {
            paymentService.giveBackAllTeamPayments(entry.getId(), reason);
            entry.setStatus(GameTeam.Status.CANCELLED);
            entry.setLeftAt(OffsetDateTime.now(ZoneOffset.UTC));
            gameTeamRepository.save(entry);
        }
        auditService.log(null, "GAME_CANCELLED", "game", game.getId(),
                "{\"reason\":\"" + status + "\"}");
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
