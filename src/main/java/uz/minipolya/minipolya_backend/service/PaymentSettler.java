package uz.minipolya.minipolya_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Settles a payment left {@code processing} <b>now</b>, while the player is still looking at
 * the screen, instead of leaving it for {@code PaymentReconcileJob} five minutes later.
 *
 * <p>Why it exists: ATMOS answers {@code pay/apply} with {@code STPIMS-ERR-041} ("Проблема
 * соединения") for payments it has in fact taken — the money moved and {@code pay/get} says
 * so moments later. {@code AtmosPaymentProvider} is right not to assume either way, so the
 * row stays {@code processing}; the mistake was making the player wait for a cron job to
 * find out. This asks the provider the same question the job asks ({@code pay/get}), a few
 * times over a few seconds, and stops as soon as the row moves.
 *
 * <p>It is <b>the same code path as the job</b>: {@link PaymentReconcileService#reconcile}
 * and nothing else, so a payment settled here is settled exactly as one settled later —
 * booking confirmed through {@code applySuccessfulBookingPayment}, money given back if what
 * it paid for has gone, audit entry written. Nothing new may settle a payment: a second way
 * to turn money into a confirmed booking is how a booking gets confirmed twice.
 *
 * <p>Called <b>after</b> the transaction that made the charge has committed (from a
 * controller, never from inside a service's transaction): each attempt is its own
 * transaction, it sleeps between them, and it must hold no row while it does.
 *
 * <p>Idempotent and safe to lose: every attempt is a {@code reconcile} of a row that locks
 * itself and re-reads its committed status, so this racing the job settles the payment once.
 * A provider that cannot be reached just leaves the row where it was — the job tries again.
 */
@Slf4j
@Service
public class PaymentSettler {

    /** The statuses this can do anything about: an answer is still owed on them. */
    private static final List<Payment.Status> IN_FLIGHT =
            List.of(Payment.Status.PROCESSING, Payment.Status.CAPTURE_PENDING);

    private final PaymentReconcileService reconcileService;
    private final PaymentRepository paymentRepository;
    private final AppProperties.SettleNow settings;
    private final Clock clock;
    private final Sleeper sleeper;

    /** How the waiting between two attempts is done; a test replaces it rather than sleeping. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentSettler(PaymentReconcileService reconcileService, PaymentRepository paymentRepository,
                          AppProperties appProperties, Clock clock) {
        this(reconcileService, paymentRepository, appProperties, clock,
                duration -> Thread.sleep(duration.toMillis()));
    }

    PaymentSettler(PaymentReconcileService reconcileService, PaymentRepository paymentRepository,
                   AppProperties appProperties, Clock clock, Sleeper sleeper) {
        this.reconcileService = reconcileService;
        this.paymentRepository = paymentRepository;
        this.settings = appProperties.getPayments().getSettleNow();
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Asks the provider about the booking's in-flight payment until it is settled or the
     * attempts run out.
     *
     * @return true when something moved, so the caller should read the booking again
     */
    public boolean settleBookingNow(UUID bookingId) {
        UUID paymentId = inFlightPaymentOf(bookingId);
        return paymentId != null && settleNow(paymentId);
    }

    /**
     * Asks the provider about one payment until it is settled or the attempts run out.
     *
     * <p>Never throws: a provider that is still unreachable, or a thread that is interrupted
     * while waiting, is exactly the case the reconcile job exists for. The interrupt is put
     * back so the container still sees it.
     *
     * @return true when the row is no longer in flight
     */
    public boolean settleNow(UUID paymentId) {
        int attempts = Math.max(0, settings.getAttempts());
        // One provider call may take as long as app.atmos.timeout-seconds, so the attempts
        // alone are no bound on how long a player waits: this is.
        Instant deadline = clock.instant().plus(settings.getBudget());
        int made = 0;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            made = attempt;
            try {
                reconcileService.reconcile(paymentId);
            } catch (RuntimeException ex) {
                // The provider is still not answering. Types only: never a body or a card detail.
                log.info("Payment {} could not be settled on attempt {}: {}",
                        paymentId, attempt, ex.getClass().getSimpleName());
            }
            if (!stillInFlight(paymentId)) {
                log.info("Payment {} settled while the player waited, on attempt {} of {}",
                        paymentId, attempt, attempts);
                return true;
            }
            if (attempt == attempts || outOfTime(deadline) || !waited(deadline)) {
                break;
            }
        }
        if (attempts > 0) {
            log.info("Payment {} is still processing after {} attempt(s); left to the reconcile job",
                    paymentId, made);
        }
        return false;
    }

    private boolean outOfTime(Instant deadline) {
        return !clock.instant().isBefore(deadline);
    }

    /** Waits before the next attempt, never past the deadline. False: stop now. */
    private boolean waited(Instant deadline) {
        Duration left = Duration.between(clock.instant(), deadline);
        Duration delay = settings.getDelay();
        if (delay.compareTo(left) > 0) {
            return false; // the next attempt could not finish in the time that is left
        }
        try {
            sleeper.sleep(delay);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The booking's card payment that is still on its way, or null. The latest one, so a
     * booking that was paid twice (a declined first attempt, then this) asks about this one.
     */
    @Transactional(readOnly = true)
    public UUID inFlightPaymentOf(UUID bookingId) {
        return paymentRepository.findByBookingId(bookingId).stream()
                .filter(p -> p.getMethod() != Payment.Method.CASH)
                .filter(p -> p.getAmount() != null && p.getAmount() > 0)
                .filter(p -> IN_FLIGHT.contains(p.getStatus()))
                .max(java.util.Comparator.comparing(Payment::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(Payment::getId)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean stillInFlight(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .map(p -> IN_FLIGHT.contains(p.getStatus()))
                .orElse(false);
    }
}
