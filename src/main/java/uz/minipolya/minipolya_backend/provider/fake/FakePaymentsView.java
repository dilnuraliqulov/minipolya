package uz.minipolya.minipolya_backend.provider.fake;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import uz.minipolya.minipolya_backend.dto.response.DevFakePaymentsResponse;
import uz.minipolya.minipolya_backend.provider.CardMask;
import uz.minipolya.minipolya_backend.provider.FakePaymentProvider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** What the fake provider holds, for {@code GET /api/dev/fake-payments}. Dev profile, fake provider only. */
@Service
@Profile("dev & !atmos")
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "fake")
@RequiredArgsConstructor
public class FakePaymentsView {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final FakeProviderStore store;

    public DevFakePaymentsResponse latest(int limit) {
        int n = Math.clamp(limit, 1, MAX_LIMIT);
        List<FakeCard> cards = store.latestCards(n);
        Map<String, String> maskedPans = cards.stream()
                .collect(Collectors.toMap(FakeCard::getCardId, FakeCard::getMaskedPan, (a, b) -> a));
        Function<FakeOperation, DevFakePaymentsResponse.Operation> operation = o -> toResponse(o, maskedPans);
        return DevFakePaymentsResponse.builder()
                .cards(cards.stream().map(FakePaymentsView::toResponse).toList())
                .charges(store.latestOperations(FakeOperation.Kind.CHARGE, n).stream().map(operation).toList())
                .holds(store.latestOperations(FakeOperation.Kind.HOLD, n).stream().map(operation).toList())
                .reverses(store.latestReverses(n).stream().map(FakePaymentsView::toResponse).toList())
                .build();
    }

    /** The scenario a card's last four digits pick (see "To simulate X in dev" in CLAUDE.md). */
    public static String scenarioOf(String last4) {
        return switch (last4 == null ? "" : last4) {
            case FakePaymentProvider.DECLINED_SUFFIX -> "declined";
            case FakePaymentProvider.INSUFFICIENT_SUFFIX -> "insufficient_funds";
            case FakePaymentProvider.EXPIRED_SUFFIX -> "card_expired";
            case FakePaymentProvider.TIMEOUT_THEN_SUCCESS_SUFFIX -> "timeout_then_success";
            case FakePaymentProvider.TIMEOUT_THEN_FAILED_SUFFIX -> "timeout_then_failed";
            case FakePaymentProvider.UNAVAILABLE_SUFFIX -> "provider_unavailable";
            case FakePaymentProvider.REVERSE_FAILS_SUFFIX -> "reverse_fails";
            case FakePaymentProvider.CAPTURE_FAILS_SUFFIX -> "capture_fails";
            default -> "success";
        };
    }

    private static DevFakePaymentsResponse.Card toResponse(FakeCard c) {
        return DevFakePaymentsResponse.Card.builder()
                .cardId(c.getCardId())
                .maskedPan(c.getMaskedPan())
                .cardType(CardMask.type(c.getMaskedPan()))
                .expiry(c.getExpiry())
                .cardHolder(c.getCardHolder())
                .maskedPhone(c.getMaskedPhone())
                .balance(c.getBalance())
                .scenario(scenarioOf(c.getLast4()))
                .isActive(c.isActive())
                .createdAt(c.getCreatedAt())
                .removedAt(c.getRemovedAt())
                .build();
    }

    private static DevFakePaymentsResponse.Operation toResponse(FakeOperation o, Map<String, String> maskedPans) {
        return DevFakePaymentsResponse.Operation.builder()
                .id(o.getId())
                .account(o.getAccount())
                .cardId(o.getCardId())
                .maskedPan(maskedPans.getOrDefault(o.getCardId(), "•••• •••• •••• " + o.getLast4()))
                .status(o.getStatus().name())
                .errorCode(o.getErrorCode())
                .amount(o.getAmount())
                .commission(o.getCommission())
                .holdTill(o.getHoldTill())
                .succeededAt(o.getSucceededAt())
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .splits(o.getSplits().stream().map(s -> DevFakePaymentsResponse.Split.builder()
                        .transactionId(s.getTransactionId())
                        .recipient(s.isPlatform() ? "platform" : "owner")
                        .recipientId(s.getRecipientId())
                        .amount(s.getAmount())
                        .commission(s.getCommission())
                        .recipientReceives(s.getAmount() - s.getCommission())
                        .successTransId(s.getSuccessTransId())
                        .reversedAt(s.getReversedAt())
                        .build()).toList())
                .build();
    }

    private static DevFakePaymentsResponse.Reverse toResponse(FakeReverse r) {
        return DevFakePaymentsResponse.Reverse.builder()
                .id(r.getId())
                .operationId(r.getOperationId())
                .transactionId(r.getTransactionId())
                .amount(r.getAmount())
                .outcome(r.getOutcome().name())
                .errorCode(r.getErrorCode())
                .reason(r.getReason())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
