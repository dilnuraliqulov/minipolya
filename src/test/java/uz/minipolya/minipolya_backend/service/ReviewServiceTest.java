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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();

    private ReviewRepository reviewRepository;
    private StadiumRepository stadiumRepository;
    private BookingRepository bookingRepository;
    private UserRepository userRepository;
    private FavoriteRepository favoriteRepository;
    private ReviewMapper reviewMapper;
    private StadiumMapper stadiumMapper;
    private NotificationService notificationService;

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
        notificationService = mock(NotificationService.class);

        reviewService = new ReviewService(reviewRepository, stadiumRepository, bookingRepository,
                userRepository, reviewMapper, notificationService);
        favoriteService = new FavoriteService(favoriteRepository, stadiumRepository, userRepository,
                reviewRepository, stadiumMapper);

        player = User.builder().id(PLAYER_ID).name("Player").build();
        owner = User.builder().id(OWNER_ID).name("Owner").build();
        stadium = Stadium.builder().id(STADIUM_ID).name("Arena").owner(owner).build();
    }

    private Booking completedBookingAtStadium() {
        return Booking.builder().id(BOOKING_ID).status("completed").user(player).stadium(stadium).build();
    }

    // ---- ReviewService ----

    @Test
    void createReviewWithCompletedBookingSucceeds() {
        CreateReviewRequest req = CreateReviewRequest.builder().stadiumId(STADIUM_ID).rating(5).comment("Great").build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(reviewRepository.findByStadiumIdAndUserId(STADIUM_ID, PLAYER_ID)).thenReturn(Optional.empty());
        when(bookingRepository.findByUserIdAndStatusIn(PLAYER_ID, List.of("completed")))
                .thenReturn(List.of(completedBookingAtStadium()));
        when(userRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reviewMapper.toResponse(any(Review.class))).thenReturn(new ReviewResponse());

        reviewService.createReview(req, PLAYER_ID);

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        Review review = captor.getValue();
        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getStadium()).isSameAs(stadium);
        assertThat(review.getUser()).isSameAs(player);
    }

    @Test
    void createReviewWithoutBookingIsRejected() {
        CreateReviewRequest req = CreateReviewRequest.builder().stadiumId(STADIUM_ID).rating(4).build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(reviewRepository.findByStadiumIdAndUserId(STADIUM_ID, PLAYER_ID)).thenReturn(Optional.empty());
        when(bookingRepository.findByUserIdAndStatusIn(PLAYER_ID, List.of("completed"))).thenReturn(List.of());

        assertThatThrownBy(() -> reviewService.createReview(req, PLAYER_ID)).isInstanceOf(BadRequestException.class);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createDuplicateReviewIsRejected() {
        CreateReviewRequest req = CreateReviewRequest.builder().stadiumId(STADIUM_ID).rating(4).build();
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(reviewRepository.findByStadiumIdAndUserId(STADIUM_ID, PLAYER_ID))
                .thenReturn(Optional.of(Review.builder().id(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> reviewService.createReview(req, PLAYER_ID)).isInstanceOf(BadRequestException.class);
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void ownerRepliesToReview() {
        UUID reviewId = UUID.randomUUID();
        Review review = Review.builder().id(reviewId).stadium(stadium).user(player).rating(5).build();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reviewMapper.toResponse(any(Review.class))).thenReturn(new ReviewResponse());

        reviewService.replyToReview(reviewId, "Thanks for playing!", OWNER_ID);

        assertThat(review.getOwnerReply()).isEqualTo("Thanks for playing!");
        assertThat(review.getOwnerReplyAt()).isNotNull();
    }

    @Test
    void nonOwnerCannotReply() {
        UUID reviewId = UUID.randomUUID();
        UUID nonOwnerId = UUID.randomUUID();
        Review review = Review.builder().id(reviewId).stadium(stadium).user(player).rating(5).build();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.replyToReview(reviewId, "Hi", nonOwnerId))
                .isInstanceOf(ForbiddenException.class);
        verify(reviewRepository, never()).save(any());
    }

    // ---- FavoriteService ----

    @Test
    void toggleFavoriteOnReturnsTrue() {
        when(favoriteRepository.existsByUserIdAndStadiumId(PLAYER_ID, STADIUM_ID)).thenReturn(false);
        when(userRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));

        boolean result = favoriteService.toggleFavorite(STADIUM_ID, PLAYER_ID);

        assertThat(result).isTrue();
        verify(favoriteRepository).save(any(Favorite.class));
    }

    @Test
    void toggleFavoriteOffReturnsFalse() {
        when(favoriteRepository.existsByUserIdAndStadiumId(PLAYER_ID, STADIUM_ID)).thenReturn(true);

        boolean result = favoriteService.toggleFavorite(STADIUM_ID, PLAYER_ID);

        assertThat(result).isFalse();
        verify(favoriteRepository).deleteByUserIdAndStadiumId(PLAYER_ID, STADIUM_ID);
        verify(favoriteRepository, never()).save(any());
    }
}
