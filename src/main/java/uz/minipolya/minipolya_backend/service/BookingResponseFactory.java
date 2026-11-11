package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.provider.DeclineReason;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Builds booking responses in three views.
 *
 * <ul>
 *   <li><b>Player</b> ({@link #of}): one price. {@code price} is the player price D,
 *       {@code paidAmount} what was paid of it, {@code remainingAmount} what is left — the
 *       same amount in the app or in cash. No owner price, fee or other breakdown.</li>
 *   <li><b>Owner</b> ({@link #forOwner}): the same {@code price}, {@code paidAmount} and
 *       {@code remainingAmount} as the player sees (the remainder to collect in cash is the
 *       remainder the player would pay in the app), plus {@code ownerPrice},
 *       {@code paidInApp} and {@code ownerReceives}.</li>
 *   <li><b>Admin</b> ({@link #forAdmin}): the owner's view plus {@code atmosFee}.</li>
 * </ul>
 *
 * <p>In every view {@code paidAmount + remainingAmount = price}, for every booking: one made
 * before the player price (and every game booking) keeps its stored owner's-terms amounts,
 * and they are converted here exactly as for the player ({@link CardPricing#playerPrice},
 * {@link CardPricing#remainingForPlayer}).
 *
 * <p>Every view also carries the cancellation deadline and what a cancellation right now
 * would refund, from the same {@link CancellationPolicy} and refundable payments that
 * {@code PaymentService} gives back on a real cancellation.
 */
@Component
@RequiredArgsConstructor
public class BookingResponseFactory {

    /** Money that arrived and was not given back. */
    private static final Set<Payment.Status> KEPT = Set.of(
            Payment.Status.PAID, Payment.Status.CAPTURED, Payment.Status.COMPLETED);

    private final BookingMapper bookingMapper;
    private final CancellationPolicy cancellationPolicy;
    private final PaymentService paymentService;
    private final CardPricing cardPricing;

    /** The player's view. */
    public BookingResponse of(Booking booking) {
        return of(booking, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public BookingResponse of(Booking booking, OffsetDateTime now) {
        return of(booking, now, booking.getId() == null ? List.of() : paymentService.paymentsOf(booking.getId()));
    }

    /** The player's view, from the booking's payments already loaded (a list loads them once). */
    private BookingResponse of(Booking booking, OffsetDateTime now, List<Payment> payments) {
        BookingResponse response = build(booking, now,
                () -> paymentService.refundableCardTotal(booking.getId()));
        if (booking.getPrice() != null) {
            setPlayerAmounts(response, booking, cardPricing);
        }
        setPaymentState(response, payments);
        return response;
    }

    /**
     * R44: why the booking's latest card payment (in full, deposit or remainder) was refused,
     * or null — when it was not refused, or a later one went through.
     */
    static DeclineReason declineReasonOf(List<Payment> payments) {
        return latestCardPayment(payments)
                .filter(p -> p.getStatus() == Payment.Status.FAILED)
                .map(Payment::getDeclineReason)
                .orElse(null);
    }

    /** Money that reached the card holder's payee — kept, or given back since. */
    private static final Set<Payment.Status> ARRIVED = Set.of(
            Payment.Status.PAID, Payment.Status.CAPTURED, Payment.Status.COMPLETED,
            Payment.Status.REFUNDED, Payment.Status.RETURN_PENDING, Payment.Status.REFUND_FAILED);

    /**
     * Where the booking's latest card payment stands (the same payment {@link #declineReasonOf}
     * reads, so {@code declined} exactly when a reason is present): {@code paid} — its money
     * arrived (even if given back since); {@code processing} — no answer yet; {@code declined}
     * — the provider refused it, see the reason; {@code failed} — it failed without a refusal
     * (e.g. never acknowledged); {@code none} — no card payment (cash, manual, game bookings).
     */
    static BookingResponse.PaymentState paymentStateOf(List<Payment> payments) {
        return latestCardPayment(payments).map(p -> {
            if (p.getStatus() == Payment.Status.FAILED) {
                return p.getDeclineReason() != null
                        ? BookingResponse.PaymentState.DECLINED
                        : BookingResponse.PaymentState.FAILED;
            }
            if (ARRIVED.contains(p.getStatus())) {
                return BookingResponse.PaymentState.PAID;
            }
            return p.getStatus() == Payment.Status.PROCESSING
                    || p.getStatus() == Payment.Status.HELD
                    || p.getStatus() == Payment.Status.CAPTURE_PENDING
                    ? BookingResponse.PaymentState.PROCESSING
                    : BookingResponse.PaymentState.NONE;
        }).orElse(BookingResponse.PaymentState.NONE);
    }

    /** The booking's latest card payment attempt — in full, deposit or remainder; refunds and cash are not attempts. */
    private static java.util.Optional<Payment> latestCardPayment(List<Payment> payments) {
        return payments.stream()
                .filter(p -> p.getMethod() != Payment.Method.CASH)
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .filter(p -> p.getPaymentType() == Payment.PaymentType.BOOKING
                        || p.getPaymentType() == Payment.PaymentType.REMAINDER)
                .max(Comparator.comparing(Payment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Payment::getId, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    /**
     * declineReason and paymentState, from the same payments; and (E4) the cash that counts
     * and the owner's remainder mark, which every view shows.
     */
    private static void setPaymentState(BookingResponse response, List<Payment> payments) {
        response.setDeclineReason(declineReasonOf(payments));
        response.setPaymentState(paymentStateOf(payments));
        response.setPaidInCash(paidInCash(payments));
    }

    /** E4: cash rows of the booking whose money counts — a voided (cancelled) one does not. */
    static long paidInCash(List<Payment> payments) {
        return payments.stream()
                .filter(p -> p.getMethod() == Payment.Method.CASH)
                .filter(p -> KEPT.contains(p.getStatus()))
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .mapToLong(Payment::getAmount)
                .sum();
    }

    /** price, paidAmount and remainingAmount in the player's terms, adding up for every booking. */
    private static void setPlayerAmounts(BookingResponse response, Booking booking, CardPricing pricing) {
        long price = pricing.playerPrice(booking);
        long remaining = remainingAmount(booking, pricing);
        response.setPrice(price);
        response.setRemainingAmount(remaining);
        response.setPaidAmount(price - remaining);
    }

    /** The {@code remainingAmount} every view shows — the one definition, also summed by the owner dashboard. */
    public static long remainingAmount(Booking booking, CardPricing pricing) {
        if (booking.getPrice() == null) {
            return booking.getRemainingAmount();
        }
        return Math.min(pricing.playerPrice(booking), pricing.remainingForPlayer(booking));
    }

    public List<BookingResponse> of(List<Booking> bookings) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<UUID, List<Payment>> payments = paymentService.paymentsOfBookings(bookings.stream()
                .map(Booking::getId).filter(java.util.Objects::nonNull).toList());
        return bookings.stream()
                .map(booking -> of(booking, now, payments.getOrDefault(booking.getId(), List.of())))
                .toList();
    }

    /** The owner's view of one booking. */
    public BookingResponse forOwner(Booking booking) {
        return forOwner(booking, OffsetDateTime.now(ZoneOffset.UTC),
                booking.getId() == null ? List.of() : paymentService.paymentsOf(booking.getId()), cardPricing);
    }

    public List<BookingResponse> forOwner(List<Booking> bookings) {
        return bookings.stream().map(this::forOwner).toList();
    }

    /**
     * The owner's view for one row of a list, from what the list has already loaded: the
     * booking's payments and the settings read once ({@link CardPricing#snapshot()}). Costs
     * no query of its own, except a team game booking's team payments ({@link #moneyPayments}).
     */
    public BookingResponse forOwner(Booking booking, OffsetDateTime now, List<Payment> payments, CardPricing pricing) {
        return forOwner(booking, now, payments, moneyPayments(booking, payments), pricing);
    }

    /**
     * The payments a booking's money figures count ({@code paidInApp}, {@code paidInCash},
     * {@code ownerReceives}, {@code atmosFee}, the admin's {@code cashReceived}): the
     * booking's own — except the booking of a team game counted in player terms (every team
     * chose minimum / full, T6): its money lives on the teams' payments
     * ({@code payments.game_team_id}), never on the booking, so it is the payments of the
     * teams currently in the game, the same teams its {@code paidAmount} counts. A casual
     * game's booking has no team payments and keeps its own; a game booking with a team from
     * before changeset 052 ({@code net}) keeps its old figures. One query, only for such a
     * game booking.
     */
    public List<Payment> moneyPayments(Booking booking, List<Payment> own) {
        if (booking.getId() == null || !"game".equals(booking.getSource())
                || booking.getPricingModel() != Booking.PricingModel.PLAYER_PRICE) {
            return own;
        }
        List<Payment> team = paymentService.activeTeamPaymentsOfGameBooking(booking.getId());
        return team.isEmpty() ? own : team;
    }

    /**
     * The owner's view, its money figures from {@code money} ({@link #moneyPayments}); the
     * cancellation figures, {@code paymentState} and {@code declineReason} stay the
     * booking's own (a game booking has no card payment of its own: {@code none}).
     */
    private BookingResponse forOwner(Booking booking, OffsetDateTime now, List<Payment> payments,
                                     List<Payment> money, CardPricing pricing) {
        BookingResponse response = build(booking, now, () -> payments.stream()
                .filter(PaymentService::isRefundableCardPayment)
                .mapToLong(Payment::getAmount)
                .sum());
        if (booking.getPrice() != null) {
            // The remainder to collect in cash: the same amount the player would pay in the app.
            setPlayerAmounts(response, booking, pricing);
            response.setOwnerPrice(booking.getPrice());
        }
        List<Payment> kept = money.stream()
                .filter(p -> KEPT.contains(p.getStatus()))
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .toList();
        response.setPaidInApp(kept.stream()
                .filter(p -> p.getMethod() != Payment.Method.CASH)
                .mapToLong(Payment::getAmount)
                .sum());
        response.setOwnerReceives(kept.stream()
                .mapToLong(p -> p.getMethod() == Payment.Method.CASH
                        ? p.getAmount()
                        : pricing.ownerReceives(p.getOwnerAmount() == null ? 0L : p.getOwnerAmount()))
                .sum());
        setPaymentState(response, payments);
        response.setPaidInCash(paidInCash(money));
        // E8: when the mark was set, and until when the owner may undo it themselves.
        response.setRemainderMarkedAt(booking.getRemainderMarkedAt());
        response.setRemainderMarkUndoableUntil(booking.getRemainderMark() == null ? null
                : BookingService.markUndoableUntil(booking.getRemainderMarkedAt()));
        return response;
    }

    /**
     * The admin's view: the owner's, plus what ATMOS takes of the card money kept. No
     * {@code remainderMarkUndoableUntil}: an admin may undo a mark at any time.
     */
    public BookingResponse forAdmin(Booking booking, OffsetDateTime now, List<Payment> payments, CardPricing pricing) {
        return forAdmin(booking, now, payments, moneyPayments(booking, payments), pricing);
    }

    /** The admin's view, its money figures from {@code money} ({@link #moneyPayments}). */
    public BookingResponse forAdmin(Booking booking, OffsetDateTime now, List<Payment> payments,
                                    List<Payment> money, CardPricing pricing) {
        BookingResponse response = forOwner(booking, now, payments, money, pricing);
        response.setRemainderMarkUndoableUntil(null);
        response.setAtmosFee(money.stream()
                .filter(p -> KEPT.contains(p.getStatus()))
                .filter(p -> p.getMethod() != Payment.Method.CASH)
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .mapToLong(pricing::atmosFee)
                .sum());
        return response;
    }

    private BookingResponse build(Booking booking, OffsetDateTime now, LongSupplier refundable) {
        BookingResponse response = bookingMapper.toResponse(booking);
        response.setRemainderMark(booking.getRemainderMark());
        if (booking.getStartsAt() == null || booking.getStadium() == null) {
            return response; // not enough to say anything about cancelling
        }
        response.setCancellationDeadline(cancellationPolicy.deadline(booking));
        response.setRefundIfCancelledNow(cancellationPolicy.refundable(booking, now)
                ? refundable.getAsLong()
                : 0L);
        return response;
    }
}
