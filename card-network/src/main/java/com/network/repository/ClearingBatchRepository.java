package com.network.repository;

import com.network.domain.ClearingBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ClearingBatchRepository extends JpaRepository<ClearingBatch, UUID> {
    Optional<ClearingBatch> findByBatchDate(LocalDate batchDate);
    boolean existsByBatchDate(LocalDate batchDate);
    Optional<ClearingBatch> findTopByStatusOrderByBatchDateDesc(ClearingBatch.Status status);
}
