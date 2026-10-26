package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import uz.minipolya.minipolya_backend.dto.response.GameMessageResponse;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameMessage;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.mapper.GameMessageMapper;
import uz.minipolya.minipolya_backend.repository.GameMessageRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameMessageServiceTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PARTICIPANT_ID = UUID.randomUUID();

    private GameRepository gameRepository;
    private GameParticipantRepository participantRepository;
    private GameMessageRepository gameMessageRepository;
    private GameMessageMapper gameMessageMapper;
    private GameTeamRepository gameTeamRepository;
    private GameMessageService service;

    private User player;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        participantRepository = mock(GameParticipantRepository.class);
        gameMessageRepository = mock(GameMessageRepository.class);
        gameMessageMapper = mock(GameMessageMapper.class);

        gameTeamRepository = mock(GameTeamRepository.class);

        service = new GameMessageService(gameRepository, participantRepository,
                gameMessageRepository, gameMessageMapper, gameTeamRepository);

        player = User.builder().id(USER_ID).name("Player").build();
    }

    private void stubParticipant(UUID gameId) {
        when(participantRepository.findByGameIdAndUserId(gameId, USER_ID)).thenReturn(Optional.of(
                GameParticipant.builder().id(PARTICIPANT_ID).user(player).build()));
    }

    @Test
    void sendMessageInConfirmedTeamGameSucceeds() {
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("confirmed").build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        stubParticipant(GAME_ID);
        when(gameMessageRepository.save(any(GameMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameMessageMapper.toResponse(any(GameMessage.class))).thenReturn(new GameMessageResponse());

        service.sendMessage(GAME_ID, USER_ID, "Good luck!");

        ArgumentCaptor<GameMessage> captor = ArgumentCaptor.forClass(GameMessage.class);
        verify(gameMessageRepository).save(captor.capture());
        GameMessage saved = captor.getValue();
        assertThat(saved.getMessage()).isEqualTo("Good luck!");
        assertThat(saved.getUser()).isSameAs(player);
        assertThat(saved.getGame()).isSameAs(game);
    }

    @Test
    void sendMessageInCasualGameIsRejected() {
        Game game = Game.builder().id(GAME_ID).type("casual").status("confirmed").build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        stubParticipant(GAME_ID);

        assertThatThrownBy(() -> service.sendMessage(GAME_ID, USER_ID, "hi"))
                .isInstanceOf(BadRequestException.class);
        verify(gameMessageRepository, never()).save(any());
    }

    @Test
    void sendMessageAsNonParticipantIsForbidden() {
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("confirmed").build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendMessage(GAME_ID, USER_ID, "hi"))
                .isInstanceOf(ForbiddenException.class);
        verify(gameMessageRepository, never()).save(any());
    }

    @Test
    void sendMessageInCancelledGameIsRejected() {
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("cancelled_by_creator").build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        stubParticipant(GAME_ID);

        assertThatThrownBy(() -> service.sendMessage(GAME_ID, USER_ID, "hi"))
                .isInstanceOf(BadRequestException.class);
        verify(gameMessageRepository, never()).save(any());
    }

    @Test
    void getMessagesReturnsPaginatedHistory() {
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("confirmed").build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        stubParticipant(GAME_ID);
        when(gameMessageRepository.findByGameIdOrderByCreatedAtDesc(eq(GAME_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new GameMessage())));
        when(gameMessageMapper.toResponse(any(GameMessage.class))).thenReturn(new GameMessageResponse());

        var result = service.getMessages(GAME_ID, USER_ID, Pageable.ofSize(20));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void chatIsClosedWhileTheGameIsStillLookingForAnOpponent() {
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("open").build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        stubParticipant(GAME_ID);

        // There is nobody to talk to yet: a waiting game has one team in it.
        assertThatThrownBy(() -> service.sendMessage(GAME_ID, USER_ID, "hi"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Chat is closed for this game");
        verify(gameMessageRepository, never()).save(any());
    }

    @Test
    void readingTheChatOfAWaitingGameIsAlsoClosed() {
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("open").build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        stubParticipant(GAME_ID);

        assertThatThrownBy(() -> service.getMessages(GAME_ID, USER_ID, Pageable.ofSize(20)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void chatIsClosedOnceTheGameHasBeenPlayed() {
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("completed").build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        stubParticipant(GAME_ID);

        assertThatThrownBy(() -> service.sendMessage(GAME_ID, USER_ID, "hi"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aNewOpponentSeesOnlyMessagesSentAfterTheyJoined() {
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("confirmed").build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        stubParticipant(GAME_ID);
        OffsetDateTime joinedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, USER_ID, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.of(GameTeam.builder().id(UUID.randomUUID())
                        .joinedAt(joinedAt).status(GameTeam.Status.ACTIVE).build()));
        when(gameMessageRepository.findByGameIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(GAME_ID), eq(joinedAt), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new GameMessage())));
        when(gameMessageMapper.toResponse(any(GameMessage.class))).thenReturn(new GameMessageResponse());

        var result = service.getMessages(GAME_ID, USER_ID, Pageable.ofSize(20));

        // What the previous opponent said is not this team's business.
        assertThat(result.getContent()).hasSize(1);
        verify(gameMessageRepository, never())
                .findByGameIdOrderByCreatedAtDesc(any(), any(Pageable.class));
    }

    @Test
    void someoneWithNoTeamOfTheirOwnStillSeesTheWholeHistory() {
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("confirmed").build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        stubParticipant(GAME_ID);
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, USER_ID, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.empty());
        when(gameMessageRepository.findByGameIdOrderByCreatedAtDesc(eq(GAME_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new GameMessage(), new GameMessage())));
        when(gameMessageMapper.toResponse(any(GameMessage.class))).thenReturn(new GameMessageResponse());

        // An invited member leads no team, so there is no join moment to cut at.
        assertThat(service.getMessages(GAME_ID, USER_ID, Pageable.ofSize(20)).getContent()).hasSize(2);
    }
}
