package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.request.CreateOwnerApplicationRequest;
import uz.minipolya.minipolya_backend.dto.request.ReviewApplicationRequest;
import uz.minipolya.minipolya_backend.dto.response.OwnerApplicationResponse;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.OwnerApplication;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.OwnerApplicationMapper;
import uz.minipolya.minipolya_backend.repository.OwnerApplicationRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Owner-application flow: a user applies, an admin approves (promoting the user
 * to owner) or rejects. One open application per user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerApplicationService {

    private final OwnerApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final OwnerApplicationMapper applicationMapper;
    private final NotificationService notificationService;
    private final AuditService auditService;

    @Transactional
    public OwnerApplicationResponse apply(CreateOwnerApplicationRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.isOwner()) {
            throw new BadRequestException("You are already a stadium owner");
        }
        boolean hasOpen = !applicationRepository.findByUserIdAndStatusIn(userId,
                List.of(OwnerApplication.Status.PENDING, OwnerApplication.Status.UNDER_REVIEW)).isEmpty();
        if (hasOpen) {
            throw new BadRequestException("You already have a pending application");
        }

        OwnerApplication application = applicationRepository.save(OwnerApplication.builder()
                .user(user)
                .businessName(request.getBusinessName())
                .businessAddress(request.getBusinessAddress())
                .phone(request.getPhone())
                .description(request.getDescription())
                .stadiumCount(request.getStadiumCount())
                .status(OwnerApplication.Status.PENDING)
                .build());

        // TODO: notify admins that a new owner application is awaiting review.
        log.info("Owner application submitted: id={} userId={}", application.getId(), userId);
        return applicationMapper.toResponse(application);
    }

    @Transactional(readOnly = true)
    public List<OwnerApplicationResponse> getMyApplications(Long userId) {
        return applicationRepository.findByUserId(userId).stream()
                .map(applicationMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<OwnerApplicationResponse> getPendingApplications(Pageable pageable) {
        return applicationRepository.findByStatus(OwnerApplication.Status.PENDING, pageable)
                .map(applicationMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<OwnerApplicationResponse> getAllApplications(Pageable pageable) {
        return applicationRepository.findAll(pageable).map(applicationMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public OwnerApplicationResponse getApplication(Long applicationId) {
        return applicationMapper.toResponse(requireApplication(applicationId));
    }

    @Transactional
    public OwnerApplicationResponse reviewApplication(Long applicationId, ReviewApplicationRequest request,
                                                      Long adminUserId) {
        OwnerApplication application = requireApplication(applicationId);
        User admin = requireAdmin(adminUserId);

        if (application.getStatus() != OwnerApplication.Status.PENDING
                && application.getStatus() != OwnerApplication.Status.UNDER_REVIEW) {
            throw new BadRequestException("This application has already been reviewed");
        }

        boolean approved = "approved".equalsIgnoreCase(request.getStatus().trim());
        application.setReviewedBy(admin);
        application.setReviewNote(request.getReviewNote());
        application.setReviewedAt(OffsetDateTime.now(ZoneOffset.UTC));

        User applicant = application.getUser();
        if (approved) {
            application.setStatus(OwnerApplication.Status.APPROVED);
            applicant.setOwner(true);
            userRepository.save(applicant);
            auditService.log(adminUserId, "APPLICATION_APPROVED", "owner_application", applicationId);
            auditService.log(adminUserId, "USER_ROLE_CHANGED", "user", applicant.getId(), "{\"isOwner\":true}");
            notifyApplicant(applicant.getId(), applicationId,
                    "Заявка одобрена",
                    "Ваша заявка на владельца одобрена! Теперь вы можете добавлять стадионы.");
        } else {
            application.setStatus(OwnerApplication.Status.REJECTED);
            auditService.log(adminUserId, "APPLICATION_REJECTED", "owner_application", applicationId);
            notifyApplicant(applicant.getId(), applicationId,
                    "Заявка отклонена",
                    "Ваша заявка на владельца не одобрена. Причина: "
                            + (request.getReviewNote() == null ? "не указана" : request.getReviewNote()));
        }

        OwnerApplication saved = applicationRepository.save(application);
        log.info("Owner application reviewed: id={} status={} adminId={}",
                applicationId, saved.getStatus().getValue(), adminUserId);
        return applicationMapper.toResponse(saved);
    }

    // ---- Helpers ----

    private void notifyApplicant(Long userId, Long applicationId, String title, String message) {
        try {
            notificationService.notify(userId, Notification.Type.OWNER_APPLICATION_STATUS, title, message,
                    Notification.ReferenceType.APPLICATION, applicationId);
        } catch (Exception ex) {
            log.warn("Owner-application notification failed: {}", ex.toString());
        }
    }

    private User requireAdmin(Long adminUserId) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!admin.isAdmin()) {
            throw new ForbiddenException("Admin access required");
        }
        return admin;
    }

    private OwnerApplication requireApplication(Long applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
    }
}
