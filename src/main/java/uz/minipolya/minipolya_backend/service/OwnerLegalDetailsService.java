package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.request.UpdateOwnerLegalDetailsRequest;
import uz.minipolya.minipolya_backend.dto.response.OwnerLegalDetailsResponse;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails.ContractStatus;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails.LegalType;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.OwnerLegalDetailsMapper;
import uz.minipolya.minipolya_backend.repository.OwnerLegalDetailsRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An owner's legal form, tax id and ATMOS contract, kept by admins. The recipient id
 * saved here is where {@code PaymentService} pays the owner's card splits, and a stadium
 * is only approved once its owner's contract is signed ({@link #requireSignedContract}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerLegalDetailsService {

    private static final Pattern NINE_DIGITS = Pattern.compile("\\d{9}");
    private static final Pattern FOURTEEN_DIGITS = Pattern.compile("\\d{14}");
    private static final int MAX_RECIPIENT_ID = 50;

    private final OwnerLegalDetailsRepository repository;
    private final UserRepository userRepository;
    private final OwnerLegalDetailsMapper mapper;
    private final AuditService auditService;

    /** The user's legal details, or null when nothing has been entered yet. */
    @Transactional(readOnly = true)
    public OwnerLegalDetailsResponse get(UUID userId) {
        requireUser(userId);
        return repository.findById(userId).map(mapper::toResponse).orElse(null);
    }

    @Transactional
    public OwnerLegalDetailsResponse update(UUID userId, UpdateOwnerLegalDetailsRequest request, UUID adminId) {
        User user = requireUser(userId);
        LegalType legalType = parse(request.getLegalType(), LegalType::fromValue,
                "legalType must be one of yatt, mchj, self_employed");
        String tin = request.getTin() == null ? "" : request.getTin().trim();
        Pattern tinFormat = legalType == LegalType.SELF_EMPLOYED ? FOURTEEN_DIGITS : NINE_DIGITS;
        if (!tinFormat.matcher(tin).matches()) {
            throw new BadRequestException("tin must be 9 digits (or 14 for self-employed)");
        }
        ContractStatus contract = parse(request.getAtmosContractStatus(), ContractStatus::fromValue,
                "atmosContractStatus must be one of none, pending, signed");
        String recipientId = null;
        if (contract == ContractStatus.SIGNED) {
            recipientId = request.getAtmosRecipientId() == null ? "" : request.getAtmosRecipientId().trim();
            if (recipientId.isEmpty()) {
                throw new BadRequestException("atmosRecipientId is required when the contract is signed");
            }
            if (recipientId.length() > MAX_RECIPIENT_ID) {
                throw new BadRequestException("atmosRecipientId must be at most 50 characters");
            }
        }

        OwnerLegalDetails details = repository.findById(userId)
                .orElseGet(() -> OwnerLegalDetails.builder().userId(userId).build());
        Map<String, Object> before = snapshot(details);
        details.setLegalType(legalType);
        details.setTin(tin);
        details.setAtmosContractStatus(contract);
        details.setAtmosRecipientId(recipientId);

        AuditEntry audit = AuditEntry.admin(adminId, AuditAction.OWNER_LEGAL_DETAILS_CHANGED)
                .entity(AuditEntityType.USER, userId, AuditLabels.user(user))
                .diff(before, snapshot(details));
        if (audit.getChanges().isEmpty()) {
            return mapper.toResponse(details); // nothing changed: nothing written
        }
        details.setUpdatedBy(adminId);
        details.setUpdatedByName(userRepository.findById(adminId).map(User::getName).orElse(null));
        OwnerLegalDetails saved = repository.saveAndFlush(details);
        auditService.record(audit);
        log.info("Admin {} updated the legal details of user {}: contract={}", adminId, userId,
                contract.getValue());
        return mapper.toResponse(saved);
    }

    /** Each user's contract status; a user with no row is {@code none}. */
    @Transactional(readOnly = true)
    public Map<UUID, ContractStatus> contractStatuses(Collection<UUID> userIds) {
        return repository.findAllById(userIds).stream()
                .collect(Collectors.toMap(OwnerLegalDetails::getUserId, OwnerLegalDetails::getAtmosContractStatus));
    }

    /**
     * Approving a stadium needs its owner able to receive card payouts: a signed ATMOS
     * contract and a recipient id. 400 otherwise, whatever the client checked.
     */
    @Transactional(readOnly = true)
    public void requireSignedContract(UUID ownerId) {
        boolean signed = repository.findById(ownerId).map(OwnerLegalDetails::canReceivePayouts).orElse(false);
        if (!signed) {
            throw new BadRequestException("The owner's ATMOS contract is not signed");
        }
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /** The fields an audit entry compares. None of them is a secret. */
    private static Map<String, Object> snapshot(OwnerLegalDetails details) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("legalType", details.getLegalType());
        fields.put("tin", details.getTin());
        fields.put("atmosContractStatus", details.getAtmosContractStatus());
        fields.put("atmosRecipientId", details.getAtmosRecipientId());
        return fields;
    }

    private static <T> T parse(String value, Function<String, T> parser, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(message);
        }
    }
}
