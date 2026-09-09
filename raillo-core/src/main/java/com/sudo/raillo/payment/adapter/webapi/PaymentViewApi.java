package com.sudo.raillo.payment.adapter.webapi;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import lombok.extern.slf4j.Slf4j;

/**
 * 토스 결제 위젯 테스트 페이지 뷰 컨트롤러. 개발/테스트 편의용이며 실제 결제 승인은
 * {@link PaymentApi#confirmPayment}가 담당한다.
 */
@Slf4j
@Controller
@RequestMapping("/test/payments")
public class PaymentViewApi {

	@GetMapping
	public String paymentPage(Model model) {
		log.info(">>> PaymentViewApi /test/payments HIT");
		model.addAttribute("clientKey", "test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm");
		model.addAttribute("orderId", "ORDER_" + System.currentTimeMillis());
		model.addAttribute("amount", 1000);
		return "payment";
	}

	@GetMapping("/confirm")
	public String confirm(
		@RequestParam String paymentKey,
		@RequestParam String orderId,
		@RequestParam Long amount,
		Model model
	) {
		model.addAttribute("paymentKey", paymentKey);
		model.addAttribute("orderId", orderId);
		model.addAttribute("amount", amount);
		return "payment-confirm";
	}

	@GetMapping("/fail")
	public String fail(
		@RequestParam String code,
		@RequestParam String message,
		@RequestParam(required = false) String orderId,
		Model model
	) {
		model.addAttribute("errorCode", code);
		model.addAttribute("errorMessage", message);
		model.addAttribute("orderId", orderId);
		return "payment-fail";
	}
}
