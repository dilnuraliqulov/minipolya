package uz.minipolya.minipolya_backend.support;

import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.config.JwtProvider;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rows for {@link DatabaseTest}s, written straight through the repositories with
 * valid defaults, so a test states only what it is about. Phones are unique per call.
 */
@Component
public class TestData {

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000);

    private final UserRepository users;
    private final StadiumRepository stadiums;
    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final GameRepository games;
    private final GameTeamRepository gameTeams;
    private final GameParticipantRepository participants;
    private final TeamRepository teams;
    private final JwtProvider jwtProvider;

    public TestData(UserRepository users, StadiumRepository stadiums, BookingRepository bookings,
                    PaymentRepository payments, GameRepository games, GameTeamRepository gameTeams,
                    GameParticipantRepository participants, TeamRepository teams, JwtProvider jwtProvider) {
        this.users = users;
        this.stadiums = stadiums;
        this.bookings = bookings;
        this.payments = payments;
        this.games = games;
        this.gameTeams = gameTeams;
        this.participants = participants;
        this.teams = teams;
        this.jwtProvider = jwtProvider;
    }

    public User user(String name) {
        long n = SEQUENCE.incrementAndGet();
        return users.save(User.builder().name(name).phone(String.format("99890%07d", n % 10_000_000)).build());
    }

    public User admin(String name) {
        User user = user(name);
        user.setAdmin(true);
        return users.save(user);
    }

    public User owner(String name) {
        User user = user(name);
        user.setOwner(true);
        return users.save(user);
    }

    /** {@code Authorization} header value for this user. */
    public String bearer(User user) {
        return "Bearer " + jwtProvider.generateToken(user);
    }

    public Stadium stadium(User owner, String name) {
        return stadium(owner, name, "approved");
    }

    public Stadium stadium(User owner, String name, String status) {
        return stadiums.save(Stadium.builder()
                .owner(owner)
                .name(name)
                .address("Toshkent, " + name)
                .format("5x5")
                .surfaceType("artificial")
                .openTime(LocalTime.of(8, 0))
                .closeTime(LocalTime.of(23, 0))
                .pricePerHour(20_000_000L)
                .status(status)
                .build());
    }

    public Booking booking(Stadium stadium, User user, OffsetDateTime startsAt, String status) {
        return bookings.save(Booking.builder()
                .stadium(stadium)
                .user(user)
                .source("app")
                .status(status)
                .startsAt(startsAt)
                .endsAt(startsAt.plusHours(1))
                .durationHours(1)
                .price(20_000_000L)
                .build());
    }

    public Booking walkIn(Stadium stadium, String clientName, String clientPhone, OffsetDateTime startsAt) {
        return bookings.save(Booking.builder()
                .stadium(stadium)
                .source("manual")
                .status("confirmed")
                .clientName(clientName)
                .clientPhone(clientPhone)
                .isWalkin(true)
                .startsAt(startsAt)
                .endsAt(startsAt.plusHours(1))
                .durationHours(1)
                .price(20_000_000L)
                .build());
    }

    /** A card payment of {@code amount} gross, split owner/platform, with a card fee and a commission. */
    public Payment cardPayment(Booking booking, long amount, long ownerAmount, long cardFee, Long commission,
                               Payment.Status status) {
        return payments.save(Payment.builder()
                .booking(booking)
                .user(booking.getUser() != null ? booking.getUser() : booking.getStadium().getOwner())
                .amount(amount)
                .ownerAmount(ownerAmount)
                .platformAmount(amount - ownerAmount)
                .cardFeeExpected(cardFee)
                .providerCommission(commission)
                .commissionPercent(0)
                .method(Payment.Method.CARD)
                .paymentType(Payment.PaymentType.BOOKING)
                .provider(Payment.Provider.ATMOS)
                .providerTxnId("txn-" + SEQUENCE.incrementAndGet())
                .status(status)
                .build());
    }

    public Payment cashPayment(Booking booking, long amount) {
        return payments.save(Payment.builder()
                .booking(booking)
                .user(booking.getStadium().getOwner())
                .amount(amount)
                .ownerAmount(amount)
                .platformAmount(0L)
                .commissionPercent(0)
                .method(Payment.Method.CASH)
                .paymentType(Payment.PaymentType.BOOKING)
                .provider(Payment.Provider.CASH)
                .status(Payment.Status.PAID)
                .build());
    }

    public Payment save(Payment payment) {
        return payments.save(payment);
    }

    public Game casualGame(Stadium stadium, User creator, OffsetDateTime startsAt) {
        return games.save(Game.builder()
                .stadium(stadium)
                .creator(creator)
                .type("casual")
                .status("open")
                .maxPlayers(10)
                .startsAt(startsAt)
                .endsAt(startsAt.plusHours(1))
                .totalPrice(20_000_000L)
                .serviceFeePerPerson(0L)
                .build());
    }

    public Game teamGame(Stadium stadium, User creator, OffsetDateTime startsAt) {
        return games.save(Game.builder()
                .stadium(stadium)
                .creator(creator)
                .type("team_vs_team")
                .status("open")
                .maxPlayers(10)
                .startsAt(startsAt)
                .endsAt(startsAt.plusHours(1))
                .totalPrice(20_000_000L)
                .serviceFeePerPerson(0L)
                .build());
    }

    public Team team(User leader, String name) {
        return teams.save(Team.builder().leader(leader).name(name).build());
    }

    public GameTeam gameTeam(Game game, Team team, GameParticipant.Side side) {
        return gameTeams.save(GameTeam.builder()
                .game(game)
                .team(team)
                .leader(team.getLeader())
                .side(side)
                .feeAmount(2_500_000L)
                .advanceAmount(5_000_000L)
                .remainderAmount(5_000_000L)
                .build());
    }

    public GameParticipant participant(Game game, User user, GameParticipant.Side side) {
        return participants.save(GameParticipant.builder().game(game).user(user).side(side).build());
    }
}
