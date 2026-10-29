package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.AdminUserResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.mapper.AdminUserMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.repository.UserSpecifications;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Users for the admin panel, newest first. A page costs a fixed handful of queries: the
 * users, the count, and the booking and stadium counts for the whole page at once.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final StadiumRepository stadiumRepository;
    private final AdminUserMapper adminUserMapper;

    /**
     * @param role   {@code player} (neither owner nor admin), {@code owner} or {@code admin}
     * @param status {@code active} or {@code blocked}
     */
    @Transactional(readOnly = true)
    public Page<AdminUserResponse> list(String search, String role, String status, int page, int size) {
        UserSpecifications.Role parsedRole = parse(role, UserSpecifications.Role::valueOf, "Invalid role");
        UserSpecifications.Status parsedStatus = parse(status, UserSpecifications.Status::valueOf, "Invalid status");
        Pageable pageable = Pagination.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<User> users = userRepository.findAll(
                UserSpecifications.adminFilter(search, parsedRole, parsedStatus), pageable);
        Map<UUID, AdminUserResponse> byId = toResponses(users.getContent()).stream()
                .collect(Collectors.toMap(AdminUserResponse::getId, Function.identity()));
        return users.map(user -> byId.get(user.getId()));
    }

    /** One user as the list shows them. */
    @Transactional(readOnly = true)
    public AdminUserResponse toResponse(User user) {
        return toResponses(List.of(user)).get(0);
    }

    private List<AdminUserResponse> toResponses(List<User> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = users.stream().map(User::getId).toList();
        Map<UUID, Long> bookings = counts(bookingRepository.countByUserIds(ids));
        Map<UUID, Long> stadiums = counts(stadiumRepository.countByOwnerIds(ids));
        return users.stream().map(user -> {
            AdminUserResponse response = adminUserMapper.toResponse(user);
            response.setBookingCount(bookings.getOrDefault(user.getId(), 0L).intValue());
            response.setStadiumCount(user.isOwner() ? stadiums.getOrDefault(user.getId(), 0L).intValue() : 0);
            return response;
        }).toList();
    }

    private static Map<UUID, Long> counts(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    private static <T> T parse(String value, Function<String, T> parser, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parser.apply(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(message);
        }
    }
}
