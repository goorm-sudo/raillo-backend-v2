package com.sudo.raillo.payment.application.required;

import java.util.List;

/**
 * Train 좌석 정보 조회 required port.
 */
public interface TrainSeatReader {

	Long getTrainCarId(List<Long> seatIds);
}
