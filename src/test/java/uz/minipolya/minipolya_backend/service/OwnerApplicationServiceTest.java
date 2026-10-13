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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerApplicationServiceTest {

    private OwnerApplicationRepository applicationRepository;
    private UserRepository userRepository;
    private OwnerApplicationMapper applicationMapper;
    private NotificationService notificationService;
    private OwnerApplicationService service;

    private User applicant;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(OwnerApplicationRepository.class);
        userRepository = mock(UserRepository.class);
        applicationMapper = mock(OwnerApplicationMapper.class);
        notificationService = mock(NotificationService.class);
        service = new OwnerApplicationService(applicationRepository, userRepository,
                applicationMapper, notificationService);

        applicant = User.builder().id(7L).name("Player").isOwner(false).build();
    }

    private CreateOwnerApplicationRequest request() {
        return CreateOwnerApplicationRequest.builder()
                .businessName("Yashil Maydon").businessAddress("Tashkent").phone("+998901234567")
                .stadiumCount(2).build();
    }

    // ---- apply ----

    @Test
    void applyCreatesPendingApplication() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(applicant));
        when(applicationRepository.findByUserIdAndStatusIn(any(), any())).thenReturn(List.of());
        when(applicationRepository.save(any(OwnerApplication.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationMapper.toResponse(any())).thenReturn(new OwnerApplicationResponse());

        service.apply(request(), 7L);

        ArgumentCaptor<OwnerApplication> captor = ArgumentCaptor.forClass(OwnerApplication.class);
        verify(applicationRepository).save(captor.capture());
        OwnerApplication saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OwnerApplication.Status.PENDING);
        assertThat(saved.getBusinessName()).isEqualTo("Yashil Maydon");
        assertThat(saved.getUser()).isSameAs(applicant);
    }

    @Test
    void applyRejectedWhenOpenApplicationExists() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(applicant));
        when(applicationRepository.findByUserIdAndStatusIn(any(), any()))
                .thenReturn(List.of(OwnerApplication.builder().id(1L).build()));

        assertThatThrownBy(() -> service.apply(request(), 7L)).isInstanceOf(BadRequestException.class);
        verify(applicationRepository, never()).save(any());
    }

    @Test
    void applyRejectedWhenAlreadyOwner() {
        applicant.setOwner(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(applicant));

        assertThatThrownBy(() -> service.apply(request(), 7L)).isInstanceOf(BadRequestException.class);
        verify(applicationRepository, never()).save(any());
    }

    // ---- reviewApplication ----

    private OwnerApplication pendingApplication() {
        return OwnerApplication.builder().id(1L).user(applicant).status(OwnerApplication.Status.PENDING).build();
    }

    private User admin() {
        return User.builder().id(99L).name("Admin").isAdmin(true).build();
    }

    @Test
    void approveMarksApprovedAndPromotesUser() {
        OwnerApplication application = pendingApplication();
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));
        when(userRepository.findById(99L)).thenReturn(Optional.of(admin()));
        when(applicationRepository.save(any(OwnerApplication.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationMapper.toResponse(any())).thenReturn(new OwnerApplicationResponse());

        service.reviewApplication(1L, ReviewApplicationRequest.builder().status("approved").build(), 99L);

        assertThat(application.getStatus()).isEqualTo(OwnerApplication.Status.APPROVED);
        assertThat(applicant.isOwner()).isTrue();
        verify(userRepository).save(applicant);
    }

    @Test
    void rejectMarksRejectedAndLeavesUserUnchanged() {
        OwnerApplication application = pendingApplication();
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));
        when(userRepository.findById(99L)).thenReturn(Optional.of(admin()));
        when(applicationRepository.save(any(OwnerApplication.class))).thenAnswer(inv -> inv.getArgument(0));
        when(applicationMapper.toResponse(any())).thenReturn(new OwnerApplicationResponse());

        service.reviewApplication(1L, ReviewApplicationRequest.builder().status("rejected").reviewNote("Incomplete").build(), 99L);

        assertThat(application.getStatus()).isEqualTo(OwnerApplication.Status.REJECTED);
        assertThat(applicant.isOwner()).isFalse();
        verify(userRepository, never()).save(applicant);
    }

    @Test
    void reviewAlreadyReviewedApplicationIsRejected() {
        OwnerApplication application = OwnerApplication.builder().id(1L).user(applicant)
                .status(OwnerApplication.Status.APPROVED).build();
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));
        when(userRepository.findById(99L)).thenReturn(Optional.of(admin()));

        assertThatThrownBy(() -> service.reviewApplication(1L,
                ReviewApplicationRequest.builder().status("approved").build(), 99L))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void nonAdminCannotReview() {
        OwnerApplication application = pendingApplication();
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application));
        when(userRepository.findById(50L)).thenReturn(Optional.of(User.builder().id(50L).isAdmin(false).build()));

        assertThatThrownBy(() -> service.reviewApplication(1L,
                ReviewApplicationRequest.builder().status("approved").build(), 50L))
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
        when(applicationRepository.findByUserId(7L)).thenReturn(List.of(pendingApplication()));
        when(applicationMapper.toResponse(any())).thenReturn(new OwnerApplicationResponse());

        assertThat(service.getMyApplications(7L)).hasSize(1);
    }
}
