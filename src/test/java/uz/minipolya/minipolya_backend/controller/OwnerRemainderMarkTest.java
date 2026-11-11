package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.service.BookingService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E5–E8: the owner's marks on a booking's remainder — "Naqd olindi" (cash), "Toʻlanmadi"
 * (not paid) — the owner's same-day undo, the admin's undo at any time, and what the
 * responses, the stats and the unpaid list show afterwards.
 */
@DatabaseTest
class OwnerRemainderMarkTest {

    private static final long PLAYER_PRICE = 15_500_000L;
    private static final long DEPOSIT = 5_500_000L;
    private static final long REMAINDER = PLAYER_PRICE - DEPOSIT;
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private User owner;
    private String ownerToken;
    private User player;
    private Stadium stadium;
    private Booking booking;

    @BeforeEach
    void setUp() {
        owner = data.owner("Mark Owner");
        ownerToken = data.bearer(owner);
        player = data.user("Mark Player");
        stadium = data.stadium(owner, "Mark Arena");
        booking = partiallyPaid(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.HOURS));
    }

    private Booking partiallyPaid(OffsetDateTime startsAt) {
        Booking b = data.booking(stadium, player, startsAt, "confirmed");
        b.setCardPrice(PLAYER_PRICE);
        b.setPaidAmount(DEPOSIT);
        b.setPaymentStatus(Booking.PaymentStatus.PARTIALLY_PAID);
        Booking saved = bookingRepository.saveAndFlush(b);
        data.cardPayment(saved, DEPOSIT, DEPOSIT, 0L, null, Payment.Status.PAID);
        return saved;
    }

    private ResultActions cash(UUID id, String token) throws Exception {
        return mvc.perform(post("/api/owner/bookings/" + id + "/cash").header("Authorization", token));
    }

    private ResultActions notPaid(UUID id, String token) throws Exception {
        return mvc.perform(post("/api/owner/bookings/" + id + "/not-paid").header("Authorization", token));
    }

    private ResultActions undo(UUID id, String token) throws Exception {
        return mvc.perform(delete("/api/owner/bookings/" + id + "/remainder-mark").header("Authorization", token));
    }

    private ResultActions adminUndo(UUID id, String token) throws Exception {
        return mvc.perform(delete("/api/admin/bookings/" + id + "/remainder-mark").header("Authorization", token));
    }

    private List<String> auditActions(UUID bookingId) {
        entityManager.flush(); // audit rows are saved, not flushed, inside the test transaction
        return jdbc.queryForList("SELECT action FROM audit_log WHERE entity_id = ? ORDER BY created_at",
                String.class, bookingId);
    }

    private String auditActorType(UUID bookingId, String action) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT actor_type FROM audit_log WHERE entity_id = ? AND action = ?",
                String.class, bookingId, action);
    }

    // ---- "Naqd olindi" ----

    @Test
    void cashSetsTheMarkAndTheOwnerViewShowsItWithItsUndoWindow() throws Exception {
        cash(booking.getId(), ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainderMark").value("cash_received"))
                .andExpect(jsonPath("$.data.remainderMarkedAt").exists())
                .andExpect(jsonPath("$.data.remainderMarkUndoableUntil").exists())
                .andExpect(jsonPath("$.data.paidInCash").value(REMAINDER))
                .andExpect(jsonPath("$.data.paidInApp").value(DEPOSIT));

        Booking saved = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(saved.getRemainderMark()).isEqualTo(Booking.RemainderMark.CASH_RECEIVED);
        assertThat(saved.getRemainderMarkedBy()).isEqualTo(owner.getId());
        // The undo window ends at the next Tashkent midnight after the mark.
        OffsetDateTime until = BookingService.markUndoableUntil(saved.getRemainderMarkedAt());
        assertThat(until.atZoneSameInstant(TASHKENT).toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
        assertThat(until.atZoneSameInstant(TASHKENT).toLocalDate())
                .isEqualTo(saved.getRemainderMarkedAt().atZoneSameInstant(TASHKENT).toLocalDate().plusDays(1));
        assertThat(auditActions(booking.getId())).contains("CASH_RECEIVED");

        // The player sees the mark and the cash, not when it was set.
        mvc.perform(get("/api/bookings/" + booking.getId()).header("Authorization", data.bearer(player)))
                .andExpect(jsonPath("$.data.remainderMark").value("cash_received"))
                .andExpect(jsonPath("$.data.paidInCash").value(REMAINDER))
                .andExpect(jsonPath("$.data.remainderMarkedAt").doesNotExist())
                .andExpect(jsonPath("$.data.remainderMarkUndoableUntil").doesNotExist());
    }

    @Test
    void undoingCashVoidsTheCashRowAndRecomputesTheBooking() throws Exception {
        cash(booking.getId(), ownerToken).andExpect(status().isOk());

        undo(booking.getId(), ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Mark undone"))
                .andExpect(jsonPath("$.data.remainderMark").doesNotExist())
                .andExpect(jsonPath("$.data.paymentStatus").value("partially_paid"))
                .andExpect(jsonPath("$.data.paidAmount").value(DEPOSIT))
                .andExpect(jsonPath("$.data.remainingAmount").value(REMAINDER))
                .andExpect(jsonPath("$.data.paidInCash").value(0));

        Payment cashRow = paymentRepository.findByBookingId(booking.getId()).stream()
                .filter(p -> p.getMethod() == Payment.Method.CASH).findFirst().orElseThrow();
        assertThat(cashRow.getStatus()).isEqualTo(Payment.Status.CANCELLED);
        // Never a refund: no refund row was written.
        assertThat(paymentRepository.findByBookingId(booking.getId()))
                .noneMatch(p -> p.getPaymentType() == Payment.PaymentType.REFUND);
        assertThat(auditActions(booking.getId())).contains("REMAINDER_MARK_UNDONE");
        assertThat(auditActorType(booking.getId(), "REMAINDER_MARK_UNDONE")).isEqualTo("OWNER");

        // The money is owed again: on the unpaid list, and cash can be taken once more.
        mvc.perform(get("/api/owner/dashboard/stadiums/" + stadium.getId() + "/bookings")
                        .param("unpaid", "true").header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data.content[0].id").value(booking.getId().toString()));
        cash(booking.getId(), ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidInCash").value(REMAINDER));
    }

    @Test
    void aVoidedCashRowDoesNotCountInTheOwnerStats() throws Exception {
        cash(booking.getId(), ownerToken).andExpect(status().isOk());
        undo(booking.getId(), ownerToken).andExpect(status().isOk());

        mvc.perform(get("/api/owner/dashboard/stadiums/" + stadium.getId() + "/stats").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthPaidInCash").value(0));
    }

    // ---- "Toʻlanmadi" ----

    @Test
    void notPaidMarksWithoutChargingAndCanBeReplacedByCash() throws Exception {
        notPaid(booking.getId(), ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Marked as not paid"))
                .andExpect(jsonPath("$.data.remainderMark").value("not_paid"))
                .andExpect(jsonPath("$.data.paymentStatus").value("partially_paid"))
                .andExpect(jsonPath("$.data.remainingAmount").value(REMAINDER));
        assertThat(paymentRepository.findByBookingId(booking.getId())).hasSize(1);
        assertThat(auditActions(booking.getId())).contains("REMAINDER_NOT_PAID");

        // Marking it again changes nothing and writes nothing.
        notPaid(booking.getId(), ownerToken).andExpect(status().isOk());
        assertThat(auditActions(booking.getId()).stream().filter("REMAINDER_NOT_PAID"::equals)).hasSize(1);

        cash(booking.getId(), ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainderMark").value("cash_received"))
                .andExpect(jsonPath("$.data.paymentStatus").value("paid"));
    }

    @Test
    void undoingNotPaidOnlyClearsTheMark() throws Exception {
        notPaid(booking.getId(), ownerToken).andExpect(status().isOk());

        undo(booking.getId(), ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainderMark").doesNotExist())
                .andExpect(jsonPath("$.data.paymentStatus").value("partially_paid"));
        assertThat(paymentRepository.findByBookingId(booking.getId()))
                .allMatch(p -> p.getStatus() == Payment.Status.PAID);
    }

    @Test
    void aWalkInStillUnpaidCanBeMarkedEitherWay() throws Exception {
        Booking walkIn = data.walkIn(stadium, "Walk-in", "+998901112233",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).truncatedTo(ChronoUnit.HOURS));
        notPaid(walkIn.getId(), ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainderMark").value("not_paid"));
        cash(walkIn.getId(), ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainderMark").value("cash_received"))
                .andExpect(jsonPath("$.data.paymentStatus").value("paid"));
        undo(walkIn.getId(), ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("unpaid"))
                .andExpect(jsonPath("$.data.paidAmount").value(0));
    }

    // ---- the undo window and the admin ----

    @Test
    void afterTheMarksTashkentDayOnlyAnAdminCanUndoIt() throws Exception {
        cash(booking.getId(), ownerToken).andExpect(status().isOk());
        Booking saved = bookingRepository.findById(booking.getId()).orElseThrow();
        saved.setRemainderMarkedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).minusHours(1));
        bookingRepository.saveAndFlush(saved);

        undo(booking.getId(), ownerToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only an admin can change this mark"));

        User admin = data.admin("Mark Admin");
        mvc.perform(get("/api/admin/bookings/" + booking.getId()).header("Authorization", data.bearer(admin)))
                .andExpect(jsonPath("$.data.remainderMark").value("cash_received"))
                .andExpect(jsonPath("$.data.remainderMarkedAt").exists())
                .andExpect(jsonPath("$.data.remainderMarkUndoableUntil").doesNotExist())
                .andExpect(jsonPath("$.data.paidInCash").value(REMAINDER));
        adminUndo(booking.getId(), data.bearer(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Mark undone"))
                .andExpect(jsonPath("$.data.remainderMark").doesNotExist())
                .andExpect(jsonPath("$.data.paymentStatus").value("partially_paid"))
                .andExpect(jsonPath("$.data.cashReceived").doesNotExist());
        assertThat(auditActorType(booking.getId(), "REMAINDER_MARK_UNDONE")).isEqualTo("ADMIN");

        adminUndo(booking.getId(), data.bearer(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This booking has no remainder mark"));
        adminUndo(booking.getId(), ownerToken).andExpect(status().isForbidden());
    }

    // ---- who and when ----

    @Test
    void accessAndStateRules() throws Exception {
        String other = data.bearer(data.owner("Other Owner"));
        notPaid(booking.getId(), other).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not own this stadium"));
        undo(booking.getId(), other).andExpect(status().isForbidden());
        notPaid(booking.getId(), data.bearer(player)).andExpect(status().isForbidden());
        undo(booking.getId(), ownerToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This booking has no remainder mark"));
        notPaid(UUID.fromString("00000000-0000-0000-0000-000000000000"), ownerToken)
                .andExpect(status().isNotFound());

        String nothingToComplete = "This booking has no part payment to complete";
        Booking cancelled = partiallyPaid(OffsetDateTime.now(ZoneOffset.UTC).plusDays(4).truncatedTo(ChronoUnit.HOURS));
        cancelled.setStatus("cancelled_by_user");
        bookingRepository.saveAndFlush(cancelled);
        notPaid(cancelled.getId(), ownerToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(nothingToComplete));
        cash(cancelled.getId(), ownerToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(nothingToComplete));

        Booking expired = data.booking(stadium, player,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(5).truncatedTo(ChronoUnit.HOURS), "expired");
        notPaid(expired.getId(), ownerToken).andExpect(status().isConflict());
        cash(expired.getId(), ownerToken).andExpect(status().isConflict());

        Booking paidInApp = data.booking(stadium, player,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(6).truncatedTo(ChronoUnit.HOURS), "confirmed");
        paidInApp.setCardPrice(PLAYER_PRICE);
        paidInApp.setPaidAmount(PLAYER_PRICE);
        paidInApp.setPaymentStatus(Booking.PaymentStatus.PAID);
        bookingRepository.saveAndFlush(paidInApp);
        notPaid(paidInApp.getId(), ownerToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already paid"));
        cash(paidInApp.getId(), ownerToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already paid"));
    }
}
