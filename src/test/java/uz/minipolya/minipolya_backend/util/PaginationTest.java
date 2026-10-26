package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaginationTest {

    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    void buildsPageRequestForValidParameters() {
        PageRequest pageable = Pagination.of(2, 20, SORT);

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).isEqualTo(SORT);
    }

    @Test
    void firstPageIsAllowed() {
        assertThat(Pagination.of(0, 1, SORT).getPageNumber()).isZero();
    }

    @Test
    void negativePageIsRejectedAsBadRequestNotAsIllegalArgument() {
        assertThatThrownBy(() -> Pagination.of(-1, 20, SORT))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("page must be 0 or greater");
    }

    @Test
    void zeroSizeIsRejectedAsBadRequestNotAsIllegalArgument() {
        assertThatThrownBy(() -> Pagination.of(0, 0, SORT))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("size must be 1 or greater");
    }

    @Test
    void negativeSizeIsRejectedAsBadRequest() {
        assertThatThrownBy(() -> Pagination.of(0, -5, SORT))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("size must be 1 or greater");
    }
}
