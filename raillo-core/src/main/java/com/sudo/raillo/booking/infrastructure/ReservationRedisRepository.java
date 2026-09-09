package com.sudo.raillo.booking.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.common.exception.BusinessException;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class ReservationRedisRepository {
	private final RedisTemplate<String, Object> redis;
	private final DefaultRedisScript<String> script;
	private final JsonMapper mapper = JsonMapper.builder().build();

	public ReservationRedisRepository(RedisConnectionFactory connectionFactory,
		@Qualifier("createReservationScript") DefaultRedisScript<String> script) {
		this.script = script;
		// 다른 도메인의 serializer/주입 후보를 변경하지 않는 전용 template.
		this.redis = new RedisTemplate<>();
		redis.setConnectionFactory(connectionFactory);
		redis.setDefaultSerializer(new StringRedisSerializer());
		redis.afterPropertiesSet();
	}

	public ReservationScriptResult execute(ReservationScriptCommand command) {
		try {
			String result = redis.execute(script,
				new ReservationKeys(Long.parseLong(command.trainScheduleId())).creationKeys(),
				mapper.writeValueAsString(command));
			if (result == null) {
				throw new BusinessException(BookingError.RESERVATION_RESULT_UNKNOWN);
			}
			ReservationScriptResult decoded = mapper.readValue(result, ReservationScriptResult.class);
			if (decoded == null || decoded.code() == null) {
				throw new BusinessException(BookingError.RESERVATION_RESULT_UNKNOWN);
			}
			return decoded;
		} catch (BusinessException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			// 연결 실패/응답 유실을 예약 실패로 확정하거나 선점을 해제하지 않는다.
			throw new BusinessException(BookingError.RESERVATION_RESULT_UNKNOWN, exception);
		}
	}
}
