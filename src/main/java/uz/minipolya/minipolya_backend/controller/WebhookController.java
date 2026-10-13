package uz.minipolya.minipolya_backend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.service.PaymentService;

import java.util.Map;

/**
 * Provider payment callbacks. Public (no JWT); the provider's signature is the
 * trust anchor. For now these mock endpoints accept any POST and extract the
 * transaction id from the body.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WebhookController {

    private final PaymentService paymentService;

    @PostMapping("/webhooks/payme")
    public ApiResponse<Void> payme(@RequestBody Map<String, Object> body) {
        // TODO: Verify Payme request signature before trusting this callback.
        handle("payme", body);
        return ApiResponse.ok(null);
    }

    @PostMapping("/webhooks/click")
    public ApiResponse<Void> click(@RequestBody Map<String, Object> body) {
        // TODO: Verify Click request signature before trusting this callback.
        handle("click", body);
        return ApiResponse.ok(null);
    }

    private void handle(String provider, Map<String, Object> body) {
        String txnId = firstNonNull(body, "txnId", "transactionId", "providerTxnId", "id");
        String status = asString(body.get("status"));
        if (txnId == null) {
            log.warn("{} webhook missing transaction id: {}", provider, body);
            return;
        }
        paymentService.handleProviderCallback(provider, txnId, status);
    }

    private static String firstNonNull(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            String value = asString(body.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
