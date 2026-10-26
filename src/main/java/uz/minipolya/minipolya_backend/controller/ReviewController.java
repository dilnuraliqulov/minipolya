package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.CreateReviewRequest;
import uz.minipolya.minipolya_backend.dto.request.ReplyToReviewRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.ReviewResponse;
import uz.minipolya.minipolya_backend.service.ReviewService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping("/api/reviews")
    public ApiResponse<ReviewResponse> create(@AuthenticationPrincipal UUID userId,
                                              @Valid @RequestBody CreateReviewRequest request) {
        return ApiResponse.ok(reviewService.createReview(request, userId), "Review posted");
    }

    @GetMapping("/api/reviews/stadium/{stadiumId}")
    public ApiResponse<PageResponse<ReviewResponse>> stadiumReviews(@PathVariable UUID stadiumId,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(
                reviewService.getStadiumReviews(stadiumId, pageable), Function.identity()));
    }

    @GetMapping("/api/reviews/my")
    public ApiResponse<PageResponse<ReviewResponse>> myReviews(@AuthenticationPrincipal UUID userId,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(
                reviewService.getMyReviews(userId, pageable), Function.identity()));
    }

    @PostMapping("/api/reviews/{id}/reply")
    public ApiResponse<ReviewResponse> reply(@AuthenticationPrincipal UUID ownerId,
                                             @PathVariable UUID id,
                                             @Valid @RequestBody ReplyToReviewRequest request) {
        return ApiResponse.ok(reviewService.replyToReview(id, request.getReply(), ownerId), "Reply posted");
    }

    @DeleteMapping("/api/reviews/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UUID userId,
                                    @PathVariable UUID id) {
        reviewService.deleteReview(id, userId);
        return ApiResponse.ok(null, "Review deleted");
    }
}
