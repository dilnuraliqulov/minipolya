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
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.GameMessageMapper;
import uz.minipolya.minipolya_backend.repository.GameMessageRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
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
                .kind(GameMessage.Kind.USER)
                .build());
        log.info("Game message sent: gameId={} userId={} messageId={}", gameId, userId, saved.getId());
        return gameMessageMapper.toResponse(saved);
    }

    /**
     * A line the platform wrote: the game was confirmed, a team joined. It is an ordinary
     * row with no sender, so it pages, sorts and gets cut at a late joiner's join like
     * everything else, and the clients word it in the reader's own language from
     * {@code event} and {@code eventTeamName}.
     *
     * <p>It is written in the transaction that confirmed the game — the line and the
     * confirmation belong together — and whatever goes wrong is logged rather than thrown,
     * so the caller is never handed an exception by a chat line. (It deliberately does not
     * run in a transaction of its own: the game it points at is not committed yet, so a
     * separate transaction could not see it.)
     */
    @Transactional
    public void systemMessage(Game game, String event, String teamName) {
        try {
            gameMessageRepository.save(GameMessage.builder()
                    .game(game)
                    .kind(GameMessage.Kind.SYSTEM)
                    .event(event)
                    // The text is never shown — the clients translate the event — but the
                    // column is NOT NULL and a raw row should still read as something.
                    .message(event)
                    .eventTeamName(teamName)
                    .build());
        } catch (Exception ex) {
            log.warn("Could not write the '{}' chat line for game {}: {}", event, game.getId(), ex.toString());
        }
    }

    /**
     * One page of the chat, newest first. {@code before} asks for the history older than
     * that instant — what "load the earlier messages" means — and is absent on the first
     * page.
     */
    @Transactional(readOnly = true)
    public Page<GameMessageResponse> getMessages(UUID gameId, UUID userId, OffsetDateTime before,
                                                 Pageable pageable) {
        Game game = requireGame(gameId);
        requireParticipant(gameId, userId);
        requireOpenChat(game);

        // A leader whose team took a side partway through only sees the chat
        // from that moment. An invited member leads no team, so there is no
        // join moment to cut at and they see their side's whole history.
        OffsetDateTime since = gameTeamRepository
                .findByGameIdAndLeaderIdAndStatus(gameId, userId, GameTeam.Status.ACTIVE)
                .map(GameTeam::getJoinedAt)
                .orElse(null);
        Page<GameMessage> page = page(gameId, since, before, pageable);
        if (page.isEmpty()) {
            return page.map(gameMessageMapper::toResponse);
        }
        // Who speaks for whom, read once for the page rather than once per message.
        Map<UUID, Side> sides = new HashMap<>();
        for (GameParticipant participant : participantRepository.findByGameId(gameId)) {
            if (participant.getUser() != null && participant.getSide() != null) {
                sides.putIfAbsent(participant.getUser().getId(), participant.getSide());
            }
        }
        Map<Side, Team> teams = new EnumMap<>(Side.class);
        for (GameTeam team : gameTeamRepository.findByGameIdAndStatus(gameId, GameTeam.Status.ACTIVE)) {
            if (team.getSide() != null && team.getTeam() != null) {
                teams.putIfAbsent(team.getSide(), team.getTeam());
            }
        }
        return page.map(message -> withSender(gameMessageMapper.toResponse(message), message, sides, teams));
    }

    /** The four history queries behind one call: with or without a join cutoff, with or without a cursor. */
    private Page<GameMessage> page(UUID gameId, OffsetDateTime since, OffsetDateTime before, Pageable pageable) {
        if (since != null && before != null) {
            return gameMessageRepository.findByGameIdAndCreatedAtAfterAndCreatedAtBeforeOrderByCreatedAtDesc(
                    gameId, since, before, pageable);
        }
        if (since != null) {
            return gameMessageRepository.findByGameIdAndCreatedAtAfterOrderByCreatedAtDesc(
                    gameId, since, pageable);
        }
        if (before != null) {
            return gameMessageRepository.findByGameIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                    gameId, before, pageable);
        }
        return gameMessageRepository.findByGameIdOrderByCreatedAtDesc(gameId, pageable);
    }

    /** The sender's side and team, when they have one; a system line has neither. */
    private GameMessageResponse withSender(GameMessageResponse response, GameMessage message,
                                           Map<UUID, Side> sides, Map<Side, Team> teams) {
        if (message.getUser() == null) {
            return response;
        }
        Side side = sides.get(message.getUser().getId());
        if (side == null) {
            return response;
        }
        response.setSide(side);
        Team team = teams.get(side);
        if (team != null) {
            response.setTeamId(team.getId());
            response.setTeamName(team.getName());
            response.setTeamLevel(team.getLevel());
        }
        return response;
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

    /**
     * A participant who is still in the game. A leader whose team walked out
     * keeps their row — it is how their payments stay explainable — but the
     * row is {@code CANCELLED}, and they have no business reading what the
     * teams still playing say to each other afterwards.
     */
    private GameParticipant requireParticipant(UUID gameId, UUID userId) {
        GameParticipant participant = participantRepository.findByGameIdAndUserId(gameId, userId)
                .orElseThrow(() -> new ForbiddenException("You are not a participant of this game"));
        if (participant.getStatus() == GameParticipant.Status.CANCELLED) {
            throw new ForbiddenException("You are no longer a participant of this game");
        }
        return participant;
    }
}
