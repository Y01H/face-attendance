package cn.weishi.attendance;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

@Configuration
public class SecurityConfig {
    private final boolean authEnabled;

    public SecurityConfig(@Value("${app.auth-enabled:${AUTH_ENABLED:false}}") boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    @Bean
    public UserDetailsService users(AppConfig config) {
        if (!authEnabled) return new InMemoryUserDetailsManager();
        String password = config.required("admin-password");
        if (password.length() < 12) throw new IllegalArgumentException("管理员密码至少 12 个字符");
        return new InMemoryUserDetailsManager(User.withUsername(config.text("admin-user", "admin"))
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
    }

    @Bean
    public SecurityFilterChain security(HttpSecurity http) throws Exception {
        if (!authEnabled) {
            http.authorizeRequests().anyRequest().permitAll();
            http.formLogin().disable();
            http.httpBasic().disable();
            http.logout().disable();
            http.requestCache().requestCache(new NullRequestCache());
            // Keep CSRF enabled: /api/csrf supplies the token used by app.js.
            http.headers().contentSecurityPolicy("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; object-src 'none'; frame-ancestors 'none'");
            return http.build();
        }
        http.authorizeRequests().antMatchers("/login", "/error").permitAll().anyRequest().authenticated()
                .and().formLogin().defaultSuccessUrl("/", true);
        // Browser clients authenticate with the login form and session cookie.
        // A Basic challenge on a polling/image request opens a native browser dialog.
        http.httpBasic().disable();
        http.requestCache().requestCache(new NullRequestCache());
        http.exceptionHandling().defaultAuthenticationEntryPointFor((request, response, exception) -> {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"LOGIN_REQUIRED\",\"message\":\"登录已失效，请重新登录\"}");
        }, new AntPathRequestMatcher("/api/**"));
        http.exceptionHandling().defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"), AnyRequestMatcher.INSTANCE);
        http.headers().contentSecurityPolicy("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; object-src 'none'; frame-ancestors 'none'");
        return http.build();
    }
}
