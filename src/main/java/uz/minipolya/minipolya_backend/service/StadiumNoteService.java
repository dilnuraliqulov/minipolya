package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.response.StadiumNoteResponse;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumNote;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.StadiumNoteMapper;
import uz.minipolya.minipolya_backend.repository.StadiumNoteRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.UUID;

/**
 * Admins' internal notes on a stadium, newest first. Only the admin endpoints read them;
 * owners and players never see them. Adding one is audited without its text.
 */
@Service
@RequiredArgsConstructor
public class StadiumNoteService {

    private static final int MAX_TEXT = 1000;

    private final StadiumNoteRepository noteRepository;
    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final StadiumNoteMapper mapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<StadiumNoteResponse> list(UUID stadiumId) {
        requireStadium(stadiumId);
        return noteRepository.findByStadiumIdOrderByCreatedAtDescIdDesc(stadiumId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional
    public StadiumNoteResponse add(UUID stadiumId, String text, UUID adminId) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            throw new BadRequestException("text is required");
        }
        if (body.length() > MAX_TEXT) {
            throw new BadRequestException("text must be at most 1000 characters");
        }
        Stadium stadium = requireStadium(stadiumId);
        String authorName = userRepository.findById(adminId).map(User::getName).orElse("Admin");
        StadiumNote note = noteRepository.save(StadiumNote.builder()
                .stadiumId(stadiumId).authorId(adminId).authorName(authorName).text(body)
                .build());
        auditService.record(AuditEntry.admin(adminId, AuditAction.STADIUM_NOTE_ADDED)
                .entity(AuditEntityType.STADIUM, stadiumId, AuditLabels.stadium(stadium))
                .created("noteId", note.getId())
                .reason("note added"));
        return mapper.toResponse(note);
    }

    private Stadium requireStadium(UUID stadiumId) {
        return stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
    }
}
