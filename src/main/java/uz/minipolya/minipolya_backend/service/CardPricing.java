package uz.minipolya.minipolya_backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.util.CardFeeSplit;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.util.function.Supplier;

/**
 * The card fee, applied with the platform settings in force now. The one place that
 * reads {@code card_fee_bps} and {@code payment_rounding_step}; the arithmetic itself
 * is {@link PriceCalculator#playerAmount} and {@link CardFeeSplit}. Card payments only —
 * cash is never grossed up.
 */
@Component
public class CardPricing {

    private final Supplier<PlatformSettings> settings;

    @Autowired
    public CardPricing(PlatformSettingsService platformSettingsService) {
        this(platformSettingsService::current);
    }

    /** Fixed settings — for tests and for previews of a settings change. */
    public CardPricing(Supplier<PlatformSettings> settings) {
        this.settings = settings;
    }

    /** With the default settings: 150 bps, a 10 000-tiyin step, no booking commission. */
    public static CardPricing withDefaults() {
        PlatformSettings defaults = PlatformSettings.builder().build();
        return new CardPricing(() -> defaults);
    }

    /** What the player pays by card for this net amount. */
    public long playerAmount(long net) {
        PlatformSettings s = settings.get();
        return PriceCalculator.playerAmount(net, s.getCardFeeBps(), s.getPaymentRoundingStep());
    }

    /** {@code playerAmount(net) - net}: what paying this by card adds. */
    public long cardFee(long net) {
        return playerAmount(net) - net;
    }

    /** One card payment of a single net amount, split between owner and platform. */
    public CardFeeSplit split(long ownerNet, long platformNet) {
        PlatformSettings s = settings.get();
        return CardFeeSplit.forNet(ownerNet, platformNet, s.getCardFeeBps(), s.getPaymentRoundingStep());
    }

    /** One card payment of an already-quoted player amount (e.g. the sum of slot prices). */
    public CardFeeSplit split(long playerAmount, long ownerNet, long platformNet) {
        return CardFeeSplit.of(playerAmount, ownerNet, platformNet, settings.get().getCardFeeBps());
    }

    /** A booking's net amount split by the booking commission: owner net, then platform net. */
    public CardFeeSplit bookingSplit(long net) {
        long platformNet = bookingCommission(net);
        return split(net - platformNet, platformNet);
    }

    /** As {@link #bookingSplit(long)}, for a player amount already quoted slot by slot. */
    public CardFeeSplit bookingSplit(long playerAmount, long net) {
        long platformNet = bookingCommission(net);
        return split(playerAmount, net - platformNet, platformNet);
    }

    /** The platform's share of a booking's net, by the booking commission in force. */
    public long bookingCommission(long net) {
        return net * settings.get().getBookingCommissionPercent() / 100;
    }
}
