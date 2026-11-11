package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import uz.minipolya.minipolya_backend.dto.response.AdminPaymentResponse;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.mapper.AdminPaymentMapper;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The admin's payment answers: what each side keeps of a payment after ATMOS. */
class AdminPaymentNetsTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final AtomicInteger settingsReads = new AtomicInteger();
    private final CardPricing pricing = new CardPricing(() -> {
        settingsReads.incrementAndGet();
        return PlatformSettings.builder().build();
    });
    private final AdminPaymentService service = new AdminPaymentService(payments,
            Mappers.getMapper(AdminPaymentMapper.class), mock(RevenueService.class), pricing);

    private static Payment teamCard() {
        return Payment.builder().amount(5_500_000L).ownerAmount(2_961_928L).platformAmount(2_538_072L)
                .cardFeeExpected(82_501L).method(Payment.Method.CARD).build();
    }

    @Test
    void theDetailSaysWhatEachSideKeeps() {
        UUID id = UUID.randomUUID();
        when(payments.findById(id)).thenReturn(Optional.of(teamCard()));
        when(payments.findByRefundOfPaymentIdOrderByCreatedAtAsc(id)).thenReturn(List.of());

        AdminPaymentResponse response = service.get(id);

        assertThat(response.getPlatformNet()).isEqualTo(2_500_000L);
        assertThat(response.getOwnerNet()).isEqualTo(2_917_499L);
        assertThat(response.getAtmosFee()).isEqualTo(82_501L);
        assertThat(response.getOwnerAmount()).isEqualTo(2_961_928L);
        assertThat(response.getPlatformAmount()).isEqualTo(2_538_072L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theListReadsTheSettingsOnceAndCashIsTheOwners() {
        Payment cash = Payment.builder().amount(13_000_000L).ownerAmount(13_000_000L).platformAmount(0L)
                .cardFeeExpected(0L).method(Payment.Method.CASH).build();
        when(payments.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(teamCard(), cash, teamCard(), teamCard())));

        List<AdminPaymentResponse> rows = service.list(null, null, null, null, null, null, null, 0, 20).getContent();

        assertThat(settingsReads).hasValue(1);
        assertThat(rows.get(0).getPlatformNet()).isEqualTo(2_500_000L);
        assertThat(rows.get(1).getOwnerNet()).isEqualTo(13_000_000L);
        assertThat(rows.get(1).getPlatformNet()).isZero();
        assertThat(rows.get(1).getAtmosFee()).isZero();
    }
}
