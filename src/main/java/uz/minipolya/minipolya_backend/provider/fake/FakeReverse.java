package uz.minipolya.minipolya_backend.provider.fake;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** One reverse asked of the fake, done or refused, for the dev view. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FakeReverse {

    public enum Outcome { REVERSED, REFUSED }

    private String id;
    private String operationId;
    /** The success transaction id (or captured hold id) that was named. */
    private String transactionId;
    /** Tiyin given back; 0 when refused. */
    private long amount;
    private Outcome outcome;
    private String errorCode;
    private String reason;
    private OffsetDateTime createdAt;
}
