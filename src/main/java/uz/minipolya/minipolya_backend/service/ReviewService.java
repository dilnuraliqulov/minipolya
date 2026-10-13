package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.request.CreateReviewRequest;
import uz.minipolya.minipolya_backend.dto.response.ReviewResponse;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Review;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.ReviewMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Stadium reviews. A player may leave one review per stadium, and only after
 * having a completed booking there. Owners can add a single reply per review.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final StadiumRepository stadiumRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final ReviewMapper reviewMapper;
    private final NotificationService notificationService;

    @Transactional
    public ReviewResponse createReview(CreateReviewRequest request, Long userId) {
        Stadium stadium = stadiumRepository.findById(request.getStadiumId())
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));

        if (reviewRepository.findByStadiumIdAndUserId(stadium.getId(), userId).isPresent()) {
            throw new BadRequestException("You have already reviewed this stadium");
        }
        if (!hasCompletedBooking(userId, stadium.getId())) {
            throw new BadRequestException("You can only review a stadium you have played at");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Review review = Review.builder()
                .stadium(stadium)
                .user(user)
                .bookingId(request.getBookingId())
                .rating(request.getRating())
                .comment(request.getComment())
                .build();
        Review saved = reviewRepository.save(review);
        log.info("Review created: id={} stadiumId={} userId={} rating={}",
                saved.getId(), stadium.getId(), userId, saved.getRating());
        return reviewMapper.toResponse(saved);
    }

    @Transactional
    public ReviewResponse replyToReview(Long reviewId, String reply, Long ownerUserId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        if (!review.getStadium().getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("Only the stadium owner can reply to this review");
        }
        review.setOwnerReply(reply);
        review.setOwnerReplyAt(OffsetDateTime.now(ZoneOffset.UTC));
        Review saved = reviewRepository.save(review);
        Long reviewerId = review.getUser().getId();
        try {
            notificationService.notify(reviewerId, Notification.Type.REVIEW_REPLY, "Ответ на отзыв",
                    "Владелец стадиона ответил на ваш отзыв о «" + review.getStadium().getName() + "».",
                    Notification.ReferenceType.REVIEW, review.getId());
        } catch (Exception ex) {
            log.warn("Review-reply notification failed: {}", ex.toString());
        }
        log.info("Owner replied to review: id={} ownerId={}", reviewId, ownerUserId);
        return reviewMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getStadiumReviews(Long stadiumId, Pageable pageable) {
        return reviewRepository.findByStadiumIdOrderByCreatedAtDesc(stadiumId, pageable)
                .map(reviewMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getMyReviews(Long userId, Pageable pageable) {
        return reviewRepository.findByUserId(userId, pageable).map(reviewMapper::toResponse);
    }

    @Transactional
    public void deleteReview(Long reviewId, Long userId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found"));
        if (!review.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This review does not belong to you");
        }
        reviewRepository.delete(review);
        log.info("Review deleted: id={} userId={}", reviewId, userId);
    }

    private boolean hasCompletedBooking(Long userId, Long stadiumId) {
        return bookingRepository.findByUserIdAndStatusIn(userId, List.of("completed")).stream()
                .anyMatch(booking -> booking.getStadium().getId().equals(stadiumId));
    }
}
