package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import uz.minipolya.minipolya_backend.dto.response.AuditLogResponse;
import uz.minipolya.minipolya_backend.entity.AuditLog;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.mapper.AuditLogMapper;
import uz.minipolya.minipolya_backend.repository.AuditLogRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID PAYMENT_ID = UUID.randomUUID();

    private AuditLogRepository auditLogRepository;
    private UserRepository userRepository;
    private AuditLogMapper auditLogMapper;
    private AuditService service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        userRepository = mock(UserRepository.class);
        auditLogMapper = mock(AuditLogMapper.class);
        service = new AuditService(auditLogRepository, userRepository, auditLogMapper);
    }

    @Test
    void logCreatesRowWithCorrectFields() {
        User user = User.builder().id(USER_ID).name("Player").build();
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service.log(USER_ID, "BOOKING_CREATED", "booking", BOOKING_ID);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        org.mockito.Mockito.verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("BOOKING_CREATED");
        assertThat(saved.getEntityType()).isEqualTo("booking");
        assertThat(saved.getEntityId()).isEqualTo(BOOKING_ID);
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getDetails()).isNull();
    }

    @Test
    void logWithDetailsStoresDetails() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(User.builder().id(USER_ID).build());
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service.log(USER_ID, "PAYMENT_COMPLETED", "payment", PAYMENT_ID, "{\"amount\":150000}");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        org.mockito.Mockito.verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getDetails()).isEqualTo("{\"amount\":150000}");
    }

    @Test
    void logSwallowsExceptionsAndNeverThrows() {
        when(userRepository.getReferenceById(any())).thenReturn(User.builder().id(USER_ID).build());
        when(auditLogRepository.save(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.log(USER_ID, "BOOKING_CREATED", "booking", UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    void getAllReturnsMappedPage() {
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(new AuditLog())));
        when(auditLogMapper.toResponse(any())).thenReturn(new AuditLogResponse());

        assertThat(service.getAll(Pageable.ofSize(20)).getContent()).hasSize(1);
    }

    @Test
    void getByEntityReturnsMatchingLogs() {
        when(auditLogRepository.findByEntityTypeAndEntityId("booking", BOOKING_ID))
                .thenReturn(List.of(new AuditLog()));
        when(auditLogMapper.toResponse(any())).thenReturn(new AuditLogResponse());

        assertThat(service.getByEntity("booking", BOOKING_ID)).hasSize(1);
    }
}
