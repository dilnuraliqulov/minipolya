package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.request.AdminUpdateStadiumStatusRequest;
import uz.minipolya.minipolya_backend.dto.request.AdminUpdateUserRequest;
import uz.minipolya.minipolya_backend.dto.response.AdminPlatformStatsResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminStadiumResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminUserResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerRevenueResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerRevenueResponse.DailyRevenue;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.OwnerApplication;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.OwnerApplicationRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform admin aggregation and management. Admin HTTP access is gated to
 * ROLE_ADMIN in SecurityConfig; this service adds the business rules (e.g. an
 * admin cannot drop their own admin flag). Date boundaries use Tashkent time;
 * platform revenue counts positive completed payments only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final List<String> COUNTED_STATUSES = List.of("confirmed", "completed");

    private final UserRepository userRepository;
    private final StadiumRepository stadiumRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final GameRepository gameRepository;
    private final OwnerApplicationRepository ownerApplicationRepository;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final GameService gameService;
    private final RevenueService revenueService;
    private final AdminStadiumService adminStadiumService;
    private final OwnerLegalDetailsService ownerLegalDetailsService;

    @Transactional(readOnly = true)
    public AdminPlatformStatsResponse getPlatformStats() {
        LocalDate today = LocalDate.now(TASHKENT);
        OffsetDateTime todayStart = startOfDay(today);
        OffsetDateTime todayEnd = startOfDay(today.plusDays(1));
        LocalDate weekStartDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        OffsetDateTime weekStart = startOfDay(weekStartDate);
        OffsetDateTime weekEnd = startOfDay(weekStartDate.plusWeeks(1));
        OffsetDateTime monthStart = startOfDay(today.withDayOfMonth(1));
        OffsetDateTime monthEnd = startOfDay(today.withDayOfMonth(1).plusMonths(1));
        return AdminPlatformStatsResponse.builder()
                .totalUsers(userRepository.count())
                .totalOwners(userRepository.countByIsOwnerTrue())
                .newUsersToday(userRepository.countByCreatedAtAfter(todayStart))
                .newUsersThisWeek(userRepository.countByCreatedAtAfter(weekStart))
                .newUsersThisMonth(userRepository.countByCreatedAtAfter(monthStart))
                .totalStadiums(stadiumRepository.count())
                .approvedStadiums(stadiumRepository.countByStatus("approved"))
                .pendingStadiums(stadiumRepository.countByStatus("pending"))
                .blockedStadiums(stadiumRepository.countByStatus("blocked"))
                .totalBookings(bookingRepository.countByStatusIn(COUNTED_STATUSES))
                .todayBookings(bookingRepository.countByStatusInAndStartsAtBetween(COUNTED_STATUSES, todayStart, todayEnd))
                .weekBookings(bookingRepository.countByStatusInAndStartsAtBetween(COUNTED_STATUSES, weekStart, weekEnd))
                .monthBookings(bookingRepository.countByStatusInAndStartsAtBetween(COUNTED_STATUSES, monthStart, monthEnd))
                // Card money kept, cash excluded: the same calculation as /api/admin/payments/summary.
                .totalRevenue(revenueService.total().appRevenue())
                .todayRevenue(appRevenue(todayStart, todayEnd))
                .weekRevenue(appRevenue(weekStart, weekEnd))
                .monthRevenue(appRevenue(monthStart, monthEnd))
                .totalGames(gameRepository.count())
                .activeGames(gameRepository.countByStatus("open"))
                .pendingOwnerApplications(ownerApplicationRepository.countByStatus(OwnerApplication.Status.PENDING))
                .pendingStadiumApprovals(stadiumRepository.countByStatus("pending"))
                .failedRefunds(paymentRepository.countByPaymentTypeAndStatus(
                        Payment.PaymentType.REFUND, Payment.Status.FAILED))
                .oldestFailedRefundAt(paymentRepository.findFirstByPaymentTypeAndStatusOrderByCreatedAtAsc(
                                Payment.PaymentType.REFUND, Payment.Status.FAILED)
                        .map(Payment::getCreatedAt)
                        .orElse(null))
                .build();
    }

    /**
     * The overview chart: card money kept on each of the last 30 Tashkent days, every day
     * present — the same calculation as the payments summary.
     */
    @Transactional(readOnly = true)
    public OwnerRevenueResponse getPlatformRevenue() {
        LocalDate today = LocalDate.now(TASHKENT);
        List<DailyRevenue> daily = revenueService.daily(today.minusDays(29), today).entrySet().stream()
                .map(e -> DailyRevenue.builder().date(e.getKey()).revenue(e.getValue().appRevenue()).build())
                .toList();
        return OwnerRevenueResponse.builder().stadiumId(null).dailyRevenue(daily).build();
    }

    private long appRevenue(OffsetDateTime from, OffsetDateTime to) {
        return revenueService.between(from, to).revenue().appRevenue();
    }

    // ---- Users ----

    @Transactional(readOnly = true)
    public Page<AdminUserResponse> getUsers(String search, Pageable pageable) {
        Page<User> page = (search == null || search.isBlank())
                ? userRepository.findAll(pageable)
                : userRepository.searchUsers(search.trim(), pageable);
        return page.map(this::buildUser);
    }

    @Transactional(readOnly = true)
    public AdminUserResponse getUser(UUID userId) {
        return buildUser(userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found")));
    }

    @Transactional
    public AdminUserResponse updateUser(UUID userId, AdminUpdateUserRequest request, UUID adminUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (Boolean.FALSE.equals(request.getIsAdmin()) && userId.equals(adminUserId)) {
            throw new BadRequestException("You cannot remove your own admin access");
        }
        if (Boolean.TRUE.equals(request.getIsBlocked()) && userId.equals(adminUserId)) {
            throw new BadRequestException("You cannot block yourself");
        }

        boolean wasOwner = user.isOwner();
        boolean wasAdmin = user.isAdmin();
        boolean wasBlocked = user.isBlocked();
        if (request.getIsOwner() != null) {
            user.setOwner(request.getIsOwner());
        }
        if (request.getIsAdmin() != null) {
            user.setAdmin(request.getIsAdmin());
        }
        if (request.getIsBlocked() != null) {
            user.setBlocked(request.getIsBlocked());
        }
        userRepository.save(user);

        String label = AuditLabels.user(user);
        if (wasOwner != user.isOwner() || wasAdmin != user.isAdmin()) {
            auditService.record(AuditEntry.admin(adminUserId, AuditAction.USER_ROLE_CHANGED)
                    .entity(AuditEntityType.USER, userId, label)
                    .change("isOwner", wasOwner, user.isOwner())
                    .change("isAdmin", wasAdmin, user.isAdmin())
                    .reason(request.getReason()));
        }
        if (wasBlocked != user.isBlocked()) {
            auditService.record(AuditEntry.admin(adminUserId,
                            user.isBlocked() ? AuditAction.USER_BLOCKED : AuditAction.USER_UNBLOCKED)
                    .entity(AuditEntityType.USER, userId, label)
                    .change("isBlocked", wasBlocked, user.isBlocked())
                    .reason(request.getReason()));
        }
        log.info("Admin {} updated user {}: owner={} admin={} blocked={}",
                adminUserId, userId, user.isOwner(), user.isAdmin(), user.isBlocked());
        return buildUser(user);
    }

    // ---- Stadiums (the list and detail are AdminStadiumService) ----

    @Transactional
    public AdminStadiumResponse updateStadiumStatus(UUID stadiumId, AdminUpdateStadiumStatusRequest request,
                                                    UUID adminUserId) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        String status = request.getStatus().trim();
        String reason = request.getReason();
        String oldStatus = stadium.getStatus();
        if ("approved".equals(status) && !"approved".equals(oldStatus)) {
            // Players will pay this owner by card: the payouts need a signed ATMOS contract.
            ownerLegalDetailsService.requireSignedContract(stadium.getOwner().getId());
        }
        stadium.setStatus(status);
        stadiumRepository.save(stadium);
        UUID ownerId = stadium.getOwner().getId();
        if (!status.equals(oldStatus)) {
            auditService.record(AuditEntry.admin(adminUserId, statusAction(oldStatus, status))
                    .entity(AuditEntityType.STADIUM, stadiumId, AuditLabels.stadium(stadium))
                    .change("status", oldStatus, status)
                    .reason(reason));
        }

        switch (status) {
            case "approved" -> {
                User owner = stadium.getOwner();
                if (!owner.isOwner()) {
                    owner.setOwner(true);
                    userRepository.save(owner);
                    auditService.record(AuditEntry.admin(adminUserId, AuditAction.USER_ROLE_CHANGED)
                            .entity(AuditEntityType.USER, owner.getId(), AuditLabels.user(owner))
                            .change("isOwner", false, true)
                            .reason("Stadium approved: " + stadium.getName()));
                }
                notify(ownerId, Notification.Type.STADIUM_APPROVED, "Стадион одобрен",
                        "Ваш стадион «" + stadium.getName() + "» одобрен!",
                        Notification.ReferenceType.STADIUM, stadiumId);
            }
            case "blocked" -> {
                notify(ownerId, Notification.Type.STADIUM_REJECTED, "Стадион заблокирован",
                        "Ваш стадион «" + stadium.getName() + "» заблокирован. Причина: "
                                + (reason == null ? "не указана" : reason),
                        Notification.ReferenceType.STADIUM, stadiumId);
                cancelFutureBookings(stadium, reason);
            }
            default -> {
                // pending: nothing beyond the status itself, which is audited above.
            }
        }
        log.info("Admin {} set stadium {} status to {}", adminUserId, stadiumId, status);
        return buildStadium(stadium);
    }

    // ---- Helpers ----

    /** Which code a stadium status change gets: the same "blocked" is a rejection for a stadium never approved. */
    private static AuditAction statusAction(String oldStatus, String newStatus) {
        return switch (newStatus) {
            case "approved" -> "blocked".equals(oldStatus) ? AuditAction.STADIUM_UNBLOCKED : AuditAction.STADIUM_APPROVED;
            case "blocked" -> "pending".equals(oldStatus) ? AuditAction.STADIUM_REJECTED : AuditAction.STADIUM_BLOCKED;
            default -> AuditAction.STADIUM_STATUS_CHANGED;
        };
    }

    private void cancelFutureBookings(Stadium stadium, String reason) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String cancelReason = reason == null ? "Stadium blocked" : reason;
        for (Booking booking : bookingRepository.findByStadiumIdAndStartsAtBetween(
                stadium.getId(), now, now.plusYears(5))) {
            if (!"pending".equals(booking.getStatus()) && !"confirmed".equals(booking.getStatus())) {
                continue;
            }
            String oldStatus = booking.getStatus();
            booking.setStatus("cancelled_by_owner");
            booking.setCancelledAt(now);
            booking.setCancellationReason(cancelReason);
            bookingRepository.save(booking);
            // A consequence of the block, not something anyone did to this booking by hand.
            auditService.record(AuditEntry.system(AuditAction.BOOKING_CANCELLED_BY_OWNER)
                    .entity(AuditEntityType.BOOKING, booking.getId(), AuditLabels.booking(booking))
                    .change("status", oldStatus, booking.getStatus())
                    .reason(cancelReason));
            // Blocking a stadium is the platform's doing, so it follows the
            // owner rule: every card payment back in full, whatever the window.
            // A game booking holds none of its teams' money, so the game gives
            // that back instead.
            Long refundedByGame = "game".equals(booking.getSource()) && booking.getGameId() != null
                    ? gameService.cancelGameForBooking(booking.getGameId(), cancelReason)
                    : null;
            if (refundedByGame == null) {
                paymentService.refundAllCardPayments(booking.getId(), cancelReason);
            }
            if (booking.getUser() != null) {
                notify(booking.getUser().getId(), Notification.Type.BOOKING_CANCELLED, "Бронь отменена",
                        "Стадион заблокирован — ваша бронь отменена, возврат средств оформлен.",
                        Notification.ReferenceType.BOOKING, booking.getId());
            }
        }
    }

    private AdminUserResponse buildUser(User user) {
        return AdminUserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .phone(user.getPhone())
                .telegramId(user.getTelegramId())
                .language(user.getLanguage())
                .isOwner(user.isOwner())
                .isAdmin(user.isAdmin())
                .isBlocked(user.isBlocked())
                .bookingCount((int) bookingRepository.countByUserId(user.getId()))
                .stadiumCount(user.isOwner() ? (int) stadiumRepository.countByOwnerId(user.getId()) : 0)
                .createdAt(user.getCreatedAt())
                .build();
    }

    private AdminStadiumResponse buildStadium(Stadium stadium) {
        return adminStadiumService.toResponse(stadium);
    }

    private void notify(UUID userId, Notification.Type type, String title, String message,
                        Notification.ReferenceType referenceType, UUID referenceId) {
        try {
            notificationService.notify(userId, type, title, message, referenceType, referenceId);
        } catch (Exception ex) {
            log.warn("Admin notification failed: {}", ex.toString());
        }
    }

    private OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(TASHKENT).toOffsetDateTime();
    }
}
