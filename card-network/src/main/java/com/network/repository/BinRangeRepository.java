package com.network.repository;

import com.network.domain.BinRange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BinRangeRepository extends JpaRepository<BinRange, UUID> {

    @Query("SELECT b FROM BinRange b JOIN FETCH b.issuer WHERE :pan >= b.low AND :pan <= b.high ORDER BY b.low DESC")
    Optional<BinRange> findByPan(@Param("pan") String pan);

    List<BinRange> findByIssuerId(UUID issuerId);

    boolean existsByLowAndHigh(String low, String high);
}
