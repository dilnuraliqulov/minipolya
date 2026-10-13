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
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.GameMessageMapper;
import uz.minipolya.minipolya_backend.repository.GameMessageRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;

/**
 * In-game chat, available only for team_vs_team games and only to participants.
 * REST/polling-based; no WebSocket, read receipts or attachments in the MVP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameMessageService {

    private static final String TYPE_TEAM_VS_TEAM = "team_vs_team";

    private final GameRepository gameRepository;
    private final GameParticipantRepository participantRepository;
    private final GameMessageRepository gameMessageRepository;
    private final GameMessageMapper gameMessageMapper;

    @Transactional
    public GameMessageResponse sendMessage(Long gameId, Long userId, String message) {
        Game game = requireGame(gameId);
        GameParticipant participant = requireParticipant(gameId, userId);

        if (!TYPE_TEAM_VS_TEAM.equals(game.getType())) {
            throw new BadRequestException("Chat is only available for team games");
        }
        if (!"open".equals(game.getStatus()) && !"confirmed".equals(game.getStatus())) {
            throw new BadRequestException("Chat is closed for this game");
        }

        GameMessage saved = gameMessageRepository.save(GameMessage.builder()
                .game(game)
                .user(participant.getUser())
                .message(message)
                .build());
        log.info("Game message sent: gameId={} userId={} messageId={}", gameId, userId, saved.getId());
        return gameMessageMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<GameMessageResponse> getMessages(Long gameId, Long userId, Pageable pageable) {
        requireGame(gameId);
        requireParticipant(gameId, userId);
        return gameMessageRepository.findByGameIdOrderByCreatedAtDesc(gameId, pageable)
                .map(gameMessageMapper::toResponse);
    }

    private Game requireGame(Long gameId) {
        return gameRepository.findById(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found"));
    }

    private GameParticipant requireParticipant(Long gameId, Long userId) {
        return participantRepository.findByGameIdAndUserId(gameId, userId)
                .orElseThrow(() -> new ForbiddenException("You are not a participant of this game"));
    }
}
