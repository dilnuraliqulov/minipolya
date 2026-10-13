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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotionServiceTest {

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

        owner = User.builder().id(2L).name("Owner").build();
        stadium = Stadium.builder().id(1L).name("Arena").owner(owner).build();
    }

    private CreatePromotionRequest request() {
        return CreatePromotionRequest.builder()
                .stadiumId(1L).name("Weekend 20%").discountPercent(20)
                .startsAt(OffsetDateTime.now(ZoneOffset.UTC))
                .endsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                .build();
    }

    @Test
    void createPromotionSucceeds() {
        when(stadiumRepository.findById(1L)).thenReturn(Optional.of(stadium));
        when(promotionMapper.toEntity(any())).thenReturn(Promotion.builder().discountPercent(20).build());
        when(promotionRepository.save(any(Promotion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promotionMapper.toResponse(any(Promotion.class))).thenReturn(new PromotionResponse());

        service.createPromotion(request(), 2L);

        verify(promotionRepository).save(any(Promotion.class));
    }

    @Test
    void createPromotionForForeignStadiumIsForbidden() {
        when(stadiumRepository.findById(1L)).thenReturn(Optional.of(stadium));

        assertThatThrownBy(() -> service.createPromotion(request(), 999L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getDiscountPercentReturnsMatchingPromotion() {
        when(promotionRepository.findMatching(eq(1L), any(), anyInt(), any()))
                .thenReturn(List.of(Promotion.builder().discountPercent(20).build()));

        assertThat(service.getDiscountPercent(1L, OffsetDateTime.now(ZoneOffset.UTC))).isEqualTo(20);
    }

    @Test
    void getDiscountPercentReturnsHighestWhenMultiple() {
        when(promotionRepository.findMatching(eq(1L), any(), anyInt(), any())).thenReturn(List.of(
                Promotion.builder().discountPercent(20).build(),
                Promotion.builder().discountPercent(50).build()));

        assertThat(service.getDiscountPercent(1L, OffsetDateTime.now(ZoneOffset.UTC))).isEqualTo(50);
    }

    @Test
    void getDiscountPercentReturnsNullWhenNoMatch() {
        when(promotionRepository.findMatching(eq(1L), any(), anyInt(), any())).thenReturn(List.of());

        assertThat(service.getDiscountPercent(1L, OffsetDateTime.now(ZoneOffset.UTC))).isNull();
    }

    @Test
    void deactivatePromotionSetsInactive() {
        Promotion promotion = Promotion.builder().id(5L).stadium(stadium).discountPercent(20).isActive(true).build();
        when(promotionRepository.findById(5L)).thenReturn(Optional.of(promotion));
        when(promotionRepository.save(any(Promotion.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivatePromotion(5L, 2L);

        assertThat(promotion.isActive()).isFalse();
    }

    @Test
    void expireEndedPromotionsDeactivatesThem() {
        Promotion a = Promotion.builder().id(1L).isActive(true).build();
        Promotion b = Promotion.builder().id(2L).isActive(true).build();
        when(promotionRepository.findByIsActiveTrueAndEndsAtBefore(any())).thenReturn(List.of(a, b));

        int count = service.expireEndedPromotions();

        assertThat(count).isEqualTo(2);
        assertThat(a.isActive()).isFalse();
        assertThat(b.isActive()).isFalse();
        verify(promotionRepository).saveAll(List.of(a, b));
    }
}
