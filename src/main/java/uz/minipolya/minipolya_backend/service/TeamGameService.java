package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;
import uz.minipolya.minipolya_backend.util.TeamGameSplit;

import java.time.OffsetDateTime;
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

    /** The last moment a new opponent can still arrive in time to play. */
    public OffsetDateTime opponentDeadline(Game game) {
        return game.getStartsAt().minusHours(appProperties.getGame().getOpponentDeadlineHours());
    }

    // ---- Helpers ----

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
