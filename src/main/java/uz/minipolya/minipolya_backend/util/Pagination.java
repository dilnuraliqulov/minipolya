package uz.minipolya.minipolya_backend.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

/**
 * Builds the {@link PageRequest} for the {@code page}/{@code size} query
 * parameters shared by every paginated endpoint.
 *
 * <p>{@code PageRequest.of} throws {@link IllegalArgumentException} for
 * {@code page < 0} or {@code size < 1}, which would surface as a 500. These are
 * client errors, so they are rejected as {@link BadRequestException} (400).
 */
public final class Pagination {

    private Pagination() {
    }

    public static PageRequest of(int page, int size, Sort sort) {
        if (page < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (size < 1) {
            throw new BadRequestException("size must be 1 or greater");
        }
        return PageRequest.of(page, size, sort);
    }
}
