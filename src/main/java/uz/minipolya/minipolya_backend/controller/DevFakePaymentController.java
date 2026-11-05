package uz.minipolya.minipolya_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.DevFakePaymentsResponse;
import uz.minipolya.minipolya_backend.provider.fake.FakePaymentsView;

/**
 * What the fake payment provider did, for testing payment flows in dev. Registered only
 * under the {@code dev} profile with the fake provider, and behind {@code X-Dev-Login-Key}
 * like the dev sign-in: anywhere else, or without the key, the path answers 404.
 */
@RestController
@Profile("dev & !atmos")
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "fake")
@RequiredArgsConstructor
public class DevFakePaymentController {

    private final FakePaymentsView view;

    /** The latest fake cards (masked), charges, holds and reverses; {@code limit} 1–100 per list, default 20. */
    @GetMapping("/api/dev/fake-payments")
    public ApiResponse<DevFakePaymentsResponse> latest(
            @RequestParam(defaultValue = "" + FakePaymentsView.DEFAULT_LIMIT) int limit) {
        return ApiResponse.ok(view.latest(limit));
    }
}
