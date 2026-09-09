package com.sudo.raillo.booking.support;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import com.sudo.raillo.booking.application.calculator.ReservationFareCalculator;
import com.sudo.raillo.booking.application.facade.ReservationFacade;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.booking.infrastructure.config.ReservationRedisConfig;
import com.sudo.raillo.booking.util.ReservationIdGenerator;
import com.sudo.raillo.booking.presentation.ReservationController;
import com.sudo.raillo.common.exception.CommonExceptionHandler;
import com.sudo.raillo.common.response.GlobalResponseHandler;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({ReservationRedisConfig.class, ReservationRedisRepository.class, ReservationService.class,
	ReservationFacade.class, ReservationFareCalculator.class, ReservationIdGenerator.class,
	ReservationController.class, CommonExceptionHandler.class, GlobalResponseHandler.class})
public class ReservationTestConfig {
	@Bean
	UserDetailsService reservationTestUsers() {
		return new InMemoryUserDetailsManager(User.withUsername("member_1")
			.password("{noop}test-password").roles("USER").build());
	}

	@Bean
	SecurityFilterChain reservationTestSecurity(HttpSecurity http) throws Exception {
		return http.csrf(csrf -> csrf.disable())
			.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
			.httpBasic(Customizer.withDefaults()).build();
	}
}
