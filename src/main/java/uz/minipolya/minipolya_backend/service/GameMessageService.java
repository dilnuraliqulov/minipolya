package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.GameMessageResponse;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameMessage;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.GameMessageMapper;
import uz.minipolya.minipolya_backend.repository.GameMessageRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;

import java.util.UUID;

/**
 * In-game chat, available only for team_vs_team games and only to participants.
 * REST/polling-based; no WebSocket, read receipts or attachments in the MVP.
 *
 * <p>Chat opens when the game is confirmed and closes once it is over: while a
 * game is still looking for an opponent there is nobody to talk to, and after
 * it has been played or cancelled there is nothing left to say.
 *
 * <p>A team that joined partway through — because the first opponent walked out
 * and this one took its place — sees only what was said after it joined.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameMessageService {

    private static final String TYPE_TEAM_VS_TEAM = "team_vs_team";
    private static final String STATUS_CONFIRMED = "confirmed";

    private final GameRepository gameRepository;
    private final GameParticipantRepository participantRepository;
    private final GameMessageRepository gameMessageRepository;
    private final GameMessageMapper gameMessageMapper;
    private final GameTeamRepository gameTeamRepository;

    @Transactional
    public GameMessageResponse sendMessage(UUID gameId, UUID userId, String message) {
        Game game = requireGame(gameId);
        GameParticipant participant = requireParticipant(gameId, userId);
        requireOpenChat(game);

        GameMessage saved = gameMessageRepository.save(GameMessage.builder()
                .game(game)
                .user(participant.getUser())
                .message(message)
                .build());
        log.info("Game message sent: gameId={} userId={} messageId={}", gameId, userId, saved.getId());
        return gameMessageMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<GameMessageResponse> getMessages(UUID gameId, UUID userId, Pageable pageable) {
        Game game = requireGame(gameId);
        requireParticipant(gameId, userId);
        requireOpenChat(game);

        // A leader whose team took a side partway through only sees the chat
        // from that moment. An invited member leads no team, so there is no
        // join moment to cut at and they see their side's whole history.
        return gameTeamRepository
                .findByGameIdAndLeaderIdAndStatus(gameId, userId, GameTeam.Status.ACTIVE)
                .map(team -> gameMessageRepository.findByGameIdAndCreatedAtAfterOrderByCreatedAtDesc(
                        gameId, team.getJoinedAt(), pageable))
                .orElseGet(() -> gameMessageRepository.findByGameIdOrderByCreatedAtDesc(gameId, pageable))
                .map(gameMessageMapper::toResponse);
    }

    /** Chat lives exactly as long as a confirmed game does. */
    private void requireOpenChat(Game game) {
        if (!TYPE_TEAM_VS_TEAM.equals(game.getType())) {
            throw new BadRequestException("Chat is only available for team games");
        }
        if (!STATUS_CONFIRMED.equals(game.getStatus())) {
            throw new BadRequestException("Chat is closed for this game");
        }
    }

    private Game requireGame(UUID gameId) {
        return gameRepository.findById(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found"));
    }

    private GameParticipant requireParticipant(UUID gameId, UUID userId) {
        return participantRepository.findByGameIdAndUserId(gameId, userId)
                .orElseThrow(() -> new ForbiddenException("You are not a participant of this game"));
    }
}
