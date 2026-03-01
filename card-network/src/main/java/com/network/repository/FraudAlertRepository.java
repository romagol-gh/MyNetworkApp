package com.network.repository;

import com.network.domain.FraudAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, UUID> {
    Page<FraudAlert> findByReviewed(boolean reviewed, Pageable pageable);
    List<FraudAlert> findByTransactionId(UUID transactionId);
    long countByReviewedFalse();
}
