package com.sudo.raillo.payment.adapter.integration;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.application.required.TrainScheduleReader;
import com.sudo.raillo.train.application.service.TrainScheduleService;
import com.sudo.raillo.train.domain.ScheduleStop;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TrainScheduleReaderAdapter implements TrainScheduleReader {

	private final TrainScheduleService trainScheduleService;

	@Override
	public List<ScheduleStop> getScheduleStops(List<Long> stopIds) {
		return trainScheduleService.getScheduleStops(stopIds);
	}
}
