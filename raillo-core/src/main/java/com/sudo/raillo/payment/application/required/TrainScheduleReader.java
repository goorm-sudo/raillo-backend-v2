package com.sudo.raillo.payment.application.required;

import java.util.List;

import com.sudo.raillo.train.domain.ScheduleStop;

/**
 * Train 스케줄 조회 required port.
 */
public interface TrainScheduleReader {

	List<ScheduleStop> getScheduleStops(List<Long> stopIds);
}
