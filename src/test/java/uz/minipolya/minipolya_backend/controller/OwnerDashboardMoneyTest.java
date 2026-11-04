package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.QueryCounter;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R38: the owner dashboard's money — the unpaid bookings list and, per period, what was paid
 * in the app, in cash, and what is still outstanding.
 */
@DatabaseTest
class OwnerDashboardMoneyTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final long PRICE = 20_310_000L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private QueryCounter queryCounter;

    private User owner;
    private String ownerToken;
    private User player;
    private Stadium stadium;
    private OffsetDateTime todayTen;

    @BeforeEach
    void setUp() {
        owner = data.owner("Money Owner");
        ownerToken = data.bearer(owner);
        player = data.user("Money Player");
        stadium = data.stadium(owner, "Money Arena");
        todayTen = LocalDate.now(TASHKENT).atTime(10, 0).atZone(TASHKENT).toOffsetDateTime();
    }

    private Booking booking(OffsetDateTime startsAt, String status, long paid) {
        Booking b = data.booking(stadium, player, startsAt, status);
        b.setCardPrice(PRICE);
        b.setPaidAmount(paid);
        b.setPaymentStatus(paid == 0 ? Booking.PaymentStatus.UNPAID
                : paid >= PRICE ? Booking.PaymentStatus.PAID : Booking.PaymentStatus.PARTIALLY_PAID);
        return bookingRepository.saveAndFlush(b);
    }

    @Test
    void unpaidListsOnlyLiveBookingsWithSomethingOwedSoonestFirst() throws Exception {
        Booking later = booking(todayTen.plusDays(2), "confirmed", 5_000_000L);
        Booking sooner = booking(todayTen.plusDays(1), "completed", 5_000_000L);
        booking(todayTen.plusDays(3), "confirmed", PRICE);                 // paid in full
        booking(todayTen.plusDays(4), "cancelled_by_user", 5_000_000L);   // cancelled
        booking(todayTen.plusDays(5), "expired", 0L);                      // expired
        data.booking(data.stadium(owner, "Other Pitch"), player, todayTen.plusDays(1), "confirmed");

        String url = "/api/owner/dashboard/stadiums/" + stadium.getId() + "/bookings";
        mvc.perform(get(url).param("unpaid", "true").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].id").value(sooner.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].remainingAmount").value(PRICE - 5_000_000L))
                .andExpect(jsonPath("$.data.content[0].ownerPrice").exists())
                .andExpect(jsonPath("$.data.content[1].id").value(later.getId().toString()));

        // Paged in the query: page 1 of size 1 is the later one, totals still exact.
        mvc.perform(get(url).param("unpaid", "true").param("size", "1").param("page", "1")
                        .header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(later.getId().toString()));

        // Without the flag, everything as before.
        mvc.perform(get(url).header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data.totalElements").value(5));
    }

    @Test
    void statsSplitPaidInAppPaidInCashAndOutstanding() throws Exception {
        Booking today = booking(todayTen, "confirmed", 5_000_000L);
        data.cardPayment(today, 5_000_000L, 5_000_000L, 0L, null, Payment.Status.PAID);
        Booking walkIn = data.walkIn(stadium, "Walk-in", "+998901112233", todayTen.plusHours(2));
        data.cashPayment(walkIn, 20_000_000L);
        walkIn.setPaidAmount(20_000_000L);
        walkIn.setPaymentStatus(Booking.PaymentStatus.PAID);
        bookingRepository.saveAndFlush(walkIn);
        // A declined card counts nowhere; a cancelled booking owes nothing.
        data.cardPayment(today, 7_000_000L, 7_000_000L, 0L, null, Payment.Status.FAILED);
        booking(todayTen.plusHours(4), "cancelled_by_owner", 0L);

        mvc.perform(get("/api/owner/dashboard/stadiums/" + stadium.getId() + "/stats")
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayPaidInApp").value(5_000_000))
                .andExpect(jsonPath("$.data.todayPaidInCash").value(20_000_000))
                .andExpect(jsonPath("$.data.todayOutstanding").value(PRICE - 5_000_000L))
                .andExpect(jsonPath("$.data.weekPaidInApp").value(5_000_000))
                .andExpect(jsonPath("$.data.weekPaidInCash").value(20_000_000))
                .andExpect(jsonPath("$.data.weekOutstanding").value(PRICE - 5_000_000L))
                .andExpect(jsonPath("$.data.monthPaidInApp").value(5_000_000))
                .andExpect(jsonPath("$.data.monthPaidInCash").value(20_000_000))
                .andExpect(jsonPath("$.data.monthOutstanding").value(PRICE - 5_000_000L));
    }

    @Test
    void statsCostAFixedNumberOfQueriesHoweverManyBookings() throws Exception {
        for (int i = 0; i < 22; i++) {
            Booking b = booking(todayTen.minusDays(1).plusHours(i % 12).plusDays(i / 12 * 2L), "confirmed", 5_000_000L);
            data.cardPayment(b, 5_000_000L, 5_000_000L, 0L, null, Payment.Status.PAID);
        }
        String url = "/api/owner/dashboard/stadiums/" + stadium.getId() + "/stats";
        queryCounter.start();
        mvc.perform(get(url).header("Authorization", ownerToken)).andExpect(status().isOk());
        long few = queryCounter.count();
        booking(todayTen.plusDays(1).plusHours(13), "confirmed", 5_000_000L);
        queryCounter.start();
        mvc.perform(get(url).header("Authorization", ownerToken)).andExpect(status().isOk());
        long more = queryCounter.count();
        org.assertj.core.api.Assertions.assertThat(more).isEqualTo(few);
    }

    @Test
    void accessRules() throws Exception {
        String bookings = "/api/owner/dashboard/stadiums/" + stadium.getId() + "/bookings?unpaid=true";
        String stats = "/api/owner/dashboard/stadiums/" + stadium.getId() + "/stats";
        for (String url : new String[]{bookings, stats}) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", data.bearer(player))).andExpect(status().isForbidden());
            mvc.perform(get(url).header("Authorization", data.bearer(data.owner("Other Owner " + url.length()))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("You do not own this stadium"));
        }
    }
}
