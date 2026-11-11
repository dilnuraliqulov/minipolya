package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.StadiumResponse;
import uz.minipolya.minipolya_backend.entity.Favorite;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.repository.FavoriteRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.UUID;

/** A user's favorited stadiums, managed with a single add/remove toggle. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final StadiumService stadiumService;

    /** Adds the favorite if absent, removes it if present. @return true if now favorited. */
    @Transactional
    public boolean toggleFavorite(UUID stadiumId, UUID userId) {
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

    /**
     * The user's favorites as the player stadium list shows them (player prices, rating,
     * cover photo, {@code isFavorited} true), for a fixed handful of queries per page.
     */
    @Transactional(readOnly = true)
    public Page<StadiumResponse> getFavorites(UUID userId, Pageable pageable) {
        Page<Favorite> favorites = favoriteRepository.findByUserId(userId, pageable);
        List<StadiumResponse> rows = stadiumService.toPublicResponses(
                favorites.getContent().stream().map(Favorite::getStadium).toList(), userId);
        return new PageImpl<>(rows, pageable, favorites.getTotalElements());
    }

    @Transactional(readOnly = true)
    public boolean isFavorited(UUID stadiumId, UUID userId) {
        return favoriteRepository.existsByUserIdAndStadiumId(userId, stadiumId);
    }
}
