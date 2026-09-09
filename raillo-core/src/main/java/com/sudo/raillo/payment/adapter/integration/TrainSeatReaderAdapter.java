package com.sudo.raillo.payment.adapter.integration;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.payment.application.required.TrainSeatReader;
import com.sudo.raillo.train.application.service.TrainSeatQueryService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TrainSeatReaderAdapter implements TrainSeatReader {

	private final TrainSeatQueryService trainSeatQueryService;

	@Override
	public Long getTrainCarId(List<Long> seatIds) {
		return trainSeatQueryService.getTrainCarId(seatIds);
	}
}
