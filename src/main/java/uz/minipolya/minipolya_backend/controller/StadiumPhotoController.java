package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import uz.minipolya.minipolya_backend.dto.request.ReorderPhotosRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PhotoResponse;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.service.StadiumPhotoService;
import uz.minipolya.minipolya_backend.service.StadiumPhotoStorage;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** R36: stadium photos — the owner's upload, delete and reorder, and the public files. */
@RestController
@RequiredArgsConstructor
public class StadiumPhotoController {

    private final StadiumPhotoService photoService;
    private final StadiumPhotoStorage storage;

    @PostMapping(value = "/api/owner/stadiums/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PhotoResponse> upload(@AuthenticationPrincipal UUID ownerId,
                                             @PathVariable UUID id,
                                             @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(photoService.upload(ownerId, id, file), "Photo uploaded");
    }

    @DeleteMapping("/api/owner/stadiums/{id}/photos/{photoId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UUID ownerId,
                                    @PathVariable UUID id,
                                    @PathVariable UUID photoId) {
        photoService.delete(ownerId, id, photoId);
        return ApiResponse.ok(null, "Photo deleted");
    }

    @PutMapping("/api/owner/stadiums/{id}/photos/order")
    public ApiResponse<List<PhotoResponse>> reorder(@AuthenticationPrincipal UUID ownerId,
                                                    @PathVariable UUID id,
                                                    @Valid @RequestBody ReorderPhotosRequest request) {
        return ApiResponse.ok(photoService.reorder(ownerId, id, request.getPhotoIds()), "Photos reordered");
    }

    /**
     * A stored photo, public. Only {@code <uuid>.<jpg|png|webp>} under a stadium id is ever
     * served — no directory listing, no other path; anything else is 404. A file never
     * changes under its name, so it may be cached for a year.
     */
    @GetMapping("/api/uploads/stadiums/{stadiumId}/{fileName}")
    public ResponseEntity<Resource> file(@PathVariable UUID stadiumId, @PathVariable String fileName) {
        StadiumPhotoStorage.StoredFile stored = storage.find(stadiumId, fileName)
                .orElseThrow(() -> new ResourceNotFoundException("Not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.type().contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(stored.path()));
    }
}
