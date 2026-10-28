package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditActorType;
import uz.minipolya.minipolya_backend.audit.AuditChange;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.Audited;
import uz.minipolya.minipolya_backend.dto.request.CreatePricingRuleRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdatePricingRuleRequest;
import uz.minipolya.minipolya_backend.entity.PricingRule;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.mapper.PricingRuleMapper;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What owners' pricing changes write to the audit log. */
class PricingServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID STADIUM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID RULE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final PricingRuleRepository ruleRepository = mock(PricingRuleRepository.class);
    private final StadiumRepository stadiumRepository = mock(StadiumRepository.class);
    private final PricingRuleMapper mapper = mock(PricingRuleMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private PricingService service;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        service = new PricingService(ruleRepository, stadiumRepository, mapper, auditService);
        stadium = Stadium.builder().id(STADIUM_ID).name("Arena")
                .owner(User.builder().id(OWNER_ID).name("Owner").build()).build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(ruleRepository.findByStadiumIdAndDayOfWeek(any(), any())).thenReturn(List.of());
        when(ruleRepository.save(any(PricingRule.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PricingRule rule() {
        return PricingRule.builder().id(RULE_ID).stadium(stadium).dayOfWeek(4)
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(22, 0)).pricePerHour(20_000_000L).build();
    }

    @Test
    void aNewRuleRecordsEveryValueItWasCreatedWith() {
        when(mapper.toEntity(any())).thenReturn(rule());

        service.create(OWNER_ID, STADIUM_ID, CreatePricingRuleRequest.builder()
                .dayOfWeek(4).startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(22, 0))
                .pricePerHour(20_000_000L).build());

        AuditEntry entry = Audited.one(auditService, AuditAction.PRICING_RULE_CREATED);
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.OWNER);
        assertThat(entry.getEntityLabel()).isEqualTo("Arena, FRIDAY 18:00–22:00");
        assertThat(entry.getChanges()).containsExactly(
                AuditChange.of("dayOfWeek", null, 4),
                AuditChange.of("startTime", null, "18:00"),
                AuditChange.of("endTime", null, "22:00"),
                AuditChange.of("pricePerHour", null, 20_000_000L));
    }

    @Test
    void aSlotPriceChangeRecordsTheOldAndNewPrice() {
        PricingRule rule = rule();
        when(ruleRepository.findById(RULE_ID)).thenReturn(Optional.of(rule));
        doAnswer(inv -> {
            ((PricingRule) inv.getArgument(1)).setPricePerHour(25_000_000L);
            return null;
        }).when(mapper).updateFromRequest(any(), any());

        service.update(OWNER_ID, STADIUM_ID, RULE_ID, UpdatePricingRuleRequest.builder().build());

        assertThat(Audited.one(auditService, AuditAction.PRICING_RULE_UPDATED).getChanges())
                .containsExactly(AuditChange.of("pricePerHour", 20_000_000L, 25_000_000L));
    }

    @Test
    void deletingARuleRecordsWhatIsGone() {
        when(ruleRepository.findById(RULE_ID)).thenReturn(Optional.of(rule()));

        service.softDelete(OWNER_ID, STADIUM_ID, RULE_ID);

        assertThat(Audited.one(auditService, AuditAction.PRICING_RULE_DELETED).getChanges()).containsExactly(
                AuditChange.of("isActive", true, false),
                AuditChange.of("pricePerHour", 20_000_000L, null));
    }
}
