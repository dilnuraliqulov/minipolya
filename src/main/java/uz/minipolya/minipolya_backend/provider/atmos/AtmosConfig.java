package uz.minipolya.minipolya_backend.provider.atmos;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import uz.minipolya.minipolya_backend.config.AppProperties;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The HTTP client for ATMOS, with connect and read timeouts. A call that runs out of time
 * surfaces as {@link uz.minipolya.minipolya_backend.provider.ProviderTimeoutException}:
 * the payment stays processing and is never retried blindly. No request/response
 * logging is configured — ATMOS bodies carry card numbers, tokens and credentials.
 */
@Configuration
@Profile("atmos")
public class AtmosConfig {

    @Bean
    public RestClient.Builder atmosRestClientBuilder(AppProperties appProperties) {
        Duration timeout = Duration.ofSeconds(appProperties.getAtmos().getTimeoutSeconds());
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory);
    }
}
