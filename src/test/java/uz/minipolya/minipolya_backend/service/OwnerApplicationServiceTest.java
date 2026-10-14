package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import uz.minipolya.minipolya_backend.dto.request.CreateOwnerApplicationRequest;
import uz.minipolya.minipolya_backend.dto.request.ReviewApplicationRequest;
import uz.minipolya.minipolya_backend.dto.response.OwnerApplicationResponse;
import uz.minipolya.minipolya_backend.entity.OwnerApplication;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.mapper.OwnerApplicationMapper;
import uz.minipolya.minipolya_backend.repository.OwnerApplicationRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

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

class OwnerApplicationServiceTest {

    private static final UUID APPLICANT_ID = UUID.randomUUID();
    private static final UUID APPLICATION_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    private OwnerApplicationRepository applicationRepository;
    private UserRepository userRepository;
    private OwnerApplicationMapper applicationMapper;
    private NotificationService notificationService;
    private AuditService auditService;
    private OwnerApplicationService service;

    private User applicant;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(OwnerApplicationRepository.class);
        userRepository = mock(UserRepository.class);
        applicationMapper = mock(OwnerApplicationMapper.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        service = new OwnerApplicationService(applicationRepository, userRepository,
                applicationMapper, notificationService, auditService);

        applicant = User.builder().id(APPLICANT_ID).name("Player").isOwner(false).build();
    }

    private CreateOwnerApplicationRequest request() {
        return CreateOwnerApplicationRequest.builder()
                .businessName("Yashil Maydon").businessAddress("Tashkent").phone("+998901234567")
                .stadiumCount(2).build();
    }

    // ---- apply ----

    @Test
    void applyCreatesPendingApplication() {
        when(userRepository.findById(APPLICANT_ID)).thenReturn(Optional.of(applicant));
        when(applicationRepository.findByUserIdAndStatusIn(any(), any())).thenReturn(List.of());
        when(applicationRepository.save(any(OwnerApplication.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationMapper.toResponse(any())).thenReturn(new OwnerApplicationResponse());

        service.apply(request(), APPLICANT_ID);

        ArgumentCaptor<OwnerApplication> captor = ArgumentCaptor.forClass(OwnerApplication.class);
        verify(applicationRepository).save(captor.capture());
        OwnerApplication saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OwnerApplication.Status.PENDING);
        assertThat(saved.getBusinessName()).isEqualTo("Yashil Maydon");
        assertThat(saved.getUser()).isSameAs(applicant);
    }

    @Test
    void applyRejectedWhenOpenApplicationExists() {
        when(userRepository.findById(APPLICANT_ID)).thenReturn(Optional.of(applicant));
        when(applicationRepository.findByUserIdAndStatusIn(any(), any()))
                .thenReturn(List.of(OwnerApplication.builder().id(APPLICATION_ID).build()));

        assertThatThrownBy(() -> service.apply(request(), APPLICANT_ID)).isInstanceOf(BadRequestException.class);
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void applyRejectedWhenAlreadyOwner() {
        applicant.setOwner(true);
        when(userRepository.findById(APPLICANT_ID)).thenReturn(Optional.of(applicant));

        assertThatThrownBy(() -> service.apply(request(), APPLICANT_ID)).isInstanceOf(BadRequestException.class);
        verify(applicationRepository, never()).save(any());
    }

    // ---- reviewApplication ----

    private OwnerApplication pendingApplication() {
        return OwnerApplication.builder().id(APPLICATION_ID).user(applicant).status(OwnerApplication.Status.PENDING).build();
    }

    private User admin() {
        return User.builder().id(ADMIN_ID).name("Admin").isAdmin(true).build();
    }

    @Test
    void approveMarksApprovedAndPromotesUser() {
        OwnerApplication application = pendingApplication();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(applicationRepository.save(any(OwnerApplication.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationMapper.toResponse(any())).thenReturn(new OwnerApplicationResponse());

        service.reviewApplication(APPLICATION_ID, ReviewApplicationRequest.builder().status("approved").build(), ADMIN_ID);

        assertThat(application.getStatus()).isEqualTo(OwnerApplication.Status.APPROVED);
        assertThat(applicant.isOwner()).isTrue();
        verify(userRepository).save(applicant);
    }

    @Test
    void rejectMarksRejectedAndLeavesUserUnchanged() {
        OwnerApplication application = pendingApplication();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(applicationRepository.save(any(OwnerApplication.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationMapper.toResponse(any())).thenReturn(new OwnerApplicationResponse());

        service.reviewApplication(APPLICATION_ID, ReviewApplicationRequest.builder().status("rejected").reviewNote("Incomplete").build(), ADMIN_ID);

        assertThat(application.getStatus()).isEqualTo(OwnerApplication.Status.REJECTED);
        assertThat(applicant.isOwner()).isFalse();
        verify(userRepository, never()).save(applicant);
    }

    @Test
    void reviewAlreadyReviewedApplicationIsRejected() {
        OwnerApplication application = OwnerApplication.builder().id(APPLICATION_ID).user(applicant)
                .status(OwnerApplication.Status.APPROVED).build();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin()));

        assertThatThrownBy(() -> service.reviewApplication(APPLICATION_ID,
                ReviewApplicationRequest.builder().status("approved").build(), ADMIN_ID))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void nonAdminCannotReview() {
        UUID nonAdminId = UUID.randomUUID();
        OwnerApplication application = pendingApplication();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
        when(userRepository.findById(nonAdminId)).thenReturn(Optional.of(User.builder().id(nonAdminId).isAdmin(false).build()));

        assertThatThrownBy(() -> service.reviewApplication(APPLICATION_ID,
                ReviewApplicationRequest.builder().status("approved").build(), nonAdminId))
                .isInstanceOf(ForbiddenException.class);
        verify(applicationRepository, never()).save(any());
    }

    // ---- listings ----

    @Test
    void getPendingApplicationsReturnsPendingOnes() {
        when(applicationRepository.findByStatus(eq(OwnerApplication.Status.PENDING), any()))
                .thenReturn(new PageImpl<>(List.of(pendingApplication())));
        when(applicationMapper.toResponse(any())).thenReturn(new OwnerApplicationResponse());

        assertThat(service.getPendingApplications(Pageable.ofSize(20)).getContent()).hasSize(1);
    }

    @Test
    void getMyApplicationsReturnsUserApplications() {
        when(applicationRepository.findByUserId(APPLICANT_ID)).thenReturn(List.of(pendingApplication()));
        when(applicationMapper.toResponse(any())).thenReturn(new OwnerApplicationResponse());

        assertThat(service.getMyApplications(APPLICANT_ID)).hasSize(1);
    }
}
