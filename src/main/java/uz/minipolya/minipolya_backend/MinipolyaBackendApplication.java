package uz.minipolya.minipolya_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * {@link UserDetailsServiceAutoConfiguration} is excluded on purpose. It is what prints
 * "Using generated security password: …" at startup: with no {@code UserDetailsService}
 * bean of our own it invents a user named {@code user} with a random password and wires it
 * into the global {@code AuthenticationManager}. Nothing here uses either — every request
 * is authenticated by {@code JwtAuthFilter}, and the admin password sign-in
 * ({@code AdminAuthService}) checks the BCrypt hash in {@code users.password_hash} itself
 * — so the account is one more way in that nobody needs, and the password in the log is
 * noise that hides real warnings. Excluding it removes the account and the line; it takes
 * nothing else away, and {@code SecurityConfig} still decides every rule.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class MinipolyaBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(MinipolyaBackendApplication.class, args);
	}

}
