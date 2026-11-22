package vn.ptit.slms.security;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import vn.ptit.slms.common.*;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder encoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(Db db) {
        return username -> {
            var r = db.rows("select * from app_user where username=? and active", username);
            if (r.isEmpty())
                throw new UsernameNotFoundException("Unknown user");
            var u = r.get(0);
            return User.withUsername(username).password(u.get("password_hash").toString())
                    .roles(u.get("role").toString()).build();
        };
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity h) throws Exception {
        h.csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .authorizeHttpRequests(
                        a -> a.requestMatchers("/api/auth/csrf", "/api/auth/login", "/error")
                                .permitAll().anyRequest().authenticated())
                .formLogin(
                        f -> f.loginProcessingUrl("/api/auth/login").successHandler((q, r, a) -> {
                            r.setContentType("application/json");
                            r.getWriter().write("{\"ok\":true}");
                        }).failureHandler((q, r, e) -> r.setStatus(401)))
                .logout(l -> l.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((q, r, a) -> r.setStatus(204)))
                .exceptionHandling(e -> e.authenticationEntryPoint((q, r, x) -> r.sendError(401))
                        .accessDeniedHandler((q, r, x) -> r.sendError(403)));
        return h.build();
    }
}
