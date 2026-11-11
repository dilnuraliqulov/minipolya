package uz.minipolya.minipolya_backend.util;

/** Money in messages: tiyin shown as so'm, thousands separated by a space ("25 000 so'm"). */
public final class Soum {

    private Soum() {
    }

    /** {@code 2_500_000} tiyin → {@code "25 000 so'm"}; a part so'm is dropped. */
    public static String of(long tiyin) {
        String digits = Long.toString(Math.abs(tiyin / 100));
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(digits.charAt(i));
        }
        return (tiyin < 0 ? "-" : "") + grouped + " so'm";
    }
}
