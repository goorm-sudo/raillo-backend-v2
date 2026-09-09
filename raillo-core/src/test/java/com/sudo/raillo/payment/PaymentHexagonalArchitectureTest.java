package com.sudo.raillo.payment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * payment 패키지의 헥사고날 계층 의존 규칙을 강제한다.
 *
 * <p>허용되는 의존 방향: adapter → application → domain
 */
@AnalyzeClasses(
	packages = "com.sudo.raillo.payment",
	importOptions = {ImportOption.DoNotIncludeTests.class}
)
class PaymentHexagonalArchitectureTest {

	private static final String DOMAIN = "com.sudo.raillo.payment.domain..";
	private static final String APPLICATION = "com.sudo.raillo.payment.application..";
	private static final String ADAPTER = "com.sudo.raillo.payment.adapter..";

	@ArchTest
	static final ArchRule domainMustNotDependOnApplicationOrAdapter =
		noClasses().that().resideInAPackage(DOMAIN)
			.should().dependOnClassesThat().resideInAnyPackage(APPLICATION, ADAPTER)
			.because("payment.domain은 application/adapter에 의존해서는 안 됩니다.");

	@ArchTest
	static final ArchRule applicationMustNotDependOnAdapter =
		noClasses().that().resideInAPackage(APPLICATION)
			.should().dependOnClassesThat().resideInAPackage(ADAPTER)
			.because("payment.application은 adapter에 의존해서는 안 됩니다. adapter 세부 사항은 required port로만 노출되어야 합니다.");

	@ArchTest
	static final ArchRule adaptersMustNotDependOnEachOther =
		noClasses().that().resideInAPackage("com.sudo.raillo.payment.adapter.persistence..")
			.should().dependOnClassesThat()
			.resideInAnyPackage(
				"com.sudo.raillo.payment.adapter.integration..",
				"com.sudo.raillo.payment.adapter.observability..",
				"com.sudo.raillo.payment.adapter.webapi..")
			.because("payment.adapter의 하위 어댑터는 서로 직접 의존해서는 안 됩니다.");

	@ArchTest
	static final ArchRule webApiMustNotDependOnPersistenceOrIntegration =
		noClasses().that().resideInAPackage("com.sudo.raillo.payment.adapter.webapi..")
			.should().dependOnClassesThat()
			.resideInAnyPackage(
				"com.sudo.raillo.payment.adapter.persistence..",
				"com.sudo.raillo.payment.adapter.integration..",
				"com.sudo.raillo.payment.adapter.observability..")
			.because("payment.adapter.webapi는 다른 어댑터에 의존해서는 안 됩니다. provided port만 사용해야 합니다.");

	@ArchTest
	static final ArchRule requiredPortsMustBeInterfaces =
		classes().that().resideInAPackage("com.sudo.raillo.payment.application.required..")
			.and().areTopLevelClasses()
			.should().beInterfaces()
			.because("required port(top-level)는 인터페이스여야 합니다. 결과 record 같은 nested 타입은 예외입니다.");

	@ArchTest
	static final ArchRule providedPortsMustBeInterfaces =
		classes().that().resideInAPackage("com.sudo.raillo.payment.application.provided..")
			.and().areTopLevelClasses()
			.should().beInterfaces()
			.because("provided port(top-level)는 인터페이스여야 합니다.");

	@ArchTest
	static final ArchRule domainMustNotDependOnFrameworkAnnotations =
		noClasses().that().resideInAPackage(DOMAIN)
			.should().dependOnClassesThat()
			.resideInAnyPackage(
				"jakarta.validation..",
				"io.swagger..",
				"org.springframework.web..")
			.because("payment.domain은 웹/검증/문서화 프레임워크에 의존해서는 안 됩니다. "
				+ "입력 DTO는 adapter.webapi.dto에 두고, 애플리케이션은 순수 Command/Value를 전달받아야 합니다.");

	@ArchTest
	static final ArchRule mustNotReachIntoOtherDomainsInternals =
		noClasses().that().resideInAPackage("com.sudo.raillo.payment..")
			.should().dependOnClassesThat()
			.resideInAnyPackage(
				"com.sudo.raillo.booking.infrastructure..",
				"com.sudo.raillo.booking.adapter..",
				"com.sudo.raillo.member.infrastructure..",
				"com.sudo.raillo.member.adapter..",
				"com.sudo.raillo.order.infrastructure..",
				"com.sudo.raillo.order.adapter..",
				"com.sudo.raillo.train.infrastructure..",
				"com.sudo.raillo.train.adapter..")
			.because("payment은 타 도메인의 인프라/어댑터 세부 구현에 접근해서는 안 됩니다. "
				+ "다른 도메인은 각자의 application/domain 진입점을 통해서만 통신합니다.");
}
