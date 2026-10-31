package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.request.UpdateUserRequest;
import uz.minipolya.minipolya_backend.dto.response.UserResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.PhoneTakenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.UserMapper;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Profile read/update for the authenticated user. First name, last name and phone are
 * set only here, by the user themselves; Telegram never fills them in.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final int NAME_PART_MAX = 60;
    private static final Pattern PHONE = Pattern.compile("^\\+998\\d{9}$");

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        return userMapper.toResponse(findOrThrow(userId));
    }

    /**
     * Applies the non-null fields. Setting {@code firstName} or {@code lastName} also rewrites
     * {@code name} as "first last", so every screen that shows the name keeps working.
     * Unique phones: another account's phone is 409 {@code PHONE_TAKEN}.
     */
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateUserRequest request) {
        String firstName = namePart("firstName", request.getFirstName());
        String lastName = namePart("lastName", request.getLastName());
        String phone = phone(request.getPhone());

        User user = findOrThrow(userId);
        String nameBefore = user.getName();
        String firstBefore = user.getFirstName();
        String lastBefore = user.getLastName();
        String languageBefore = user.getLanguage();
        String phoneBefore = user.getPhone();

        userMapper.updateUserFromRequest(request, user);
        if (firstName != null) {
            user.setFirstName(firstName);
        }
        if (lastName != null) {
            user.setLastName(lastName);
        }
        if (firstName != null || lastName != null) {
            user.setName(fullName(user.getFirstName(), user.getLastName()));
        }
        if (phone != null && !phone.equals(user.getPhone())) {
            userRepository.findByPhone(phone)
                    .filter(other -> !other.getId().equals(userId))
                    .ifPresent(other -> {
                        throw new PhoneTakenException();
                    });
            user.setPhone(phone);
        }

        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Another request took the phone between the check and the write.
            throw new PhoneTakenException();
        }

        // The phone number itself is personal data and stays out of the audit log.
        AuditEntry audit = AuditEntry.player(userId, AuditAction.PROFILE_UPDATED)
                .entity(AuditEntityType.USER, userId, AuditLabels.user(saved))
                .change("firstName", firstBefore, saved.getFirstName())
                .change("lastName", lastBefore, saved.getLastName())
                .change("name", nameBefore, saved.getName())
                .change("language", languageBefore, saved.getLanguage());
        if (!Objects.equals(phoneBefore, saved.getPhone())) {
            audit.change("phoneChanged", false, true);
        }
        if (!audit.getChanges().isEmpty()) {
            auditService.record(audit);
        }
        return userMapper.toResponse(saved);
    }

    /**
     * Saves the phone a user shared with the bot (their own Telegram contact, already
     * normalised to {@code +998XXXXXXXXX}). The same uniqueness rule as
     * {@link #updateProfile}: another account's phone is never overwritten or merged, it is
     * {@link PhoneTakenException}. Audited {@code PHONE_SHARED_VIA_BOT}, without the number.
     */
    @Transactional
    public void savePhoneFromBot(UUID userId, String phone) {
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new BadRequestException("phone must be in the format +998XXXXXXXXX");
        }
        User user = findOrThrow(userId);
        if (phone.equals(user.getPhone())) {
            return;
        }
        userRepository.findByPhone(phone)
                .filter(other -> !other.getId().equals(userId))
                .ifPresent(other -> {
                    throw new PhoneTakenException();
                });
        user.setPhone(phone);
        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new PhoneTakenException();
        }
        auditService.record(AuditEntry.player(userId, AuditAction.PHONE_SHARED_VIA_BOT)
                .entity(AuditEntityType.USER, userId, AuditLabels.user(saved))
                .change("phoneChanged", false, true));
    }

    /** "first last", or whichever of the two is set. */
    private static String fullName(String first, String last) {
        if (first == null) {
            return last;
        }
        return last == null ? first : first + " " + last;
    }

    private static String namePart(String field, String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > NAME_PART_MAX) {
            throw new BadRequestException(field + " must be between 1 and " + NAME_PART_MAX + " characters");
        }
        return trimmed;
    }

    private static String phone(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (!PHONE.matcher(trimmed).matches()) {
            throw new BadRequestException("phone must be in the format +998XXXXXXXXX");
        }
        return trimmed;
    }

    private User findOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
