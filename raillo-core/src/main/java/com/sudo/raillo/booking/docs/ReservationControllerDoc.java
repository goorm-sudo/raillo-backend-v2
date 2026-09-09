package com.sudo.raillo.booking.docs;

import org.springframework.security.core.userdetails.UserDetails;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.common.response.ErrorResponse;
import com.sudo.raillo.common.response.SuccessResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

public interface ReservationControllerDoc {
	@Operation(summary = "예약 생성", tags = "Reservations", security = @SecurityRequirement(name = "bearerAuth"),
		description = "같은 키와 같은 요청은 기존 결과를 반환합니다. 좌석 순서는 무관하며 유효기간을 연장하지 않습니다. "
			+ "시간은 UTC Unix timestamp(ms)입니다. 만료된 요청 재시도는 EXPIRED를 반환합니다.")
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "예약 생성 또는 동일 요청 결과 재생"),
		@ApiResponse(responseCode = "400", description = "잘못된 구간·좌석·멱등성 키", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
		@ApiResponse(responseCode = "401", description = "인증 필요"),
		@ApiResponse(responseCode = "409", description = "좌석 충돌·판매 마감·멱등성 키 재사용"),
		@ApiResponse(responseCode = "503", description = "기준정보 미준비·상태 불일치·결과 불명")
	})
	SuccessResponse<ReservationCreateResponse> create(@Parameter(hidden = true) UserDetails userDetails,
		@Parameter(description = "회원·운행별 요청 키, 영문/숫자/_/-, 1~128자", required = true) String idempotencyKey,
		ReservationCreateRequest request);
}
