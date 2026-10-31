package uz.minipolya.minipolya_backend.service;

import uz.minipolya.minipolya_backend.util.CardFeeSplit;

/** Card charges as the services build them, with the default fee settings (150 bps, 100 so'm step). */
final class TestPrices {

    private static final CardPricing DEFAULTS = CardPricing.withDefaults();

    private TestPrices() {
    }

    /** A booking card charge worth {@code net} to the owner: playerAmount(net), no commission. */
    static CardFeeSplit net(long net) {
        return DEFAULTS.bookingSplit(net);
    }

    /** What the player pays by card for {@code net}. */
    static long card(long net) {
        return DEFAULTS.playerAmount(net);
    }
}
