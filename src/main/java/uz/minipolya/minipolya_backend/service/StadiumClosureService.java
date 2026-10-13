package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumClosureRequest;
import uz.minipolya.minipolya_backend.dto.response.StadiumClosureResponse;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumClosure;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.StadiumClosureMapper;
import uz.minipolya.minipolya_backend.repository.StadiumClosureRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Owner-managed stadium closures. Existing confirmed bookings in a closure
 * window are NOT auto-cancelled — the owner handles those manually.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StadiumClosureService {

    private final StadiumClosureRepository closureRepository;
    private final StadiumRepository stadiumRepository;
    private final StadiumClosureMapper closureMapper;

    @Transactional
    public StadiumClosureResponse createClosure(CreateStadiumClosureRequest request, Long ownerUserId) {
        Stadium stadium = requireOwnedStadium(request.getStadiumId(), ownerUserId);

        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new BadRequestException("endDate must be on or after startDate");
        }
        if ((request.getStartTime() == null) != (request.getEndTime() == null)) {
            throw new BadRequestException("startTime and endTime must both be set, or both be omitted");
        }
        if (request.getStartTime() != null && !request.getStartTime().isBefore(request.getEndTime())) {
            throw new BadRequestException("startTime must be before endTime");
        }

        StadiumClosure closure = closureMapper.toEntity(request);
        closure.setStadium(stadium);
        StadiumClosure saved = closureRepository.save(closure);
        log.info("Closure created: id={} stadiumId={} {}..{}",
                saved.getId(), stadium.getId(), saved.getStartDate(), saved.getEndDate());
        return closureMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<StadiumClosureResponse> getClosures(Long stadiumId, Long ownerUserId) {
        requireOwnedStadium(stadiumId, ownerUserId);
        return closureRepository.findByStadiumId(stadiumId).stream()
                .map(closureMapper::toResponse)
                .toList();
    }

    @Transactional
    public void deleteClosure(Long closureId, Long ownerUserId) {
        StadiumClosure closure = closureRepository.findById(closureId)
                .orElseThrow(() -> new ResourceNotFoundException("Closure not found"));
        if (!closure.getStadium().getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        closureRepository.delete(closure);
        log.info("Closure deleted: id={} ownerId={}", closureId, ownerUserId);
    }

    /** True if the given date/time falls within any closure of the stadium. */
    @Transactional(readOnly = true)
    public boolean isClosedAt(Long stadiumId, LocalDate date, LocalTime time) {
        return closureRepository.findByStadiumIdAndEndDateGreaterThanEqual(stadiumId, date).stream()
                .anyMatch(closure -> covers(closure, date, time));
    }

    private boolean covers(StadiumClosure closure, LocalDate date, LocalTime time) {
        if (date.isBefore(closure.getStartDate()) || date.isAfter(closure.getEndDate())) {
            return false;
        }
        if (closure.getStartTime() == null) {
            return true; // full-day closure
        }
        return !time.isBefore(closure.getStartTime()) && time.isBefore(closure.getEndTime());
    }

    private Stadium requireOwnedStadium(Long stadiumId, Long ownerUserId) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        return stadium;
    }
}
