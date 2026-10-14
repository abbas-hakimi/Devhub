package com.versioncontrol.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.versioncontrol.security.JwtAuthenticationFilter;
import com.versioncontrol.security.RestAuthenticationEntryPoint;
import com.versioncontrol.security.VersionControlUserDetailsService;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(
            VersionControlUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            AuthenticationProvider authenticationProvider
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint))
                .authenticationProvider(authenticationProvider)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/health",
                                "/actuator/health",
                                "/api/auth/register",
                                "/api/auth/login"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/repositories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/commits/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/repositories/{id:\\d+}",
                                "/api/repositories/{repositoryId:\\d+}/commits",
                                "/api/repositories/{repositoryId:\\d+}/compare",
                                "/api/repositories/{repositoryId:\\d+}/pull-requests",
                                "/api/repositories/{repositoryId:\\d+}/issues",
                                "/api/repositories/{repositoryId:\\d+}/labels",
                                "/api/repositories/{repositoryId:\\d+}/milestones",
                                "/api/repositories/{repositoryId:\\d+}/branches",
                                "/api/branches/{branchId:\\d+}",
                                "/api/milestones/{milestoneId:\\d+}",
                                "/api/issues/{issueId:\\d+}",
                                "/api/issues/{issueId:\\d+}/comments",
                                "/api/pull-requests/{pullRequestId:\\d+}",
                                "/api/pull-requests/{pullRequestId:\\d+}/diff",
                                "/api/pull-requests/{pullRequestId:\\d+}/reviews",
                                "/api/pull-requests/{pullRequestId:\\d+}/review-status",
                                "/api/pull-requests/{pullRequestId:\\d+}/mergeability",
                                "/api/pull-requests/{pullRequestId:\\d+}/comments"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

