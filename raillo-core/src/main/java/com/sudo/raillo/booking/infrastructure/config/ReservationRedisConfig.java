package com.sudo.raillo.booking.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration(proxyBeanMethods = false)
public class ReservationRedisConfig {
	@Bean
	public DefaultRedisScript<String> createReservationScript() {
		DefaultRedisScript<String> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("scripts/create_reservation.lua"));
		script.setResultType(String.class);
		return script;
	}
}
