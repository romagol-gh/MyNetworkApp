package com.network.repository;

import com.network.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByStanAndAcquirerId(String stan, UUID acquirerId);

    Optional<Transaction> findByRetrievalRef(String retrievalRef);

    Page<Transaction> findAll(Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.panMasked LIKE :panPrefix% AND t.transmittedAt > :since")
    List<Transaction> findRecentByPanPrefix(@Param("panPrefix") String panPrefix, @Param("since") Instant since);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.panMasked LIKE :panPrefix% AND t.transmittedAt > :since")
    long countRecentByPanPrefix(@Param("panPrefix") String panPrefix, @Param("since") Instant since);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.panMasked LIKE :panPrefix% AND t.status = 'DECLINED' AND t.transmittedAt > :since")
    long countRecentDeclinesByPanPrefix(@Param("panPrefix") String panPrefix, @Param("since") Instant since);

    @Query("SELECT t FROM Transaction t WHERE t.status = 'APPROVED' AND t.clearingBatch IS NULL")
    List<Transaction> findApprovedUncleared();

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.transmittedAt >= :from AND t.transmittedAt < :to")
    long countByPeriod(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.status = 'APPROVED' AND t.transmittedAt >= :from AND t.transmittedAt < :to")
    long countApprovedByPeriod(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.status = 'APPROVED' AND t.transmittedAt >= :from AND t.transmittedAt < :to")
    long sumApprovedAmountByPeriod(@Param("from") Instant from, @Param("to") Instant to);
}
