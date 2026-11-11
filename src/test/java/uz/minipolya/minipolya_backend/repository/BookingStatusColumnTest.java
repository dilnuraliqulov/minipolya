package uz.minipolya.minipolya_backend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Every booking status the CHECK constraint allows fits the column — 'cancelled_opponent_left' is 23 characters. */
@DatabaseTest
class BookingStatusColumnTest {

    @Autowired
    private TestData data;
    @Autowired
    private BookingRepository bookings;

    @Test
    void theLongestStatusIsStored() {
        User owner = data.owner("Status Owner");
        Stadium stadium = data.stadium(owner, "Status Arena");
        Booking booking = data.booking(stadium, data.user("Status Player"),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withMinute(0).withSecond(0).withNano(0), "confirmed");

        booking.setStatus("cancelled_opponent_left");
        bookings.saveAndFlush(booking);

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo("cancelled_opponent_left");
    }
}
