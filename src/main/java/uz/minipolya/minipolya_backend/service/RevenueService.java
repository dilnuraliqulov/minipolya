package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.RevenueRow;
import uz.minipolya.minipolya_backend.util.Revenue;
import uz.minipolya.minipolya_backend.util.TashkentDays;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one revenue calculation. The admin overview ({@code monthRevenue}, {@code todayRevenue},
 * ...), its 30-day chart and the payments summary all read from here, so they always agree.
 * See {@link Revenue} for what each figure means; every figure is card money only — cash
 * the owner took in hand never passed through the platform.
 *
 * <p>What ATMOS took of a payment is the {@code provider_commission} it reported. When it
 * reported none, it is estimated at the current {@code card_fee_bps}; a legacy Payme/Click
 * row predates the card fee and counts none. A refund that reversed the transaction is
 * taken to give ATMOS's fee back as well, so a payment refunded in full nets to zero on
 * every figure.
 */
@Service
@RequiredArgsConstructor
public class RevenueService {

    /** Statuses of a card payment whose money arrived — kept now, or given back since. */
    static final Set<Payment.Status> ARRIVED = Set.of(
            Payment.Status.PAID, Payment.Status.CAPTURED, Payment.Status.COMPLETED, Payment.Status.REFUNDED);

    private static final OffsetDateTime BEGINNING = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END = OffsetDateTime.of(3000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final PaymentRepository paymentRepository;
    private final PlatformSettingsService platformSettingsService;

    /** The figures, and the refunds behind them as a positive amount. */
    public record Summary(Revenue revenue, long refunded) {
    }

    /** Card money in {@code [from, to)}. */
    @Transactional(readOnly = true)
    public Summary between(OffsetDateTime from, OffsetDateTime to) {
        List<RevenueRow> rows = rows(from, to);
        int bps = cardFeeBps();
        return new Summary(
                Revenue.sum(rows.stream().map(row -> line(row, bps)).toList()),
                rows.stream().filter(RevenueRow::isRefund).mapToLong(row -> -row.amount()).sum());
    }

    /** Card money ever taken. */
    @Transactional(readOnly = true)
    public Revenue total() {
        return between(BEGINNING, END).revenue();
    }

    /** Each Tashkent day from {@code first} to {@code last}, both included — every day present. */
    @Transactional(readOnly = true)
    public Map<LocalDate, Revenue> daily(LocalDate first, LocalDate last) {
        int bps = cardFeeBps();
        Map<LocalDate, Revenue> byDay = new LinkedHashMap<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            byDay.put(day, Revenue.ZERO);
        }
        TashkentDays.Range range = TashkentDays.range(first, last);
        for (RevenueRow row : rows(range.from(), range.to())) {
            byDay.merge(TashkentDays.dateOf(row.countedAt()), line(row, bps), Revenue::plus);
        }
        return byDay;
    }

    /** Everything on hold on players' cards right now, whenever it was taken. */
    @Transactional(readOnly = true)
    public long heldNow() {
        return paymentRepository.sumByStatus(Payment.Status.HELD);
    }

    private List<RevenueRow> rows(OffsetDateTime from, OffsetDateTime to) {
        return paymentRepository.findRevenueRows(Payment.Method.CARD, Payment.PaymentType.REFUND,
                ARRIVED, Payment.Status.REFUNDED, from, to);
    }

    private int cardFeeBps() {
        return platformSettingsService.current().getCardFeeBps();
    }

    /** One row's part in the figures. */
    static Revenue line(RevenueRow row, int bps) {
        long fee = row.isRefund()
                ? -feeOf(row.originalProvider(), row.originalAmount(), row.originalProviderCommission(), bps)
                : feeOf(row.provider(), row.amount(), row.providerCommission(), bps);
        return Revenue.line(row.amount(), row.ownerAmount(), row.platformAmount(), fee);
    }

    /** What ATMOS took of a payment: as reported, else estimated; nothing on a legacy row. */
    static long feeOf(Payment.Provider provider, Long amount, Long reportedCommission, int bps) {
        if (reportedCommission != null) {
            return reportedCommission;
        }
        if (provider != Payment.Provider.ATMOS || amount == null) {
            return 0;
        }
        return Math.floorDiv(Math.multiplyExact(amount, bps) + 5_000, 10_000);
    }
}
