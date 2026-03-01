package com.network.repository;

import com.network.domain.SettlementRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SettlementRecordRepository extends JpaRepository<SettlementRecord, UUID> {
    List<SettlementRecord> findBySettlementDate(LocalDate date);
    List<SettlementRecord> findByParticipantId(UUID participantId);
}
