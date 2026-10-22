package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.dto.request.CreatePromotionRequest;
import uz.minipolya.minipolya_backend.dto.response.PromotionResponse;
import uz.minipolya.minipolya_backend.entity.Promotion;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.mapper.PromotionMapper;
import uz.minipolya.minipolya_backend.repository.PromotionRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionServiceTest {

    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    private PromotionRepository promotionRepository;
    private StadiumRepository stadiumRepository;
    private PromotionMapper promotionMapper;
    private PromotionService service;

    private User owner;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        promotionRepository = mock(PromotionRepository.class);
        stadiumRepository = mock(StadiumRepository.class);
        promotionMapper = mock(PromotionMapper.class);
        service = new PromotionService(promotionRepository, stadiumRepository, promotionMapper);

        owner = User.builder().id(OWNER_ID).name("Owner").build();
        stadium = Stadium.builder().id(STADIUM_ID).name("Arena").owner(owner).build();
    }

    private CreatePromotionRequest request() {
        return CreatePromotionRequest.builder()
                .stadiumId(STADIUM_ID).name("Weekend 20%").discountPercent(20)
                .startsAt(OffsetDateTime.now(ZoneOffset.UTC))
                .endsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                .build();
    }

    @Test
    void createPromotionSucceeds() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(promotionMapper.toEntity(any())).thenReturn(Promotion.builder().discountPercent(20).build());
        when(promotionRepository.save(any(Promotion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promotionMapper.toResponse(any(Promotion.class))).thenReturn(new PromotionResponse());

        service.createPromotion(request(), OWNER_ID);

        verify(promotionRepository).save(any(Promotion.class));
    }

    @Test
    void createPromotionForForeignStadiumIsForbidden() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));

        assertThatThrownBy(() -> service.createPromotion(request(), UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getDiscountPercentReturnsMatchingPromotion() {
        when(promotionRepository.findMatching(eq(STADIUM_ID), any(), anyInt(), any()))
                .thenReturn(List.of(Promotion.builder().discountPercent(20).build()));

        assertThat(service.getDiscountPercent(STADIUM_ID, OffsetDateTime.now(ZoneOffset.UTC))).isEqualTo(20);
    }

    @Test
    void getDiscountPercentReturnsHighestWhenMultiple() {
        when(promotionRepository.findMatching(eq(STADIUM_ID), any(), anyInt(), any())).thenReturn(List.of(
                Promotion.builder().discountPercent(20).build(),
                Promotion.builder().discountPercent(50).build()));

        assertThat(service.getDiscountPercent(STADIUM_ID, OffsetDateTime.now(ZoneOffset.UTC))).isEqualTo(50);
    }

    @Test
    void getDiscountPercentReturnsNullWhenNoMatch() {
        when(promotionRepository.findMatching(eq(STADIUM_ID), any(), anyInt(), any())).thenReturn(List.of());

        assertThat(service.getDiscountPercent(STADIUM_ID, OffsetDateTime.now(ZoneOffset.UTC))).isNull();
    }

    @Test
    void deactivatePromotionSetsInactive() {
        UUID promotionId = UUID.randomUUID();
        Promotion promotion = Promotion.builder().id(promotionId).stadium(stadium).discountPercent(20).isActive(true).build();
        when(promotionRepository.findById(promotionId)).thenReturn(Optional.of(promotion));
        when(promotionRepository.save(any(Promotion.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivatePromotion(promotionId, OWNER_ID);

        assertThat(promotion.isActive()).isFalse();
    }

    @Test
    void expireEndedPromotionsDeactivatesThem() {
        Promotion a = Promotion.builder().id(UUID.randomUUID()).isActive(true).build();
        Promotion b = Promotion.builder().id(UUID.randomUUID()).isActive(true).build();
        when(promotionRepository.findByIsActiveTrueAndEndsAtBefore(any())).thenReturn(List.of(a, b));

        int count = service.expireEndedPromotions();

        assertThat(count).isEqualTo(2);
        assertThat(a.isActive()).isFalse();
        assertThat(b.isActive()).isFalse();
        verify(promotionRepository).saveAll(List.of(a, b));
    }
}
