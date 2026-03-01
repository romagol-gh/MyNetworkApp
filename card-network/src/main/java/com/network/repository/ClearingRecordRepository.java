package com.network.repository;

import com.network.domain.ClearingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ClearingRecordRepository extends JpaRepository<ClearingRecord, UUID> {
    List<ClearingRecord> findByBatchId(UUID batchId);
}
