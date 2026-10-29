package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import uz.minipolya.minipolya_backend.dto.response.AdminPaymentResponse;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;

/**
 * Payments as the admin panel shows them. The stadium is the booking's, or for a game
 * payment the game's; the list fetches both in its one query.
 */
@Mapper(componentModel = "spring")
public interface AdminPaymentMapper {

    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.name", target = "userName")
    @Mapping(source = "user.phone", target = "userPhone")
    @Mapping(source = "booking.id", target = "bookingId")
    @Mapping(source = "game.id", target = "gameId")
    @Mapping(source = "cardFeeExpected", target = "cardFee")
    @Mapping(source = "refundOfPayment.id", target = "refundOfPaymentId")
    @Mapping(target = "stadiumId", ignore = true)
    @Mapping(target = "stadiumName", ignore = true)
    @Mapping(target = "linkedPayments", ignore = true)
    AdminPaymentResponse toResponse(Payment payment);

    AdminPaymentResponse.LinkedPayment toLinked(Payment payment);

    @AfterMapping
    default void stadium(Payment payment, @MappingTarget AdminPaymentResponse response) {
        Stadium stadium = payment.getBooking() != null ? payment.getBooking().getStadium()
                : payment.getGame() != null ? payment.getGame().getStadium()
                : null;
        if (stadium != null) {
            response.setStadiumId(stadium.getId());
            response.setStadiumName(stadium.getName());
        }
        // Only said when true: a plain refund or payment has nothing to report here.
        if (!payment.isRefundedManually()) {
            response.setRefundedManually(null);
        }
    }
}
