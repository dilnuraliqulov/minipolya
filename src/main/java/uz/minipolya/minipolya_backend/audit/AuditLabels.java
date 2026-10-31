package uz.minipolya.minipolya_backend.audit;

import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.BookingSeries;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.OwnerApplication;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PricingRule;
import uz.minipolya.minipolya_backend.entity.Promotion;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumClosure;
import uz.minipolya.minipolya_backend.entity.User;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The {@code entity_label} each kind of entity gets: short, human, and saved at the time
 * so the entry stays readable after the thing is renamed or deleted. Times are shown in
 * Asia/Tashkent, the way the people reading the log think about them.
 */
public final class AuditLabels {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private AuditLabels() {
    }

    public static String user(User user) {
        if (user == null) {
            return null;
        }
        return user.getUsername() == null ? user.getName() : user.getName() + " (" + user.getUsername() + ")";
    }

    public static String stadium(Stadium stadium) {
        return stadium == null ? null : stadium.getName();
    }

    /** "Chilonzor Arena, 2026-11-02 20:00 (2h)" — plus the walk-in client's name for a manual booking. */
    public static String booking(Booking booking) {
        String label = stadium(booking.getStadium()) + ", " + local(booking.getStartsAt())
                + " (" + booking.getDurationHours() + "h)";
        return booking.getClientName() == null ? label : label + ", " + booking.getClientName();
    }

    /** "Chilonzor Arena, 2026-11-02 20:00, team_vs_team". */
    public static String game(Game game) {
        return stadium(game.getStadium()) + ", " + local(game.getStartsAt()) + ", " + game.getType();
    }

    /** "Chilonzor Arena, MONDAY 18:00–22:00". */
    public static String pricingRule(PricingRule rule) {
        return stadium(rule.getStadium()) + ", " + day(rule.getDayOfWeek()) + " "
                + rule.getStartTime() + "–" + rule.getEndTime();
    }

    /** "Chilonzor Arena, 2026-11-01..2026-11-03" (+ hours for a part-day closure). */
    public static String closure(StadiumClosure closure) {
        String label = stadium(closure.getStadium()) + ", " + closure.getStartDate() + ".." + closure.getEndDate();
        return closure.getStartTime() == null ? label
                : label + " " + closure.getStartTime() + "–" + closure.getEndTime();
    }

    public static String promotion(Promotion promotion) {
        return stadium(promotion.getStadium()) + ", " + promotion.getName();
    }

    /** "Chilonzor Arena, every THURSDAY 20:00 (1h)". */
    public static String bookingSeries(BookingSeries series) {
        return stadium(series.getStadium()) + ", every " + day(series.getDayOfWeek()) + " "
                + series.getStartTime() + " (" + series.getDurationHours() + "h)";
    }

    /** "Ali Valiyev — Chilonzor Sport LLC". */
    public static String ownerApplication(OwnerApplication application) {
        String applicant = application.getUser() == null ? null : application.getUser().getName();
        return applicant + " — " + application.getBusinessName();
    }

    /** "booking — Chilonzor Arena, 2026-11-02 20:00 (1h)": the payment's type and what it paid for. */
    public static String payment(Payment payment) {
        String type = payment.getPaymentType() == null ? "payment" : payment.getPaymentType().getValue();
        if (payment.getBooking() != null) {
            return type + " — " + booking(payment.getBooking());
        }
        if (payment.getGame() != null) {
            return type + " — " + game(payment.getGame());
        }
        return type;
    }

    private static String local(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(TASHKENT).format(LOCAL);
    }

    /** dayOfWeek is 0 = Monday … 6 = Sunday throughout the project. */
    private static String day(Integer dayOfWeek) {
        return dayOfWeek == null ? "?" : DayOfWeek.of(dayOfWeek + 1).name();
    }
}
