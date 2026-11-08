package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A walk-in's payment: stored as unpaid or paid in cash, then switched with the owner's marks. */
@DatabaseTest
class ManualBookingPaymentTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    /** {@code TestData.stadium}'s own price. */
    private static final long OWNER_PRICE = 20_000_000L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private BookingRepository bookings;

    private User owner;
    private String token;
    private Stadium stadium;
    private OffsetDateTime startsAt;

    @BeforeEach
    void setUp() {
        owner = data.owner("Walkin Owner");
        token = data.bearer(owner);
        stadium = data.stadium(owner, "Walkin Arena");
        startsAt = LocalDate.now(TASHKENT).plusDays(2).atTime(18, 0).atZone(TASHKENT).toOffsetDateTime();
    }

    private ResultActions create(Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "clientName", "Aziz", "clientPhone", "+998901112233", "startsAt", startsAt.toString()));
        body.putAll(extra);
        return mvc.perform(post("/api/owner/stadiums/" + stadium.getId() + "/bookings")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private UUID createdId(ResultActions result) throws Exception {
        return UUID.fromString(json.readTree(result.andReturn().getResponse().getContentAsString())
                .path("data").path("id").asString());
    }

    private List<Payment> cashRows(UUID bookingId) {
        return payments.findByBookingId(bookingId).stream()
                .filter(p -> p.getMethod() == Payment.Method.CASH).toList();
    }

    @Test
    void aWalkInIsUnpaidUnlessSaidOtherwiseAndAtTheOwnersPrice() throws Exception {
        ResultActions result = create(Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("manual"))
                .andExpect(jsonPath("$.data.isWalkin").value(true))
                .andExpect(jsonPath("$.data.price").value(OWNER_PRICE))
                .andExpect(jsonPath("$.data.paymentStatus").value("unpaid"))
                .andExpect(jsonPath("$.data.remainingAmount").value(OWNER_PRICE))
                .andExpect(jsonPath("$.data.remainderMark").doesNotExist());

        assertThat(payments.findByBookingId(createdId(result))).isEmpty();
    }

    @Test
    void theOwnerCanSetTheirOwnPrice() throws Exception {
        create(Map.of("price", 15_000_000L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(15_000_000L))
                .andExpect(jsonPath("$.data.ownerPrice").value(15_000_000L))
                .andExpect(jsonPath("$.data.remainingAmount").value(15_000_000L));
    }

    @Test
    void addedAsPaidIsOneCashRowWithTheMarkSoItCanBeUndoneToday() throws Exception {
        ResultActions result = create(Map.of("paymentStatus", "paid", "price", 15_000_000L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("paid"))
                .andExpect(jsonPath("$.data.remainingAmount").value(0))
                .andExpect(jsonPath("$.data.paidInCash").value(15_000_000L))
                .andExpect(jsonPath("$.data.remainderMark").value("cash_received"))
                .andExpect(jsonPath("$.data.remainderMarkUndoableUntil").exists());
        UUID id = createdId(result);

        assertThat(payments.findByBookingId(id)).singleElement().satisfies(p -> {
            assertThat(p.getMethod()).isEqualTo(Payment.Method.CASH);
            assertThat(p.getProvider()).isEqualTo(Payment.Provider.CASH);
            assertThat(p.getAmount()).isEqualTo(15_000_000L);
            assertThat(p.getPlatformAmount()).isZero();
        });

        mvc.perform(delete("/api/owner/bookings/" + id + "/remainder-mark").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("unpaid"))
                .andExpect(jsonPath("$.data.remainderMark").doesNotExist())
                .andExpect(jsonPath("$.data.remainingAmount").value(15_000_000L));
        assertThat(cashRows(id)).singleElement()
                .satisfies(p -> assertThat(p.getStatus()).isEqualTo(Payment.Status.CANCELLED));
    }

    @Test
    void theOwnerSwitchesItWithTheSameMarksAsAnyBooking() throws Exception {
        UUID id = createdId(create(Map.of()));

        mvc.perform(post("/api/owner/bookings/" + id + "/not-paid").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("unpaid"))
                .andExpect(jsonPath("$.data.remainderMark").value("not_paid"));

        mvc.perform(post("/api/owner/bookings/" + id + "/cash").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("paid"))
                .andExpect(jsonPath("$.data.remainderMark").value("cash_received"));

        mvc.perform(delete("/api/owner/bookings/" + id + "/remainder-mark").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("unpaid"));
    }

    @Test
    void anUnknownPaymentStatusIs400() throws Exception {
        create(Map.of("paymentStatus", "partially_paid")).andExpect(status().isBadRequest());
    }

    @Test
    void aPriceOutOfRangeIs400() throws Exception {
        create(Map.of("price", 0)).andExpect(status().isBadRequest());
        create(Map.of("price", 500_000_001L)).andExpect(status().isBadRequest());
    }

    @Test
    void aPhoneThatIsNotUzbekIs400() throws Exception {
        create(Map.of("clientPhone", "90 111 22 33")).andExpect(status().isBadRequest());
        create(Map.of("clientPhone", "+79001112233")).andExpect(status().isBadRequest());
    }

    @Test
    void theSuggestedPriceIsWhatAWalkInWouldCost() throws Exception {
        mvc.perform(get("/api/owner/stadiums/" + stadium.getId() + "/bookings/suggested-price")
                        .param("startsAt", startsAt.toString()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(OWNER_PRICE));
    }

    @Test
    void anotherOwnerGetsNoSuggestedPrice() throws Exception {
        mvc.perform(get("/api/owner/stadiums/" + stadium.getId() + "/bookings/suggested-price")
                        .param("startsAt", startsAt.toString())
                        .header("Authorization", data.bearer(data.owner("Other Owner"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void theOwnerReportCountsWalkInsInCashAndOutstanding() throws Exception {
        OffsetDateTime today = LocalDate.now(TASHKENT).atTime(12, 0).atZone(TASHKENT).toOffsetDateTime();
        data.walkIn(stadium, "Unpaid", "+998901112233", today);
        Booking settled = data.walkIn(stadium, "Paid", "+998901112244", today.plusHours(1));
        mvc.perform(post("/api/owner/bookings/" + settled.getId() + "/cash").header("Authorization", token))
                .andExpect(status().isOk());
        bookings.flush();

        mvc.perform(get("/api/owner/dashboard/stadiums/" + stadium.getId() + "/stats").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayOutstanding").value(OWNER_PRICE))
                .andExpect(jsonPath("$.data.todayPaidInCash").value(OWNER_PRICE));
    }
}
