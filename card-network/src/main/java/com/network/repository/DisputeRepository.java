package com.network.repository;

import com.network.domain.Dispute;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {
    List<Dispute> findByTransactionId(UUID transactionId);
    Page<Dispute> findByStatus(Dispute.Status status, Pageable pageable);
    long countByStatusIn(List<Dispute.Status> statuses);
}
