package com.sudo.raillo.payment.adapter.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.payment.domain.PaymentOutbox;
import com.sudo.raillo.payment.domain.PaymentOutboxStatus;

@Repository
public interface PaymentOutboxJpaRepository extends JpaRepository<PaymentOutbox, Long> {

	Optional<PaymentOutbox> findByDeduplicationKey(String deduplicationKey);

	@Query("""
		select o
		  from PaymentOutbox o
		 where o.status = :status
		   and (o.nextRetryAt is null or o.nextRetryAt <= :now)
		 order by o.nextRetryAt asc nulls first, o.id asc
		""")
	List<PaymentOutbox> findProcessable(
		@Param("status") PaymentOutboxStatus status,
		@Param("now") LocalDateTime now,
		Limit limit
	);
}
