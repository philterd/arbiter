package ai.philterd.arbiter.api.controller;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Set;

/** Tiny helper for building Authentication instances in controller unit tests. */
final class TestAuth {

    static Authentication user(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                Set.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    static Authentication admin(final String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private TestAuth() {}
}
