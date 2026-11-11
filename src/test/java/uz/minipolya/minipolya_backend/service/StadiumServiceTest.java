package uz.minipolya.minipolya_backend.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditActorType;
import uz.minipolya.minipolya_backend.audit.AuditChange;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.Audited;
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.response.StadiumDetailResponse;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.StadiumMapper;
import uz.minipolya.minipolya_backend.repository.FavoriteRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StadiumServiceTest {

    @Mock
    private StadiumRepository stadiumRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private StadiumMapper stadiumMapper;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private FavoriteRepository favoriteRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private EntityManager entityManager;

    @org.mockito.Spy
    private CardPricing cardPricing = CardPricing.withDefaults();

    @InjectMocks
    private StadiumService stadiumService;

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID PUBLIC_STADIUM_ID = UUID.randomUUID();

    @Test
    void createForcesPendingStatusAndAttachesOwnerAndAmenities() {
        User owner = User.builder().id(OWNER_ID).name("Owner").build();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));

        CreateStadiumRequest request = validCreate();
        Stadium entity = new Stadium();
        when(stadiumMapper.toEntity(request)).thenReturn(entity);
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));
        StadiumDetailResponse dto = new StadiumDetailResponse();
        when(stadiumMapper.toDetailResponse(any(Stadium.class))).thenReturn(dto);

        StadiumDetailResponse result = stadiumService.create(OWNER_ID, request);

        assertThat(result).isSameAs(dto);
        ArgumentCaptor<Stadium> captor = ArgumentCaptor.forClass(Stadium.class);
        verify(stadiumRepository).save(captor.capture());
        Stadium saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("pending");
        assertThat(saved.getOwner()).isSameAs(owner);
        assertThat(saved.getAmenities()).isNotNull();
        assertThat(saved.getAmenities().getStadium()).isSameAs(saved);
    }

    @Test
    void updateFlushesAndRefreshesSoTheResponseCarriesTheStoredUpdatedAt() {
        User owner = User.builder().id(OWNER_ID).name("Owner").build();
        Stadium stadium = Stadium.builder().id(STADIUM_ID).owner(owner).build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.saveAndFlush(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));
        StadiumDetailResponse dto = new StadiumDetailResponse();
        when(stadiumMapper.toDetailResponse(any(Stadium.class))).thenReturn(dto);

        StadiumDetailResponse result = stadiumService.update(OWNER_ID, STADIUM_ID, new UpdateStadiumRequest());

        assertThat(result).isSameAs(dto);
        // The updated_at trigger runs in the database, so the entity must be reloaded
        // before it is mapped, otherwise the response reports the old timestamp.
        InOrder inOrder = inOrder(stadiumRepository, entityManager, stadiumMapper);
        inOrder.verify(stadiumRepository).saveAndFlush(stadium);
        inOrder.verify(entityManager).refresh(stadium);
        inOrder.verify(stadiumMapper).toDetailResponse(stadium);
    }

    @Test
    void anEditRecordsOnlyTheFieldsThatChanged() {
        User owner = User.builder().id(OWNER_ID).name("Owner").build();
        Stadium stadium = Stadium.builder().id(STADIUM_ID).owner(owner).name("Arena")
                .pricePerHour(15_000_000L).cancellationHours(24).build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.saveAndFlush(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));
        // The mapper is a mock here, so apply the request the way the real one would.
        org.mockito.Mockito.doAnswer(inv -> {
            Stadium target = inv.getArgument(1);
            target.setName("Arena");                 // sent, but the same value
            target.setPricePerHour(18_000_000L);     // 150 000 → 180 000 so'm
            target.setDescription("Covered");        // null → "Covered"
            return null;
        }).when(stadiumMapper).updateStadiumFromRequest(any(), any());

        stadiumService.update(OWNER_ID, STADIUM_ID, new UpdateStadiumRequest());

        AuditEntry entry = Audited.one(auditService, AuditAction.STADIUM_UPDATED);
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.OWNER);
        assertThat(entry.getEntityLabel()).isEqualTo("Arena");
        assertThat(entry.getChanges()).containsExactly(
                AuditChange.of("description", null, "Covered"),
                AuditChange.of("pricePerHour", 15_000_000L, 18_000_000L));
    }

    @Test
    void anEditThatChangesNothingRecordsNothing() {
        User owner = User.builder().id(OWNER_ID).name("Owner").build();
        Stadium stadium = Stadium.builder().id(STADIUM_ID).owner(owner).name("Arena").build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.saveAndFlush(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));

        stadiumService.update(OWNER_ID, STADIUM_ID, new UpdateStadiumRequest());

        assertThat(Audited.entries(auditService)).isEmpty();
    }

    @Test
    void updateRejectsForeignOwner() {
        User otherOwner = User.builder().id(UUID.randomUUID()).build();
        Stadium stadium = Stadium.builder().id(STADIUM_ID).owner(otherOwner).build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));

        assertThatThrownBy(() -> stadiumService.update(OWNER_ID, STADIUM_ID, new UpdateStadiumRequest()))
                .isInstanceOf(ForbiddenException.class);

        verify(stadiumRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateThrowsWhenStadiumMissing() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stadiumService.update(OWNER_ID, STADIUM_ID, new UpdateStadiumRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void publicDetailHidesUnapprovedStadium() {
        when(stadiumRepository.findByIdAndStatus(PUBLIC_STADIUM_ID, "approved")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stadiumService.getPublicDetail(PUBLIC_STADIUM_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void publicDetailHidesInactiveStadium() {
        Stadium inactive = Stadium.builder().id(PUBLIC_STADIUM_ID).status("approved").isActive(false).build();
        when(stadiumRepository.findByIdAndStatus(PUBLIC_STADIUM_ID, "approved")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> stadiumService.getPublicDetail(PUBLIC_STADIUM_ID, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- The add-stadium rules (StadiumRules) ----

    @Test
    void createRefusesAnInvalidRequestBeforeWritingAnything() {
        CreateStadiumRequest request = validCreate();
        request.setPhone(null);

        assertThatThrownBy(() -> stadiumService.create(OWNER_ID, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("phone is required");

        verify(stadiumRepository, never()).save(any());
    }

    @Test
    void updateRefusesHoursThatWouldCloseAfterMidnight() {
        Stadium existing = Stadium.builder().id(STADIUM_ID).owner(User.builder().id(OWNER_ID).build())
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0)).build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> stadiumService.update(OWNER_ID, STADIUM_ID,
                UpdateStadiumRequest.builder().closeTime(LocalTime.of(2, 0)).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("closeTime must be after openTime; closing after midnight is not supported");

        verify(stadiumRepository, never()).saveAndFlush(any());
    }

    private static CreateStadiumRequest validCreate() {
        return CreateStadiumRequest.builder()
                .name("Yunusobod Arena").address("Yunusobod 4, 12").district("Yunusobod")
                .phone("+998901234567").latitude(new BigDecimal("41.365")).longitude(new BigDecimal("69.285"))
                .format("5x5").surfaceType("artificial")
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0)).pricePerHour(15_000_000L)
                .build();
    }

    // ---- Card prices ----

    @Test
    void playersSeeOnePriceAndOwnersTheirOwnPriceWithThePlayerPrice() {
        User owner = User.builder().id(OWNER_ID).name("Owner").build();
        Stadium stadium = Stadium.builder().id(STADIUM_ID).owner(owner).status("approved").isActive(true)
                .pricePerHour(20_000_000L).minBookingAmount(5_000_000L).build();
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(stadiumMapper.toDetailResponse(any(Stadium.class))).thenAnswer(inv -> StadiumDetailResponse.builder()
                .pricePerHour(20_000_000L).minBookingAmount(5_000_000L).build());
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(stadiumRepository.saveAndFlush(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));

        StadiumDetailResponse player = stadiumService.getPublicDetail(STADIUM_ID, null);
        StadiumDetailResponse forOwner = stadiumService.update(OWNER_ID, STADIUM_ID, new UpdateStadiumRequest());

        assertThat(player.getPricePerHour()).isEqualTo(20_500_000L);
        assertThat(player.getMinBookingAmount()).isEqualTo(TestPrices.card(5_000_000L));
        assertThat(player.getPlayerPrice()).isNull();
        assertThat(player.getPlayerMinBookingAmount()).isNull();
        assertThat(player.getOwnerReceives()).isNull();
        assertThat(player.getAtmosFee()).isNull();

        assertThat(forOwner.getPricePerHour()).isEqualTo(20_000_000L);
        assertThat(forOwner.getPlayerPrice()).isEqualTo(20_500_000L);
        assertThat(forOwner.getMinBookingAmount()).isEqualTo(5_000_000L);
        assertThat(forOwner.getPlayerMinBookingAmount()).isEqualTo(TestPrices.card(5_000_000L));
    }
}
