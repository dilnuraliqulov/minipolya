package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumResponse;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumAmenities;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.StadiumMapper;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StadiumService {

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_APPROVED = "approved";

    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final StadiumMapper stadiumMapper;

    // ---- Owner operations ----

    @Transactional
    public StadiumDetailResponse create(Long ownerId, CreateStadiumRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Stadium stadium = stadiumMapper.toEntity(request);
        stadium.setOwner(owner);
        stadium.setStatus(STATUS_PENDING);
        applyRefundOverrides(stadium, request.getFullRefundHours(),
                request.getPartialRefundHours(), request.getPartialRefundPercent());

        StadiumAmenities amenities = request.getAmenities() != null
                ? stadiumMapper.toAmenities(request.getAmenities())
                : new StadiumAmenities();
        stadium.setAmenities(amenities);

        replacePhotos(stadium, request.getPhotos());

        Stadium saved = stadiumRepository.save(stadium);
        log.info("Stadium created: id={} ownerId={} status={}", saved.getId(), ownerId, saved.getStatus());
        return stadiumMapper.toDetailResponse(saved);
    }

    @Transactional
    public StadiumDetailResponse update(Long ownerId, Long stadiumId, UpdateStadiumRequest request) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }

        stadiumMapper.updateStadiumFromRequest(request, stadium);

        if (request.getAmenities() != null) {
            if (stadium.getAmenities() == null) {
                stadium.setAmenities(stadiumMapper.toAmenities(request.getAmenities()));
            } else {
                stadiumMapper.updateAmenities(request.getAmenities(), stadium.getAmenities());
            }
        }
        if (request.getPhotos() != null) {
            replacePhotos(stadium, request.getPhotos());
        }

        Stadium saved = stadiumRepository.save(stadium);
        log.info("Stadium updated: id={} ownerId={}", saved.getId(), ownerId);
        return stadiumMapper.toDetailResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<StadiumResponse> listOwnerStadiums(Long ownerId) {
        return stadiumRepository.findByOwnerId(ownerId).stream()
                .map(stadiumMapper::toResponse)
                .toList();
    }

    // ---- Public operations ----

    @Transactional(readOnly = true)
    public PageResponse<StadiumResponse> searchPublic(String q, String format, Integer maxPrice, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"));
        return PageResponse.from(
                stadiumRepository.search(blankToNull(q), blankToNull(format), maxPrice, pageable),
                stadiumMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public StadiumDetailResponse getPublicDetail(Long id) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(id, STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        return stadiumMapper.toDetailResponse(stadium);
    }

    // ---- Helpers ----

    private void applyRefundOverrides(Stadium stadium, Integer fullHours, Integer partialHours, Integer partialPercent) {
        if (fullHours != null) {
            stadium.setFullRefundHours(fullHours);
        }
        if (partialHours != null) {
            stadium.setPartialRefundHours(partialHours);
        }
        if (partialPercent != null) {
            stadium.setPartialRefundPercent(partialPercent);
        }
    }

    private void replacePhotos(Stadium stadium, List<String> urls) {
        stadium.getPhotos().clear();
        if (urls == null) {
            return;
        }
        int position = 0;
        for (String url : urls) {
            stadium.addPhoto(StadiumPhoto.builder().url(url).position(position++).build());
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
