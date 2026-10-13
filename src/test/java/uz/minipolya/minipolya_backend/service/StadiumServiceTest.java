package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.response.StadiumDetailResponse;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.StadiumMapper;
import uz.minipolya.minipolya_backend.repository.FavoriteRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private StadiumService stadiumService;

    @Test
    void createForcesPendingStatusAndAttachesOwnerAndAmenities() {
        User owner = User.builder().id(10L).name("Owner").build();
        when(userRepository.findById(10L)).thenReturn(Optional.of(owner));

        CreateStadiumRequest request = CreateStadiumRequest.builder().name("Yunusobod Arena").build();
        Stadium entity = new Stadium();
        when(stadiumMapper.toEntity(request)).thenReturn(entity);
        when(stadiumRepository.save(any(Stadium.class))).thenAnswer(inv -> inv.getArgument(0));
        StadiumDetailResponse dto = new StadiumDetailResponse();
        when(stadiumMapper.toDetailResponse(any(Stadium.class))).thenReturn(dto);

        StadiumDetailResponse result = stadiumService.create(10L, request);

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
    void updateRejectsForeignOwner() {
        User otherOwner = User.builder().id(99L).build();
        Stadium stadium = Stadium.builder().id(1L).owner(otherOwner).build();
        when(stadiumRepository.findById(1L)).thenReturn(Optional.of(stadium));

        assertThatThrownBy(() -> stadiumService.update(10L, 1L, new UpdateStadiumRequest()))
                .isInstanceOf(ForbiddenException.class);

        verify(stadiumRepository, never()).save(any());
    }

    @Test
    void updateThrowsWhenStadiumMissing() {
        when(stadiumRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stadiumService.update(10L, 1L, new UpdateStadiumRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void publicDetailHidesUnapprovedStadium() {
        when(stadiumRepository.findByIdAndStatus(7L, "approved")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stadiumService.getPublicDetail(7L, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void publicDetailHidesInactiveStadium() {
        Stadium inactive = Stadium.builder().id(7L).status("approved").isActive(false).build();
        when(stadiumRepository.findByIdAndStatus(7L, "approved")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> stadiumService.getPublicDetail(7L, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
