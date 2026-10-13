package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.dto.request.CreateReviewRequest;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Favorite;
import uz.minipolya.minipolya_backend.entity.Review;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.dto.response.ReviewResponse;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.mapper.ReviewMapper;
import uz.minipolya.minipolya_backend.mapper.StadiumMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.FavoriteRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    private ReviewRepository reviewRepository;
    private StadiumRepository stadiumRepository;
    private BookingRepository bookingRepository;
    private UserRepository userRepository;
    private FavoriteRepository favoriteRepository;
    private ReviewMapper reviewMapper;
    private StadiumMapper stadiumMapper;

    private ReviewService reviewService;
    private FavoriteService favoriteService;

    private User player;
    private User owner;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        stadiumRepository = mock(StadiumRepository.class);
        bookingRepository = mock(BookingRepository.class);
        userRepository = mock(UserRepository.class);
        favoriteRepository = mock(FavoriteRepository.class);
        reviewMapper = mock(ReviewMapper.class);
        stadiumMapper = mock(StadiumMapper.class);

        reviewService = new ReviewService(reviewRepository, stadiumRepository, bookingRepository,
                userRepository, reviewMapper);
        favoriteService = new FavoriteService(favoriteRepository, stadiumRepository, userRepository,
                reviewRepository, stadiumMapper);

        player = User.builder().id(7L).name("Player").build();
        owner = User.builder().id(2L).name("Owner").build();
        stadium = Stadium.builder().id(1L).name("Arena").owner(owner).build();
    }

    private Booking completedBookingAtStadium() {
        return Booking.builder().id(5L).status("completed").user(player).stadium(stadium).build();
    }

    // ---- ReviewService ----

    @Test
    void createReviewWithCompletedBookingSucceeds() {
        CreateReviewRequest req = CreateReviewRequest.builder().stadiumId(1L).rating(5).comment("Great").build();
        when(stadiumRepository.findById(1L)).thenReturn(Optional.of(stadium));
        when(reviewRepository.findByStadiumIdAndUserId(1L, 7L)).thenReturn(Optional.empty());
        when(bookingRepository.findByUserIdAndStatusIn(7L, List.of("completed")))
                .thenReturn(List.of(completedBookingAtStadium()));
        when(userRepository.findById(7L)).thenReturn(Optional.of(player));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reviewMapper.toResponse(any(Review.class))).thenReturn(new ReviewResponse());

        reviewService.createReview(req, 7L);

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        Review review = captor.getValue();
        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getStadium()).isSameAs(stadium);
        assertThat(review.getUser()).isSameAs(player);
    }

    @Test
    void createReviewWithoutBookingIsRejected() {
        CreateReviewRequest req = CreateReviewRequest.builder().stadiumId(1L).rating(4).build();
        when(stadiumRepository.findById(1L)).thenReturn(Optional.of(stadium));
        when(reviewRepository.findByStadiumIdAndUserId(1L, 7L)).thenReturn(Optional.empty());
        when(bookingRepository.findByUserIdAndStatusIn(7L, List.of("completed"))).thenReturn(List.of());

        assertThatThrownBy(() -> reviewService.createReview(req, 7L)).isInstanceOf(BadRequestException.class);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createDuplicateReviewIsRejected() {
        CreateReviewRequest req = CreateReviewRequest.builder().stadiumId(1L).rating(4).build();
        when(stadiumRepository.findById(1L)).thenReturn(Optional.of(stadium));
        when(reviewRepository.findByStadiumIdAndUserId(1L, 7L))
                .thenReturn(Optional.of(Review.builder().id(99L).build()));

        assertThatThrownBy(() -> reviewService.createReview(req, 7L)).isInstanceOf(BadRequestException.class);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void ownerRepliesToReview() {
        Review review = Review.builder().id(10L).stadium(stadium).user(player).rating(5).build();
        when(reviewRepository.findById(10L)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reviewMapper.toResponse(any(Review.class))).thenReturn(new ReviewResponse());

        reviewService.replyToReview(10L, "Thanks for playing!", 2L);

        assertThat(review.getOwnerReply()).isEqualTo("Thanks for playing!");
        assertThat(review.getOwnerReplyAt()).isNotNull();
    }

    @Test
    void nonOwnerCannotReply() {
        Review review = Review.builder().id(10L).stadium(stadium).user(player).rating(5).build();
        when(reviewRepository.findById(10L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.replyToReview(10L, "Hi", 999L))
                .isInstanceOf(ForbiddenException.class);
        verify(reviewRepository, never()).save(any());
    }

    // ---- FavoriteService ----

    @Test
    void toggleFavoriteOnReturnsTrue() {
        when(favoriteRepository.existsByUserIdAndStadiumId(7L, 1L)).thenReturn(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(player));
        when(stadiumRepository.findById(1L)).thenReturn(Optional.of(stadium));

        boolean result = favoriteService.toggleFavorite(1L, 7L);

        assertThat(result).isTrue();
        verify(favoriteRepository).save(any(Favorite.class));
    }

    @Test
    void toggleFavoriteOffReturnsFalse() {
        when(favoriteRepository.existsByUserIdAndStadiumId(7L, 1L)).thenReturn(true);

        boolean result = favoriteService.toggleFavorite(1L, 7L);

        assertThat(result).isFalse();
        verify(favoriteRepository).deleteByUserIdAndStadiumId(7L, 1L);
        verify(favoriteRepository, never()).save(any());
    }
}
