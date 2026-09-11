package com.sudo.raillo.payment.adapter.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.payment.domain.PaymentAttempt;
import com.sudo.raillo.payment.domain.PaymentAttemptStatus;
import com.sudo.raillo.payment.domain.PaymentAttemptType;

@Repository
public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttempt, Long> {

	Optional<PaymentAttempt> findByAttemptId(String attemptId);

	Optional<PaymentAttempt> findFirstByPaymentIdAndAttemptTypeOrderByIdDesc(Long paymentId, PaymentAttemptType type);

	@Query("""
		select a
		  from PaymentAttempt a
		 where a.status = :status
		   and a.attemptType = :type
		   and a.updatedAt < :threshold
		 order by a.updatedAt asc
		""")
	List<PaymentAttempt> findStaleInProgress(
		@Param("status") PaymentAttemptStatus status,
		@Param("type") PaymentAttemptType type,
		@Param("threshold") LocalDateTime threshold,
		Limit limit
	);
}
