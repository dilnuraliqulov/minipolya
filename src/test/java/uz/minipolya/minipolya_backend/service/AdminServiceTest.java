package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import uz.minipolya.minipolya_backend.dto.request.AdminUpdateStadiumStatusRequest;
import uz.minipolya.minipolya_backend.dto.request.AdminUpdateUserRequest;
import uz.minipolya.minipolya_backend.dto.response.AdminUserResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminPlatformStatsResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerRevenueResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.OwnerApplication;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.OwnerApplicationRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminServiceTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TARGET_USER_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID BOOKING_USER_ID = UUID.randomUUID();

    private UserRepository userRepository;
    private StadiumRepository stadiumRepository;
    private BookingRepository bookingRepository;
    private PaymentRepository paymentRepository;
    private GameRepository gameRepository;
    private ReviewRepository reviewRepository;
    private OwnerApplicationRepository ownerApplicationRepository;
    private PaymentService paymentService;
    private NotificationService notificationService;
    private AuditService auditService;
    private AdminService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        stadiumRepository = mock(StadiumRepository.class);
        bookingRepository = mock(BookingRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        gameRepository = mock(GameRepository.class);
        reviewRepository = mock(ReviewRepository.class);
        ownerApplicationRepository = mock(OwnerApplicationRepository.class);
        paymentService = mock(PaymentService.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);

        service = new AdminService(userRepository, stadiumRepository, bookingRepository, paymentRepository,
                gameRepository, reviewRepository, ownerApplicationRepository, paymentService,
                notificationService, auditService);
    }

    private Stadium stadium(String status) {
        return Stadium.builder().id(STADIUM_ID).name("Arena").address("Tashkent").status(status)
                .format("5x5").surfaceType("artificial")
                .owner(User.builder().id(OWNER_ID).name("Owner").isOwner(true).build()).build();
    }

    @Test
    void platformStatsAggregatesCounts() {
        when(userRepository.count()).thenReturn(100L);
        when(userRepository.countByIsOwnerTrue()).thenReturn(10L);
        when(userRepository.countByCreatedAtAfter(any())).thenReturn(5L);
        when(stadiumRepository.count()).thenReturn(20L);
        when(stadiumRepository.countByStatus("approved")).thenReturn(15L);
        when(stadiumRepository.countByStatus("pending")).thenReturn(3L);
        when(stadiumRepository.countByStatus("blocked")).thenReturn(2L);
        when(bookingRepository.countByStatusIn(any())).thenReturn(500L);
        when(bookingRepository.countByStatusInAndStartsAtBetween(any(), any(), any())).thenReturn(7L);
        when(paymentRepository.sumTotalRevenue(any())).thenReturn(9_000_000L);
        when(paymentRepository.sumRevenueAfter(any(), any())).thenReturn(100_000L);
        when(gameRepository.count()).thenReturn(50L);
        when(gameRepository.countByStatus("open")).thenReturn(8L);
        when(ownerApplicationRepository.countByStatus(OwnerApplication.Status.PENDING)).thenReturn(4L);

        AdminPlatformStatsResponse stats = service.getPlatformStats();

        assertThat(stats.getTotalUsers()).isEqualTo(100);
        assertThat(stats.getTotalOwners()).isEqualTo(10);
        assertThat(stats.getApprovedStadiums()).isEqualTo(15);
        assertThat(stats.getPendingStadiumApprovals()).isEqualTo(3);
        assertThat(stats.getTotalBookings()).isEqualTo(500);
        assertThat(stats.getTotalRevenue()).isEqualTo(9_000_000);
        assertThat(stats.getActiveGames()).isEqualTo(8);
        assertThat(stats.getPendingOwnerApplications()).isEqualTo(4);
    }

    @Test
    void searchUsersByNameDelegatesToSearch() {
        when(userRepository.searchUsers(eq("john"), any()))
                .thenReturn(new PageImpl<>(List.of(User.builder().id(USER_ID).name("John").build())));

        assertThat(service.getUsers("john", Pageable.ofSize(20)).getContent()).hasSize(1);
        verify(userRepository).searchUsers(eq("john"), any());
    }

    @Test
    void searchUsersByPhoneDelegatesToSearch() {
        when(userRepository.searchUsers(eq("+99890"), any()))
                .thenReturn(new PageImpl<>(List.of(User.builder().id(USER_ID).phone("+998901112233").build())));

        assertThat(service.getUsers("+99890", Pageable.ofSize(20)).getContent()).hasSize(1);
        verify(userRepository).searchUsers(eq("+99890"), any());
    }

    @Test
    void updateUserChangesOwnerFlagAndAudits() {
        User user = User.builder().id(TARGET_USER_ID).name("U").isOwner(false).isAdmin(false).build();
        when(userRepository.findById(TARGET_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateUser(TARGET_USER_ID, AdminUpdateUserRequest.builder().isOwner(true).build(), ADMIN_ID);

        assertThat(user.isOwner()).isTrue();
        verify(auditService).log(eq(ADMIN_ID), eq("USER_ROLE_CHANGED"), eq("user"), eq(TARGET_USER_ID), anyString());
    }

    @Test
    void adminCannotRemoveOwnAdminFlag() {
        User admin = User.builder().id(ADMIN_ID).isAdmin(true).build();
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.updateUser(ADMIN_ID, AdminUpdateUserRequest.builder().isAdmin(false).build(), ADMIN_ID))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void blockingAUserPersistsTheFlagAndAudits() {
        User user = User.builder().id(TARGET_USER_ID).name("U").isBlocked(false).build();
        when(userRepository.findById(TARGET_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUserResponse response = service.updateUser(
                TARGET_USER_ID, AdminUpdateUserRequest.builder().isBlocked(true).build(), ADMIN_ID);

        assertThat(user.isBlocked()).isTrue();
        assertThat(response.isBlocked()).isTrue();
        verify(userRepository).save(user);
        verify(auditService).log(ADMIN_ID, "USER_BLOCKED", "user", TARGET_USER_ID, "{\"isBlocked\":true}");
    }

    @Test
    void unblockingAUserPersistsTheFlagAndAudits() {
        User user = User.builder().id(TARGET_USER_ID).name("U").isBlocked(true).build();
        when(userRepository.findById(TARGET_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUserResponse response = service.updateUser(
                TARGET_USER_ID, AdminUpdateUserRequest.builder().isBlocked(false).build(), ADMIN_ID);

        assertThat(user.isBlocked()).isFalse();
        assertThat(response.isBlocked()).isFalse();
        verify(auditService).log(ADMIN_ID, "USER_UNBLOCKED", "user", TARGET_USER_ID, "{\"isBlocked\":false}");
    }

    @Test
    void anUnchangedBlockedFlagIsNotAudited() {
        User user = User.builder().id(TARGET_USER_ID).name("U").isBlocked(true).build();
        when(userRepository.findById(TARGET_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateUser(TARGET_USER_ID, AdminUpdateUserRequest.builder().isBlocked(true).build(), ADMIN_ID);

        verify(auditService, never()).log(any(), eq("USER_BLOCKED"), any(), any(), anyString());
    }

    @Test
    void adminCannotBlockThemselves() {
        User admin = User.builder().id(ADMIN_ID).isAdmin(true).isBlocked(false).build();
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.updateUser(
                ADMIN_ID, AdminUpdateUserRequest.builder().isBlocked(true).build(), ADMIN_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("You cannot block yourself");

        assertThat(admin.isBlocked()).isFalse();
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void adminCanUnblockThemselvesIfSomehowBlocked() {
        User admin = User.builder().id(ADMIN_ID).isAdmin(true).isBlocked(true).build();
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateUser(ADMIN_ID, AdminUpdateUserRequest.builder().isBlocked(false).build(), ADMIN_ID);

        assertThat(admin.isBlocked()).isFalse();
    }

    @Test
    void getStadiumsFilteredByStatus() {
        when(stadiumRepository.findByStatus(eq("pending"), any()))
                .thenReturn(new PageImpl<>(List.of(stadium("pending"))));

        assertThat(service.getStadiums("pending", null, Pageable.ofSize(20)).getContent()).hasSize(1);
        verify(stadiumRepository).findByStatus(eq("pending"), any());
    }

    @Test
    void searchStadiumsByName() {
        when(stadiumRepository.searchStadiums(eq("arena"), any()))
                .thenReturn(new PageImpl<>(List.of(stadium("approved"))));

        assertThat(service.getStadiums(null, "arena", Pageable.ofSize(20)).getContent()).hasSize(1);
        verify(stadiumRepository).searchStadiums(eq("arena"), any());
    }

    @Test
    void approveStadiumSetsStatusAndNotifiesOwner() {
        Stadium stadium = stadium("pending");
        stadium.getOwner().setOwner(false);
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStadiumStatus(STADIUM_ID, AdminUpdateStadiumStatusRequest.builder().status("approved").build(), ADMIN_ID);

        assertThat(stadium.getStatus()).isEqualTo("approved");
        assertThat(stadium.getOwner().isOwner()).isTrue();
        verify(notificationService).notify(eq(OWNER_ID), eq(Notification.Type.STADIUM_APPROVED),
                anyString(), anyString(), eq(Notification.ReferenceType.STADIUM), eq(STADIUM_ID));
        verify(auditService).log(ADMIN_ID, "STADIUM_APPROVED", "stadium", STADIUM_ID);
    }

    @Test
    void blockStadiumCancelsFutureBookingsAndRefunds() {
        Stadium stadium = stadium("approved");
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));
        Booking future = Booking.builder().id(BOOKING_ID).status("confirmed").price(100_000)
                .user(User.builder().id(BOOKING_USER_ID).build()).startsAt(OffsetDateTime.now().plusDays(3)).build();
        when(bookingRepository.findByStadiumIdAndStartsAtBetween(eq(STADIUM_ID), any(), any())).thenReturn(List.of(future));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStadiumStatus(STADIUM_ID,
                AdminUpdateStadiumStatusRequest.builder().status("blocked").reason("fraud").build(), ADMIN_ID);

        assertThat(stadium.getStatus()).isEqualTo("blocked");
        assertThat(future.getStatus()).isEqualTo("cancelled_by_owner");
        verify(paymentService).refundBookingPayment(BOOKING_ID, 100_000, "fraud");
    }

    @Test
    void platformRevenueGroupsByDay() {
        OffsetDateTime d1 = LocalDate.now(TASHKENT).minusDays(1).atTime(12, 0).atZone(TASHKENT).toOffsetDateTime();
        OffsetDateTime d2 = LocalDate.now(TASHKENT).atTime(10, 0).atZone(TASHKENT).toOffsetDateTime();
        when(paymentRepository.findPositiveCompletedSince(any(), any())).thenReturn(List.of(
                Payment.builder().amount(100_000).createdAt(d1).build(),
                Payment.builder().amount(70_000).createdAt(d2).build()));

        OwnerRevenueResponse revenue = service.getPlatformRevenue();

        assertThat(revenue.getDailyRevenue()).hasSize(2);
        assertThat(revenue.getDailyRevenue().get(0).getRevenue()).isEqualTo(100_000);
    }
}
