package uz.minipolya.minipolya_backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.util.CardFeeSplit;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.util.function.Supplier;

/**
 * The player price, with the platform settings in force now. The one place that reads
 * {@code card_fee_bps} and {@code payment_rounding_step}; the conversion itself is
 * {@link PriceCalculator#playerAmount} and nothing else calculates it.
 *
 * <p>The owner enters their own price; players see and pay exactly one price,
 * {@code D = playerAmount(owner price)}. The platform takes nothing from an ordinary
 * booking — every such card payment is the owner's in full ({@link CardFeeSplit#toOwner}).
 * Its only money is the fixed team-game fee and, when set, the friends-game service fee.
 * Owner manual bookings are cash at the owner's own price and never converted.
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

    /** With the default settings: 150 bps, a 500 000-tiyin (5 000 so'm) step. */
    public static CardPricing withDefaults() {
        PlatformSettings defaults = PlatformSettings.builder().build();
        return new CardPricing(() -> defaults);
    }

    /**
     * These settings read once and kept: for building a list, where reading the settings
     * row for every item would cost a query per row.
     */
    public CardPricing snapshot() {
        PlatformSettings fixed = settings.get();
        return new CardPricing(() -> fixed);
    }

    public int cardFeeBps() {
        return settings.get().getCardFeeBps();
    }

    /** What players see and pay for this owner price. */
    public long playerAmount(long ownerPrice) {
        PlatformSettings s = settings.get();
        return PriceCalculator.playerAmount(ownerPrice, s.getCardFeeBps(), s.getPaymentRoundingStep());
    }

    /**
     * The deposit a player pays up front on a booking whose player price is
     * {@code bookingPlayerPrice}: {@code min(playerAmount(minBookingAmount), D)}.
     */
    public long deposit(long minBookingAmount, long bookingPlayerPrice) {
        return Math.min(playerAmount(minBookingAmount), bookingPlayerPrice);
    }

    /** A card payment that is all the owner's: an ordinary booking, its deposit or remainder. */
    public CardFeeSplit toOwner(long playerAmount) {
        return CardFeeSplit.toOwner(playerAmount, cardFeeBps());
    }

    /**
     * What a team's card is charged (or held) on joining:
     * {@code playerAmount(advance + fee)}; the platform's split nets exactly its fee, the
     * rest is the owner's.
     */
    public CardFeeSplit joinSplit(GameTeam team) {
        return CardFeeSplit.withPlatformFee(teamJoinPrice(team.getAdvanceAmount(), team.getFeeAmount()),
                team.getFeeAmount(), cardFeeBps());
    }

    /** What a team pays on joining, from its advance and the platform fee. */
    public long teamJoinPrice(long advance, long fee) {
        return playerAmount(advance + fee);
    }

    /** A team's remainder as players see it — the same in the app and in cash. */
    public long teamRemainderPrice(long remainder) {
        return remainder > 0 ? playerAmount(remainder) : 0L;
    }

    /** A team's remainder paid in the app: all the owner's. */
    public CardFeeSplit teamRemainderSplit(GameTeam team) {
        return toOwner(teamRemainderPrice(team.getRemainderAmount()));
    }

    /**
     * One player's share of a friends game: {@code playerAmount(pitch share + service fee)}.
     * Without a service fee it is all the owner's; with one, the service fee works like the
     * team fee.
     */
    public CardFeeSplit gameShareSplit(Game game) {
        long fee = game.getServiceFeePerPerson() == null ? 0 : game.getServiceFeePerPerson();
        return CardFeeSplit.withPlatformFee(playerAmount(gameShareNet(game) + fee), fee, cardFeeBps());
    }

    /** One player's share of the pitch, in the owner's terms. */
    public static long gameShareNet(Game game) {
        int players = game.getMaxPlayers() == null || game.getMaxPlayers() < 1 ? 1 : game.getMaxPlayers();
        return game.getTotalPrice() / players;
    }

    /**
     * What is left for the player to pay on a booking, as players see it. For a booking
     * priced under this rule it is simply {@code D - paid}; a booking taken before it kept
     * the owner's terms, and its rest is converted.
     */
    public long remainingForPlayer(Booking booking) {
        long remaining = booking.getRemainingAmount();
        if (remaining == 0 || booking.getPricingModel() == Booking.PricingModel.PLAYER_PRICE) {
            return remaining;
        }
        return playerAmount(remaining);
    }

    /** The whole booking as players see it: D, or converted from the owner's price on an older one. */
    public long playerPrice(Booking booking) {
        if (booking.getCardPrice() != null) {
            return booking.getCardPrice();
        }
        if (booking.getPrice() == null) {
            return 0L;
        }
        return booking.isWalkin() ? booking.getPrice() : playerAmount(booking.getPrice());
    }

    /** What the owner keeps of a card split once ATMOS has taken its fee. */
    public long ownerReceives(long ownerAmount) {
        return PriceCalculator.netAfterFee(ownerAmount, cardFeeBps());
    }

    /** What ATMOS takes of one card payment: its rate on each split. */
    public long atmosFee(Payment payment) {
        long owner = payment.getOwnerAmount() == null ? 0L : payment.getOwnerAmount();
        long platform = payment.getPlatformAmount() == null ? 0L : payment.getPlatformAmount();
        int bps = cardFeeBps();
        return owner + platform - PriceCalculator.netAfterFee(owner, bps) - PriceCalculator.netAfterFee(platform, bps);
    }

    /**
     * The largest owner price whose player price is still at most {@code playerPrice} — to
     * filter by what players see. playerAmount(n) ≤ P ⟺ grossUp(n) ≤ M where M = P rounded
     * down to a step ⟺ n ≤ floor(M · (10000 − bps) / 10000).
     */
    public long maxNetFor(long playerPrice) {
        PlatformSettings s = settings.get();
        long m = Math.floorDiv(playerPrice, s.getPaymentRoundingStep()) * s.getPaymentRoundingStep();
        return PriceCalculator.netAfterFee(m, s.getCardFeeBps());
    }
}
