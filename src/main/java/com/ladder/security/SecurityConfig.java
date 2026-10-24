package com.ladder.security;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

/**
 * Session login for the browser pages the app itself serves.
 *
 * Why a session and not JWT: the frontend is static HTML served by this same
 * app, so the browser already sends the session cookie on every request, and
 * logout and revocation are immediate (delete the session). A JWT would add
 * token storage in JavaScript and a revocation problem for nothing.
 *
 * Why Spring's formLogin and not a hand-written login endpoint: it already
 * does session-fixation protection, CSRF-token rotation on login and saving
 * the security context into the session. All easy to get subtly wrong.
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // The username is the user's email; the Spring principal's name is the
    // user id, so controllers get the id without a second lookup.
    @Bean
    UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return email -> {
            List<org.springframework.security.core.userdetails.UserDetails> found = jdbc.query(
                    "SELECT user_id, password_hash, activated FROM users WHERE email = ?",
                    (rs, row) -> User.withUsername(rs.getObject("user_id", UUID.class).toString())
                            .password(rs.getString("password_hash"))
                            .disabled(!rs.getBoolean("activated"))
                            .build(),
                    email.trim().toLowerCase(Locale.ROOT));
            // A user without a password hash (created before login existed)
            // can't log in. The row's null hash would otherwise fail inside
            // the encoder instead of as an ordinary "bad credentials".
            if (found.isEmpty() || found.get(0).getPassword() == null) {
                throw new UsernameNotFoundException("no login for " + email);
            }
            return found.get(0);
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF stays ON. spa(): the token travels in a cookie that
                // JavaScript may read (XSRF-TOKEN) and comes back in the
                // X-XSRF-TOKEN header on every POST/PUT/DELETE.
                .csrf(csrf -> csrf.spa())
                // spa() loads the token lazily, so on a plain GET no cookie is
                // written and the page would have nothing to send back. This
                // filter touches the token on every request to force the cookie out.
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // The pages themselves, plus /error: without it, a 404
                        // or 409 thrown by a controller is re-dispatched to
                        // /error and an anonymous caller would see 401 instead.
                        .requestMatchers(HttpMethod.GET, "/", "/*.html", "/*.css", "/*.js", "/error").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        // Anyone may ask to watch; VideoAccess decides per video
                        // (private ones answer 404 to everybody without the permission).
                        .requestMatchers(HttpMethod.GET, "/api/v1/videos/*/master.m3u8").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/videos/*/thumbnail").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/videos/*/*/playlist.m3u8").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/videos/*/view").permitAll()
                        // The read side: feed, video details, channel pages. Each service
                        // method decides per video what the caller may see.
                        .requestMatchers(HttpMethod.GET, "/api/v1/videos", "/api/v1/videos/*", "/api/v1/channels/*").permitAll()
                        .anyRequest().authenticated())
                // Remembering "the page you wanted before login" costs a server
                // session per anonymous 401, and /me runs on every page load. An
                // API never redirects back, so the cache is off.
                .requestCache(cache -> cache.disable())
                // An API answers "not logged in" with 401, not a redirect to a login page.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form
                        // Moves the login to our own URL and turns off the
                        // generated HTML login page. POST here logs in.
                        .loginPage("/api/v1/auth/login")
                        .usernameParameter("email")
                        .successHandler((request, response, auth) -> response.setStatus(HttpStatus.NO_CONTENT.value()))
                        // Same 401 for a wrong password and an unknown email:
                        // never tell a caller which half was wrong.
                        .failureHandler((request, response, ex) -> response.setStatus(HttpStatus.UNAUTHORIZED.value()))
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
        return http.build();
    }

    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken(); // reading it is what makes the repository write the cookie
            }
            chain.doFilter(request, response);
        }
    }
}
