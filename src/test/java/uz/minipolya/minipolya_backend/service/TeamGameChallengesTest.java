package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.response.GameChallengeResponse;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.TeamMember;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.repository.TeamMemberRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The challenge board: waiting team games an opponent could still join. A game
 * inside its 3-hour window is not listed, because nobody could get there in
 * time, and a game your own team is waiting on is not a challenge for you.
 */
class TeamGameChallengesTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private static final long PRICE = 20_000_000L;
    private static final long FEE = 2_500_000L;
    private static final long ADVANCE = 2_500_000L;
    private static final long REMAINDER = 7_500_000L;

    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final UUID VIEWER_TEAM_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();

    private GameRepository gameRepository;
    private GameTeamRepository gameTeamRepository;
    private TeamMemberRepository teamMemberRepository;
    private TeamRepository teamRepository;
    private TeamGameService service;

    private Stadium stadium;
    private Team waitingTeam;
    private Game game;
    private GameTeam sideA;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        gameTeamRepository = mock(GameTeamRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        teamRepository = mock(TeamRepository.class);
        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.current()).thenReturn(
                PlatformSettings.builder().teamGamePlatformFee(FEE).build());

        service = new TeamGameService(gameRepository, gameTeamRepository,
                mock(GameParticipantRepository.class), mock(BookingRepository.class),
                mock(PaymentService.class), mock(SavedCardService.class), platformSettingsService,
                mock(NotificationService.class), mock(AuditService.class),
                new CancellationPolicy(), new AppProperties(),
                teamMemberRepository, teamRepository);

        User waitingLeader = User.builder().id(UUID.randomUUID()).name("Leader A").build();
        stadium = Stadium.builder().id(STADIUM_ID).name("Chilonzor Arena")
                .address("Chilonzor tumani, 12-kvartal").format("5x5")
                .minBookingAmount(2 * ADVANCE).cancellationHours(24)
                .owner(User.builder().id(UUID.randomUUID()).build()).build();
        waitingTeam = Team.builder().id(UUID.randomUUID()).name("Bars FC").leader(waitingLeader).build();

        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
        game = Game.builder().id(UUID.randomUUID()).type("team_vs_team").status("open")
                .creator(waitingLeader).stadium(stadium).teamA(waitingTeam)
                .startsAt(start).endsAt(start.plusHours(1))
                .totalPrice(PRICE).maxPlayers(10).build();
        sideA = GameTeam.builder().id(UUID.randomUUID()).game(game).team(waitingTeam).leader(waitingLeader)
                .side(Side.A).status(GameTeam.Status.ACTIVE)
                .feeAmount(FEE).advanceAmount(ADVANCE).remainderAmount(REMAINDER)
                .remainderStatus(GameTeam.RemainderStatus.UNPAID).build();

        when(teamMemberRepository.findByUserId(VIEWER_ID)).thenReturn(List.of());
        when(teamRepository.findByLeaderId(VIEWER_ID)).thenReturn(List.of());
        when(gameTeamRepository.findByGameIdAndSideAndStatus(game.getId(), Side.A, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.of(sideA));
        when(gameRepository.findChallenges(any(), any(), anyBoolean(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(game)));
    }

    private Page<GameChallengeResponse> challenges(LocalDate date, String format, UUID stadiumId) {
        return service.challenges(VIEWER_ID, date, format, stadiumId, PageRequest.of(0, 20));
    }

    @Test
    void aChallengeCarriesWhatTheCardNeedsToShow() {
        GameChallengeResponse card = challenges(null, null, null).getContent().getFirst();

        assertThat(card.getGameId()).isEqualTo(game.getId());
        assertThat(card.getWaitingTeamName()).isEqualTo("Bars FC");
        assertThat(card.getStadiumName()).isEqualTo("Chilonzor Arena");
        assertThat(card.getStadiumAddress()).isEqualTo("Chilonzor tumani, 12-kvartal");
        assertThat(card.getFormat()).isEqualTo("5x5");
        assertThat(card.getStartsAt()).isEqualTo(game.getStartsAt());
        assertThat(card.getDurationHours()).isEqualTo(1);
        assertThat(card.getTotalPrice()).isEqualTo(PRICE);
    }

    @Test
    void aChallengeShowsWhatTheJoiningTeamPaysNow() {
        GameChallengeResponse card = challenges(null, null, null).getContent().getFirst();

        // 25 000 fee + 25 000 advance = 50 000 so'm, with 75 000 to come later.
        assertThat(card.getFeeAmount()).isEqualTo(FEE);
        assertThat(card.getAdvanceAmount()).isEqualTo(ADVANCE);
        assertThat(card.getAmountToPayNow()).isEqualTo(FEE + ADVANCE);
        assertThat(card.getRemainderAmount()).isEqualTo(REMAINDER);
    }

    /** Every argument findChallenges was called with, in order. */
    private Object[] lastCall() {
        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<Boolean> anyStadium = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<UUID> stadiumId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Boolean> anyFormat = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> format = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<UUID>> excluded = ArgumentCaptor.forClass(List.class);
        verify(gameRepository).findChallenges(from.capture(), to.capture(), anyStadium.capture(),
                stadiumId.capture(), anyFormat.capture(), format.capture(), any(), excluded.capture(), any());
        return new Object[]{from.getValue(), to.getValue(), anyStadium.getValue(),
                stadiumId.getValue(), anyFormat.getValue(), format.getValue(), excluded.getValue()};
    }

    @Test
    void gamesInsideTheThreeHourWindowAreNotListed() {
        challenges(null, null, null);

        OffsetDateTime from = (OffsetDateTime) lastCall()[0];
        long minutesAhead = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), from).toMinutes();
        assertThat(minutesAhead).isBetween(175L, 181L);   // ~3 hours from now
    }

    @Test
    void theDateFilterCoversOneTashkentDay() {
        LocalDate date = LocalDate.of(2099, 11, 5);   // far future, so the 3-hour cutoff does not clip it

        challenges(date, null, null);

        Object[] call = lastCall();
        assertThat(call[0]).isEqualTo(date.atStartOfDay(TASHKENT).toOffsetDateTime());
        assertThat(call[1]).isEqualTo(date.plusDays(1).atStartOfDay(TASHKENT).toOffsetDateTime());
    }

    @Test
    void aDateInThePastIsClippedToTheThreeHourCutoff() {
        challenges(LocalDate.now(TASHKENT).minusDays(5), null, null);

        // The lower bound never goes behind the cutoff, so a stale date cannot
        // surface a game that can no longer be joined.
        OffsetDateTime from = (OffsetDateTime) lastCall()[0];
        assertThat(from).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Test
    void theFormatAndStadiumFiltersArePassedThrough() {
        challenges(null, "7x7", STADIUM_ID);

        Object[] call = lastCall();
        assertThat(call[2]).isEqualTo(false);        // anyStadium
        assertThat(call[3]).isEqualTo(STADIUM_ID);
        assertThat(call[4]).isEqualTo(false);        // anyFormat
        assertThat(call[5]).isEqualTo("7x7");
    }

    @Test
    void anUnfilteredCallPassesNoNullsAtAll() {
        challenges(null, null, null);

        // A null bind in a `? is null` position is rejected outright by
        // PostgreSQL (42P18), so the query is built to never receive one.
        Object[] call = lastCall();
        assertThat(call).doesNotContainNull();
        assertThat(call[2]).isEqualTo(true);         // anyStadium
        assertThat(call[4]).isEqualTo(true);         // anyFormat
    }

    @Test
    void gamesTheViewersOwnTeamIsWaitingOnAreExcluded() {
        Team viewersTeam = Team.builder().id(VIEWER_TEAM_ID).name("My FC").build();
        when(teamMemberRepository.findByUserId(VIEWER_ID)).thenReturn(
                List.of(TeamMember.builder().team(viewersTeam).build()));

        challenges(null, null, null);

        ArgumentCaptor<List<UUID>> excluded = ArgumentCaptor.forClass(List.class);
        verify(gameRepository).findChallenges(any(), any(), anyBoolean(), any(), anyBoolean(), any(), any(), excluded.capture(), any());
        assertThat(excluded.getValue()).contains(VIEWER_TEAM_ID);
    }

    @Test
    void teamsTheViewerLeadsAreExcludedEvenWithoutAMembershipRow() {
        when(teamRepository.findByLeaderId(VIEWER_ID)).thenReturn(
                List.of(Team.builder().id(VIEWER_TEAM_ID).name("My FC").build()));

        challenges(null, null, null);

        ArgumentCaptor<List<UUID>> excluded = ArgumentCaptor.forClass(List.class);
        verify(gameRepository).findChallenges(any(), any(), anyBoolean(), any(), anyBoolean(), any(), any(), excluded.capture(), any());
        assertThat(excluded.getValue()).contains(VIEWER_TEAM_ID);
    }

    @Test
    void aViewerInNoTeamStillGetsAUsableExclusionList() {
        challenges(null, null, null);

        ArgumentCaptor<List<UUID>> excluded = ArgumentCaptor.forClass(List.class);
        verify(gameRepository).findChallenges(any(), any(), anyBoolean(), any(), anyBoolean(), any(), any(), excluded.capture(), any());

        // An empty IN (...) is not valid SQL, so the list always holds something.
        assertThat(excluded.getValue()).isNotEmpty();
    }
}
