package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditChange;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.Audited;
import uz.minipolya.minipolya_backend.dto.request.AdminUpdateStadiumStatusRequest;
import uz.minipolya.minipolya_backend.dto.request.AdminUpdateUserRequest;
import uz.minipolya.minipolya_backend.dto.response.AdminStadiumResponse;
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
import uz.minipolya.minipolya_backend.util.Revenue;

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
    private GameService gameService;
    private RevenueService revenueService;
    private AdminStadiumService adminStadiumService;
    private OwnerLegalDetailsService ownerLegalDetailsService;
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

        gameService = mock(GameService.class);
        revenueService = mock(RevenueService.class);
        adminStadiumService = mock(AdminStadiumService.class);
        ownerLegalDetailsService = mock(OwnerLegalDetailsService.class);
        when(adminStadiumService.toResponse(any())).thenAnswer(inv -> AdminStadiumResponse.builder()
                .id(((Stadium) inv.getArgument(0)).getId()).status(((Stadium) inv.getArgument(0)).getStatus()).build());
        when(revenueService.between(any(), any())).thenReturn(new RevenueService.Summary(Revenue.ZERO, 0));
        when(revenueService.total()).thenReturn(Revenue.ZERO);
        service = new AdminService(userRepository, stadiumRepository, bookingRepository, paymentRepository,
                gameRepository, ownerApplicationRepository, paymentService,
                notificationService, auditService, gameService, revenueService, adminStadiumService,
                ownerLegalDetailsService);
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
        when(revenueService.total()).thenReturn(new Revenue(9_000_000L, 135_000L, 8_000_000L, 865_000L));
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
        AuditEntry entry = Audited.one(auditService, AuditAction.USER_ROLE_CHANGED);
        assertThat(entry.getEntityId()).isEqualTo(TARGET_USER_ID);
        assertThat(entry.getChanges()).containsExactly(AuditChange.of("isOwner", false, true));
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
        AuditEntry entry = Audited.one(auditService, AuditAction.USER_BLOCKED);
        assertThat(entry.getActorType()).isEqualTo(uz.minipolya.minipolya_backend.audit.AuditActorType.ADMIN);
        assertThat(entry.getActorId()).isEqualTo(ADMIN_ID);
        assertThat(entry.getEntityLabel()).isEqualTo("U");
        assertThat(entry.getChanges()).containsExactly(AuditChange.of("isBlocked", false, true));
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
        assertThat(Audited.one(auditService, AuditAction.USER_UNBLOCKED).getChanges())
                .containsExactly(AuditChange.of("isBlocked", true, false));
    }

    @Test
    void anUnchangedBlockedFlagIsNotAudited() {
        User user = User.builder().id(TARGET_USER_ID).name("U").isBlocked(true).build();
        when(userRepository.findById(TARGET_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateUser(TARGET_USER_ID, AdminUpdateUserRequest.builder().isBlocked(true).build(), ADMIN_ID);

        assertThat(Audited.entries(auditService)).isEmpty();
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
        AuditEntry approved = Audited.one(auditService, AuditAction.STADIUM_APPROVED);
        assertThat(approved.getEntityId()).isEqualTo(STADIUM_ID);
        assertThat(approved.getChanges()).containsExactly(AuditChange.of("status", "pending", "approved"));
        // The owner flag the approval switched on is its own entry.
        assertThat(Audited.one(auditService, AuditAction.USER_ROLE_CHANGED).getChanges())
                .containsExactly(AuditChange.of("isOwner", false, true));
        verify(ownerLegalDetailsService).requireSignedContract(OWNER_ID);
    }

    @Test
    void approvalWaitsForASignedContractAndChangesNothingWithoutOne() {
        Stadium stadium = stadium("pending");
        stadium.getOwner().setOwner(false);
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        org.mockito.Mockito.doThrow(new uz.minipolya.minipolya_backend.exception.BadRequestException(
                        "The owner's ATMOS contract is not signed"))
                .when(ownerLegalDetailsService).requireSignedContract(OWNER_ID);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateStadiumStatus(STADIUM_ID,
                        AdminUpdateStadiumStatusRequest.builder().status("approved").build(), ADMIN_ID))
                .hasMessage("The owner's ATMOS contract is not signed");

        assertThat(stadium.getStatus()).isEqualTo("pending");
        assertThat(stadium.getOwner().isOwner()).isFalse();
        verify(stadiumRepository, never()).save(any(Stadium.class));
    }

    @Test
    void blockStadiumCancelsFutureBookingsAndRefunds() {
        Stadium stadium = stadium("approved");
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));
        Booking future = Booking.builder().id(BOOKING_ID).status("confirmed").price(100_000L)
                .user(User.builder().id(BOOKING_USER_ID).build()).startsAt(OffsetDateTime.now().plusDays(3)).build();
        when(bookingRepository.findByStadiumIdAndStartsAtBetween(eq(STADIUM_ID), any(), any())).thenReturn(List.of(future));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStadiumStatus(STADIUM_ID,
                AdminUpdateStadiumStatusRequest.builder().status("blocked").reason("fraud").build(), ADMIN_ID);

        assertThat(stadium.getStatus()).isEqualTo("blocked");
        assertThat(future.getStatus()).isEqualTo("cancelled_by_owner");
        verify(paymentService).refundAllCardPayments(BOOKING_ID, "fraud");

        AuditEntry blocked = Audited.one(auditService, AuditAction.STADIUM_BLOCKED);
        assertThat(blocked.getEntityLabel()).isEqualTo("Arena");
        assertThat(blocked.getChanges()).containsExactly(
                AuditChange.of("status", "approved", "blocked"), AuditChange.of("statusReason", null, "fraud"));
        assertThat(blocked.getReason()).isEqualTo("fraud");
        assertThat(stadium.getStatusReason()).isEqualTo("fraud");
        // Each booking the block cancelled is a SYSTEM consequence, with the same reason.
        AuditEntry cancelled = Audited.one(auditService, AuditAction.BOOKING_CANCELLED_BY_OWNER);
        assertThat(cancelled.getActorType()).isEqualTo(uz.minipolya.minipolya_backend.audit.AuditActorType.SYSTEM);
        assertThat(cancelled.getActorId()).isNull();
        assertThat(cancelled.getEntityId()).isEqualTo(BOOKING_ID);
        assertThat(cancelled.getChanges())
                .containsExactly(AuditChange.of("status", "confirmed", "cancelled_by_owner"));
        assertThat(cancelled.getReason()).isEqualTo("fraud");
    }

    @Test
    void rejectingAPendingStadiumMakesItRejectedWithTheReason() {
        Stadium stadium = stadium("pending");
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStadiumStatus(STADIUM_ID,
                AdminUpdateStadiumStatusRequest.builder().status("rejected").reason("Fake photos").build(), ADMIN_ID);

        assertThat(stadium.getStatus()).isEqualTo("rejected");
        assertThat(stadium.getStatusReason()).isEqualTo("Fake photos");
        AuditEntry entry = Audited.one(auditService, AuditAction.STADIUM_REJECTED);
        assertThat(entry.getChanges()).containsExactly(
                AuditChange.of("status", "pending", "rejected"), AuditChange.of("statusReason", null, "Fake photos"));
        assertThat(entry.getReason()).isEqualTo("Fake photos");
        verify(notificationService).notify(eq(OWNER_ID), eq(Notification.Type.STADIUM_REJECTED),
                anyString(), anyString(), eq(Notification.ReferenceType.STADIUM), eq(STADIUM_ID));
        // Nothing to cancel: a stadium never approved has no bookings.
        verify(bookingRepository, never()).findByStadiumIdAndStartsAtBetween(any(), any(), any());
    }

    @Test
    void onlyAPendingStadiumCanBeRejected() {
        Stadium stadium = stadium("approved");
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateStadiumStatus(STADIUM_ID,
                        AdminUpdateStadiumStatusRequest.builder().status("rejected").reason("Too late").build(), ADMIN_ID))
                .hasMessage("Only a pending stadium can be rejected");
        assertThat(stadium.getStatus()).isEqualTo("approved");
    }

    @Test
    void rejectingOrBlockingNeedsAReason() {
        Stadium stadium = stadium("pending");
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));

        for (String status : List.of("rejected", "blocked")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateStadiumStatus(STADIUM_ID,
                            AdminUpdateStadiumStatusRequest.builder().status(status).reason("  ").build(), ADMIN_ID))
                    .hasMessage("reason is required");
        }
        verify(stadiumRepository, never()).save(any(Stadium.class));
    }

    @Test
    void approvingClearsTheReason() {
        Stadium stadium = stadium("blocked");
        stadium.setStatusReason("fraud");
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStadiumStatus(STADIUM_ID, AdminUpdateStadiumStatusRequest.builder().status("approved").build(), ADMIN_ID);

        assertThat(stadium.getStatusReason()).isNull();
        assertThat(Audited.one(auditService, AuditAction.STADIUM_UNBLOCKED).getChanges()).containsExactly(
                AuditChange.of("status", "blocked", "approved"), AuditChange.of("statusReason", "fraud", null));
    }

    @Test
    void approvingABlockedStadiumIsAnUnblock() {
        Stadium stadium = stadium("blocked");
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStadiumStatus(STADIUM_ID,
                AdminUpdateStadiumStatusRequest.builder().status("approved").build(), ADMIN_ID);

        assertThat(Audited.one(auditService, AuditAction.STADIUM_UNBLOCKED).getChanges())
                .containsExactly(AuditChange.of("status", "blocked", "approved"));
        // The owner was an owner already: no role change to record.
        assertThat(Audited.entries(auditService, AuditAction.USER_ROLE_CHANGED)).isEmpty();
    }

    @Test
    void settingTheSameStatusAgainRecordsNothing() {
        Stadium stadium = stadium("approved");
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStadiumStatus(STADIUM_ID,
                AdminUpdateStadiumStatusRequest.builder().status("approved").build(), ADMIN_ID);

        assertThat(Audited.entries(auditService)).isEmpty();
    }

    @Test
    void aBlockKeepsTheReasonTheAdminGave() {
        User user = User.builder().id(TARGET_USER_ID).name("U").isBlocked(false).build();
        when(userRepository.findById(TARGET_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateUser(TARGET_USER_ID,
                AdminUpdateUserRequest.builder().isBlocked(true).reason("Spam bookings").build(), ADMIN_ID);

        assertThat(Audited.one(auditService, AuditAction.USER_BLOCKED).getReason()).isEqualTo("Spam bookings");
    }

    @Test
    void blockStadiumRefundsAGameBookingThroughTheGameNotTheBooking() {
        Stadium stadium = stadium("approved");
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID gameId = UUID.randomUUID();
        Booking gameBooking = Booking.builder().id(BOOKING_ID).status("confirmed").price(100_000L)
                .source("game").gameId(gameId)
                .user(User.builder().id(BOOKING_USER_ID).build())
                .startsAt(OffsetDateTime.now().plusDays(3)).build();
        when(bookingRepository.findByStadiumIdAndStartsAtBetween(eq(STADIUM_ID), any(), any()))
                .thenReturn(List.of(gameBooking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameService.cancelGameForBooking(eq(gameId), anyString())).thenReturn(80_000L);

        service.updateStadiumStatus(STADIUM_ID,
                AdminUpdateStadiumStatusRequest.builder().status("blocked").reason("fraud").build(), ADMIN_ID);

        // The teams' money hangs off game_teams, so refunding the booking's own
        // payments would give back nothing and hide the real refund.
        verify(gameService).cancelGameForBooking(eq(gameId), anyString());
        verify(paymentService, never()).refundAllCardPayments(eq(BOOKING_ID), anyString());
    }

    @Test
    void platformRevenueIsTheSharedDailyCalculationForTheLast30Days() {
        LocalDate today = LocalDate.now(TASHKENT);
        java.util.Map<LocalDate, Revenue> daily = new java.util.LinkedHashMap<>();
        for (LocalDate day = today.minusDays(29); !day.isAfter(today); day = day.plusDays(1)) {
            daily.put(day, Revenue.ZERO);
        }
        daily.put(today.minusDays(1), new Revenue(100_000L, 1_500L, 90_000L, 8_500L));
        when(revenueService.daily(today.minusDays(29), today)).thenReturn(daily);

        OwnerRevenueResponse revenue = service.getPlatformRevenue();

        assertThat(revenue.getDailyRevenue()).hasSize(30);
        assertThat(revenue.getDailyRevenue().get(28).getDate()).isEqualTo(today.minusDays(1));
        assertThat(revenue.getDailyRevenue().get(28).getRevenue()).isEqualTo(100_000);
        assertThat(revenue.getDailyRevenue().get(29).getRevenue()).isZero();
    }

    @Test
    void overviewRevenueFiguresAreAppRevenue() {
        when(revenueService.between(any(), any()))
                .thenReturn(new RevenueService.Summary(new Revenue(500_000L, 7_500L, 450_000L, 42_500L), 0));

        AdminPlatformStatsResponse stats = service.getPlatformStats();

        assertThat(stats.getTodayRevenue()).isEqualTo(500_000);
        assertThat(stats.getWeekRevenue()).isEqualTo(500_000);
        assertThat(stats.getMonthRevenue()).isEqualTo(500_000);
    }

}
