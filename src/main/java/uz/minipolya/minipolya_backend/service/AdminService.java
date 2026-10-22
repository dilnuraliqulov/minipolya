package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
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
    private final ReviewRepository reviewRepository;
    private final OwnerApplicationRepository ownerApplicationRepository;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final AuditService auditService;

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
        Payment.Status completed = Payment.Status.COMPLETED;

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
                .totalRevenue(paymentRepository.sumTotalRevenue(completed))
                .todayRevenue(paymentRepository.sumRevenueAfter(completed, todayStart))
                .weekRevenue(paymentRepository.sumRevenueAfter(completed, weekStart))
                .monthRevenue(paymentRepository.sumRevenueAfter(completed, monthStart))
                .totalGames(gameRepository.count())
                .activeGames(gameRepository.countByStatus("open"))
                .pendingOwnerApplications(ownerApplicationRepository.countByStatus(OwnerApplication.Status.PENDING))
                .pendingStadiumApprovals(stadiumRepository.countByStatus("pending"))
                .build();
    }

    @Transactional(readOnly = true)
    public OwnerRevenueResponse getPlatformRevenue() {
        LocalDate today = LocalDate.now(TASHKENT);
        OffsetDateTime from = startOfDay(today.minusDays(29));
        Map<LocalDate, int[]> byDate = new LinkedHashMap<>();
        for (Payment payment : paymentRepository.findPositiveCompletedSince(Payment.Status.COMPLETED, from)) {
            LocalDate date = payment.getCreatedAt().atZoneSameInstant(TASHKENT).toLocalDate();
            int[] agg = byDate.computeIfAbsent(date, d -> new int[2]);
            agg[0] += payment.getAmount();
            agg[1] += 1;
        }
        List<DailyRevenue> daily = byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> DailyRevenue.builder()
                        .date(e.getKey()).revenue(e.getValue()[0]).bookingCount(e.getValue()[1]).build())
                .toList();
        return OwnerRevenueResponse.builder().stadiumId(null).dailyRevenue(daily).build();
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

        boolean ownerChanged = request.getIsOwner() != null && request.getIsOwner() != user.isOwner();
        boolean adminChanged = request.getIsAdmin() != null && request.getIsAdmin() != user.isAdmin();
        boolean blockedChanged = request.getIsBlocked() != null && request.getIsBlocked() != user.isBlocked();
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

        if (ownerChanged) {
            auditService.log(adminUserId, "USER_ROLE_CHANGED", "user", userId, "{\"isOwner\":" + user.isOwner() + "}");
        }
        if (adminChanged) {
            auditService.log(adminUserId, "USER_ROLE_CHANGED", "user", userId, "{\"isAdmin\":" + user.isAdmin() + "}");
        }
        if (blockedChanged) {
            auditService.log(adminUserId, user.isBlocked() ? "USER_BLOCKED" : "USER_UNBLOCKED", "user", userId,
                    "{\"isBlocked\":" + user.isBlocked() + "}");
        }
        log.info("Admin {} updated user {}: owner={} admin={} blocked={}",
                adminUserId, userId, user.isOwner(), user.isAdmin(), user.isBlocked());
        return buildUser(user);
    }

    // ---- Stadiums ----

    @Transactional(readOnly = true)
    public Page<AdminStadiumResponse> getStadiums(String status, String search, Pageable pageable) {
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasSearch = search != null && !search.isBlank();
        Page<Stadium> page;
        if (hasStatus && hasSearch) {
            page = stadiumRepository.searchStadiumsByStatus(status.trim(), search.trim(), pageable);
        } else if (hasStatus) {
            page = stadiumRepository.findByStatus(status.trim(), pageable);
        } else if (hasSearch) {
            page = stadiumRepository.searchStadiums(search.trim(), pageable);
        } else {
            page = stadiumRepository.findAll(pageable);
        }
        return page.map(this::buildStadium);
    }

    @Transactional(readOnly = true)
    public AdminStadiumResponse getStadium(UUID stadiumId) {
        return buildStadium(stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found")));
    }

    @Transactional
    public AdminStadiumResponse updateStadiumStatus(UUID stadiumId, AdminUpdateStadiumStatusRequest request,
                                                    UUID adminUserId) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        String status = request.getStatus().trim();
        String reason = request.getReason();
        stadium.setStatus(status);
        stadiumRepository.save(stadium);
        UUID ownerId = stadium.getOwner().getId();

        switch (status) {
            case "approved" -> {
                User owner = stadium.getOwner();
                if (!owner.isOwner()) {
                    owner.setOwner(true);
                    userRepository.save(owner);
                }
                notify(ownerId, Notification.Type.STADIUM_APPROVED, "Стадион одобрен",
                        "Ваш стадион «" + stadium.getName() + "» одобрен!",
                        Notification.ReferenceType.STADIUM, stadiumId);
                auditService.log(adminUserId, "STADIUM_APPROVED", "stadium", stadiumId);
            }
            case "blocked" -> {
                notify(ownerId, Notification.Type.STADIUM_REJECTED, "Стадион заблокирован",
                        "Ваш стадион «" + stadium.getName() + "» заблокирован. Причина: "
                                + (reason == null ? "не указана" : reason),
                        Notification.ReferenceType.STADIUM, stadiumId);
                cancelFutureBookings(stadium, reason);
                auditService.log(adminUserId, "STADIUM_BLOCKED", "stadium", stadiumId);
            }
            default -> auditService.log(adminUserId, "STADIUM_STATUS_CHANGED", "stadium", stadiumId,
                    "{\"status\":\"" + status + "\"}");
        }
        log.info("Admin {} set stadium {} status to {}", adminUserId, stadiumId, status);
        return buildStadium(stadium);
    }

    // ---- Helpers ----

    private void cancelFutureBookings(Stadium stadium, String reason) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String cancelReason = reason == null ? "Stadium blocked" : reason;
        for (Booking booking : bookingRepository.findByStadiumIdAndStartsAtBetween(
                stadium.getId(), now, now.plusYears(5))) {
            if (!"pending".equals(booking.getStatus()) && !"confirmed".equals(booking.getStatus())) {
                continue;
            }
            booking.setStatus("cancelled_by_owner");
            booking.setCancelledAt(now);
            booking.setCancellationReason(cancelReason);
            bookingRepository.save(booking);
            paymentService.refundBookingPayment(booking.getId(), booking.getPrice(), cancelReason);
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
        Double avg = reviewRepository.averageRatingByStadiumId(stadium.getId());
        return AdminStadiumResponse.builder()
                .id(stadium.getId())
                .name(stadium.getName())
                .address(stadium.getAddress())
                .ownerName(stadium.getOwner().getName())
                .ownerId(stadium.getOwner().getId())
                .status(stadium.getStatus())
                .format(stadium.getFormat())
                .surfaceType(stadium.getSurfaceType())
                .averageRating(avg == null ? 0.0 : avg)
                .reviewCount(reviewRepository.countByStadiumId(stadium.getId()))
                .totalBookings((int) bookingRepository.countByStadiumIdAndStatusIn(stadium.getId(), COUNTED_STATUSES))
                .totalRevenue((int) paymentRepository.sumRevenueByStadium(
                        stadium.getId(), Payment.Status.COMPLETED, Payment.PaymentType.BOOKING))
                .createdAt(stadium.getCreatedAt())
                .build();
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
