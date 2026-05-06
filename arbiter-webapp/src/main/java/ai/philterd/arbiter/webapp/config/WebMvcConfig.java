/*
 * Copyright 2026 Philterd
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
        registry.addInterceptor(new MfaEnrollmentInterceptor(generalSettingsService, userRepository))
                .excludePathPatterns("/css/**", "/js/**", "/images/**", "/webjars/**",
                        "/login", "/login/**", "/logout", "/mfa", "/error",
                        "/api/**", "/v3/api-docs/**", "/swagger-ui/**");
    }
}
