package uz.minipolya.minipolya_backend.service;

import uz.minipolya.minipolya_backend.util.CardFeeSplit;

/** Card charges as the services build them, with the default fee settings (150 bps, 5 000 so'm step). */
final class TestPrices {

    private static final CardPricing DEFAULTS = CardPricing.withDefaults();

    private TestPrices() {
    }

    /** A booking card charge for the player price of {@code net}: all the owner's. */
    static CardFeeSplit net(long net) {
        return DEFAULTS.toOwner(DEFAULTS.playerAmount(net));
    }

    /** A booking card charge of exactly {@code playerAmount}: all the owner's. */
    static CardFeeSplit charge(long playerAmount) {
        return DEFAULTS.toOwner(playerAmount);
    }

    /** What the player pays by card for {@code net}. */
    static long card(long net) {
        return DEFAULTS.playerAmount(net);
    }
}
