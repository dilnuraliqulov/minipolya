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
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.mapper.GameMessageMapper;
import uz.minipolya.minipolya_backend.repository.GameMessageRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameMessageServiceTest {

    private GameRepository gameRepository;
    private GameParticipantRepository participantRepository;
    private GameMessageRepository gameMessageRepository;
    private GameMessageMapper gameMessageMapper;
    private GameMessageService service;

    private User player;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        participantRepository = mock(GameParticipantRepository.class);
        gameMessageRepository = mock(GameMessageRepository.class);
        gameMessageMapper = mock(GameMessageMapper.class);

        service = new GameMessageService(gameRepository, participantRepository,
                gameMessageRepository, gameMessageMapper);

        player = User.builder().id(7L).name("Player").build();
    }

    private void stubParticipant(Long gameId) {
        when(participantRepository.findByGameIdAndUserId(gameId, 7L)).thenReturn(Optional.of(
                GameParticipant.builder().id(1L).user(player).build()));
    }

    @Test
    void sendMessageInTeamGameSucceeds() {
        Game game = Game.builder().id(100L).type("team_vs_team").status("open").build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        stubParticipant(100L);
        when(gameMessageRepository.save(any(GameMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameMessageMapper.toResponse(any(GameMessage.class))).thenReturn(new GameMessageResponse());

        service.sendMessage(100L, 7L, "Good luck!");

        ArgumentCaptor<GameMessage> captor = ArgumentCaptor.forClass(GameMessage.class);
        verify(gameMessageRepository).save(captor.capture());
        GameMessage saved = captor.getValue();
        assertThat(saved.getMessage()).isEqualTo("Good luck!");
        assertThat(saved.getUser()).isSameAs(player);
        assertThat(saved.getGame()).isSameAs(game);
    }

    @Test
    void sendMessageInCasualGameIsRejected() {
        Game game = Game.builder().id(100L).type("casual").status("open").build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        stubParticipant(100L);

        assertThatThrownBy(() -> service.sendMessage(100L, 7L, "hi"))
                .isInstanceOf(BadRequestException.class);
        verify(gameMessageRepository, never()).save(any());
    }

    @Test
    void sendMessageAsNonParticipantIsForbidden() {
        Game game = Game.builder().id(100L).type("team_vs_team").status("open").build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(100L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendMessage(100L, 7L, "hi"))
                .isInstanceOf(ForbiddenException.class);
        verify(gameMessageRepository, never()).save(any());
    }

    @Test
    void sendMessageInCancelledGameIsRejected() {
        Game game = Game.builder().id(100L).type("team_vs_team").status("cancelled_by_creator").build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        stubParticipant(100L);

        assertThatThrownBy(() -> service.sendMessage(100L, 7L, "hi"))
                .isInstanceOf(BadRequestException.class);
        verify(gameMessageRepository, never()).save(any());
    }

    @Test
    void getMessagesReturnsPaginatedHistory() {
        Game game = Game.builder().id(100L).type("team_vs_team").status("confirmed").build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        stubParticipant(100L);
        when(gameMessageRepository.findByGameIdOrderByCreatedAtDesc(eq(100L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new GameMessage())));
        when(gameMessageMapper.toResponse(any(GameMessage.class))).thenReturn(new GameMessageResponse());

        var result = service.getMessages(100L, 7L, Pageable.ofSize(20));

        assertThat(result.getContent()).hasSize(1);
    }
}
