package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.StadiumResponse;
import uz.minipolya.minipolya_backend.entity.Favorite;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.StadiumMapper;
import uz.minipolya.minipolya_backend.repository.FavoriteRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

/** A user's favorited stadiums, managed with a single add/remove toggle. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final ReviewRepository reviewRepository;
    private final StadiumMapper stadiumMapper;

    /** Adds the favorite if absent, removes it if present. @return true if now favorited. */
    @Transactional
    public boolean toggleFavorite(Long stadiumId, Long userId) {
        if (favoriteRepository.existsByUserIdAndStadiumId(userId, stadiumId)) {
            favoriteRepository.deleteByUserIdAndStadiumId(userId, stadiumId);
            log.info("Favorite removed: userId={} stadiumId={}", userId, stadiumId);
            return false;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        favoriteRepository.save(Favorite.builder().user(user).stadium(stadium).build());
        log.info("Favorite added: userId={} stadiumId={}", userId, stadiumId);
        return true;
    }

    @Transactional(readOnly = true)
    public Page<StadiumResponse> getFavorites(Long userId, Pageable pageable) {
        return favoriteRepository.findByUserId(userId, pageable)
                .map(favorite -> toResponseWithRatings(favorite.getStadium()));
    }

    @Transactional(readOnly = true)
    public boolean isFavorited(Long stadiumId, Long userId) {
        return favoriteRepository.existsByUserIdAndStadiumId(userId, stadiumId);
    }

    private StadiumResponse toResponseWithRatings(Stadium stadium) {
        StadiumResponse response = stadiumMapper.toResponse(stadium);
        response.setAverageRating(reviewRepository.averageRatingByStadiumId(stadium.getId()));
        response.setReviewCount(reviewRepository.countByStadiumId(stadium.getId()));
        return response;
    }
}
