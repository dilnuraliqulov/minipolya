package uz.minipolya.minipolya_backend.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @InjectMocks
    private StadiumService stadiumService;

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID PUBLIC_STADIUM_ID = UUID.randomUUID();

    @Test
    void createForcesPendingStatusAndAttachesOwnerAndAmenities() {
        User owner = User.builder().id(OWNER_ID).name("Owner").build();
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));

        CreateStadiumRequest request = CreateStadiumRequest.builder().name("Yunusobod Arena").build();
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

    // ---- min_booking_amount ----

    @Test
    void createRejectsAMinimumBelowTheAtmosFloor() {
        CreateStadiumRequest request = CreateStadiumRequest.builder()
                .name("Arena").address("Tashkent").format("5x5").surfaceType("artificial")
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0))
                .pricePerHour(10_000_000L)
                .minBookingAmount(49_999L) // one tiyin under 500 so'm
                .build();

        assertThatThrownBy(() -> stadiumService.create(OWNER_ID, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("50000");

        verify(stadiumRepository, never()).save(any());
    }

    @Test
    void createAcceptsAMinimumExactlyAtTheFloor() {
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(User.builder().id(OWNER_ID).build()));
        CreateStadiumRequest request = CreateStadiumRequest.builder()
                .name("Arena").address("Tashkent").format("5x5").surfaceType("artificial")
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0))
                .pricePerHour(10_000_000L)
                .minBookingAmount(Stadium.MIN_BOOKING_AMOUNT_FLOOR)
                .build();
        Stadium entity = Stadium.builder().id(STADIUM_ID).build();
        when(stadiumMapper.toEntity(request)).thenReturn(entity);
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));

        stadiumService.create(OWNER_ID, request);

        verify(stadiumRepository).save(any(Stadium.class));
    }

    @Test
    void updateRejectsAMinimumBelowTheAtmosFloor() {
        Stadium existing = Stadium.builder().id(STADIUM_ID)
                .owner(User.builder().id(OWNER_ID).build()).build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(existing));

        UpdateStadiumRequest request = UpdateStadiumRequest.builder().minBookingAmount(1_000L).build();

        assertThatThrownBy(() -> stadiumService.update(OWNER_ID, STADIUM_ID, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("50000");

        verify(stadiumRepository, never()).saveAndFlush(any());
    }

    @Test
    void aNullMinimumIsAllowedAndMeansFullPaymentUpFront() {
        Stadium existing = Stadium.builder().id(STADIUM_ID)
                .owner(User.builder().id(OWNER_ID).build()).minBookingAmount(100_000L).build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(existing));
        when(stadiumRepository.saveAndFlush(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));

        // A null field on a partial update means "leave it alone", so this must not throw.
        stadiumService.update(OWNER_ID, STADIUM_ID, UpdateStadiumRequest.builder().build());

        verify(stadiumRepository).saveAndFlush(existing);
    }
}
