package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.response.PhotoResponse;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.PayloadTooLargeException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.StadiumMapper;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.util.ImageReencoder;
import uz.minipolya.minipolya_backend.util.ImageType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * R36: an owner's stadium photos — upload, delete, reorder — in any stadium status. Every
 * upload is checked by its magic bytes, then re-encoded to a JPEG ({@link ImageReencoder}). The
 * lowest position is the cover ({@code coverPhotoUrl}). Each change is audited as
 * {@code STADIUM_UPDATED} with the photo URLs before and after, like any other stadium edit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StadiumPhotoService {

    public static final int MAX_PHOTOS = 10;
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private final StadiumService stadiumService;
    private final StadiumRepository stadiumRepository;
    private final StadiumPhotoStorage storage;
    private final StadiumMapper stadiumMapper;
    private final AuditService auditService;

    @Transactional
    public PhotoResponse upload(UUID ownerId, UUID stadiumId, MultipartFile file) {
        Stadium stadium = lockOwnedStadium(ownerId, stadiumId);
        if (file == null) {
            throw new BadRequestException("file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new PayloadTooLargeException("File too large");
        }
        byte[] bytes = readBytes(file);
        if (ImageType.detect(bytes).isEmpty()) {
            throw new BadRequestException("Unsupported image type");
        }
        if (stadium.getPhotos().size() >= MAX_PHOTOS) {
            throw new BadRequestException("A stadium can have at most " + MAX_PHOTOS + " photos");
        }
        // D5: decoded and written again as a JPEG, at most 2000 px, without EXIF.
        byte[] jpeg = ImageReencoder.toJpeg(bytes);

        Map<String, Object> before = StadiumService.auditSnapshot(stadium);
        int position = stadium.getPhotos().stream().mapToInt(StadiumPhoto::getPosition).max().orElse(-1) + 1;
        String url = storage.store(stadiumId, jpeg, ImageType.JPEG);
        StadiumPhoto photo = StadiumPhoto.builder().url(url).position(position).build();
        stadium.addPhoto(photo);
        stadiumRepository.flush(); // the stadium is managed: flushing cascades the photo rows

        audit(ownerId, stadium, before);
        log.info("Stadium photo uploaded: stadiumId={} photoId={} position={}", stadiumId, photo.getId(), position);
        return stadiumMapper.toPhotoResponse(photo);
    }

    @Transactional
    public void delete(UUID ownerId, UUID stadiumId, UUID photoId) {
        Stadium stadium = lockOwnedStadium(ownerId, stadiumId);
        StadiumPhoto photo = stadium.getPhotos().stream()
                .filter(p -> p.getId().equals(photoId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found"));

        Map<String, Object> before = StadiumService.auditSnapshot(stadium);
        stadium.getPhotos().remove(photo); // orphanRemoval deletes the row
        renumber(stadium.getPhotos());
        stadiumRepository.flush(); // the stadium is managed: flushing cascades the photo rows
        storage.deleteAfterCommit(photo.getUrl());

        audit(ownerId, stadium, before);
        log.info("Stadium photo deleted: stadiumId={} photoId={}", stadiumId, photoId);
    }

    /** {@code photoIds} must be every photo of the stadium exactly once; the first becomes the cover. */
    @Transactional
    public List<PhotoResponse> reorder(UUID ownerId, UUID stadiumId, List<UUID> photoIds) {
        Stadium stadium = lockOwnedStadium(ownerId, stadiumId);
        Map<UUID, StadiumPhoto> byId = stadium.getPhotos().stream()
                .collect(Collectors.toMap(StadiumPhoto::getId, Function.identity()));
        Set<UUID> sent = new HashSet<>(photoIds);
        if (sent.size() != photoIds.size() || !sent.equals(byId.keySet())) {
            throw new BadRequestException("photoIds must list every photo of the stadium exactly once");
        }

        Map<String, Object> before = StadiumService.auditSnapshot(stadium);
        List<StadiumPhoto> ordered = photoIds.stream().map(byId::get).toList();
        renumber(ordered);
        stadium.getPhotos().sort((a, b) -> Integer.compare(a.getPosition(), b.getPosition()));
        stadiumRepository.flush(); // the stadium is managed: flushing cascades the photo rows

        audit(ownerId, stadium, before);
        log.info("Stadium photos reordered: stadiumId={} count={}", stadiumId, ordered.size());
        return ordered.stream().map(stadiumMapper::toPhotoResponse).toList();
    }

    // ---- Helpers ----

    /** 404 / 403 as every owner endpoint, then the row locked for the rest of the change. */
    private Stadium lockOwnedStadium(UUID ownerId, UUID stadiumId) {
        stadiumService.requireOwnedStadium(ownerId, stadiumId);
        return stadiumRepository.findForUpdate(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
    }

    private static void renumber(List<StadiumPhoto> photos) {
        for (int i = 0; i < photos.size(); i++) {
            photos.get(i).setPosition(i);
        }
    }

    private void audit(UUID ownerId, Stadium stadium, Map<String, Object> before) {
        AuditEntry entry = AuditEntry.owner(ownerId, AuditAction.STADIUM_UPDATED)
                .entity(AuditEntityType.STADIUM, stadium.getId(), AuditLabels.stadium(stadium))
                .diff(before, StadiumService.auditSnapshot(stadium));
        if (!entry.getChanges().isEmpty()) {
            auditService.record(entry);
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the upload", ex);
        }
    }
}
