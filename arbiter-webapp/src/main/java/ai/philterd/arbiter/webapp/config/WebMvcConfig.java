/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.config;

import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.GeneralSettingsService;
import ai.philterd.arbiter.webapp.security.MfaEnrollmentInterceptor;
import ai.philterd.arbiter.webapp.security.PasswordChangeRequiredInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final GeneralSettingsService generalSettingsService;
    private final UserRepository userRepository;

    public WebMvcConfig(final GeneralSettingsService generalSettingsService,
                        final UserRepository userRepository) {
        this.generalSettingsService = generalSettingsService;
        this.userRepository = userRepository;
    }

    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        // Force a password rotation on first login (after an admin set the
        // initial password or reset an existing user's password). Registered
        // before the MFA enrollment gate so a user who must do both lands on
        // the password page first; the MFA gate fires once the password
        // change has cleared this one.
        registry.addInterceptor(new PasswordChangeRequiredInterceptor(userRepository))
                .excludePathPatterns("/css/**", "/js/**", "/images/**", "/webjars/**", "/docs/**",
                        "/login", "/login/**", "/logout", "/mfa", "/error",
                        "/api/**", "/v3/api-docs/**", "/swagger-ui/**");
        registry.addInterceptor(new MfaEnrollmentInterceptor(generalSettingsService, userRepository))
                .excludePathPatterns("/css/**", "/js/**", "/images/**", "/webjars/**", "/docs/**",
                        "/login", "/login/**", "/logout", "/mfa", "/error",
                        "/api/**", "/v3/api-docs/**", "/swagger-ui/**");
    }
}
