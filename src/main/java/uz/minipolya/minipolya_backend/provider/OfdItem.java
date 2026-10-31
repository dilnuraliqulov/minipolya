package uz.minipolya.minipolya_backend.provider;

/**
 * One fiscal receipt line ({@code ofd_items}): the IKPU code, a name and the amount.
 *
 * @param ofdCode IKPU product/service code
 * @param name    what the line is for
 * @param amount  tiyin
 */
public record OfdItem(String ofdCode, String name, long amount) {
}
