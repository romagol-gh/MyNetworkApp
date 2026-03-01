package com.network.repository;

import com.network.domain.BlacklistedCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BlacklistedCardRepository extends JpaRepository<BlacklistedCard, UUID> {

    Optional<BlacklistedCard> findByPanHash(String panHash);

    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN TRUE ELSE FALSE END FROM BlacklistedCard b " +
           "WHERE b.panHash = :hash AND (b.expiresAt IS NULL OR b.expiresAt > :now)")
    boolean isBlacklisted(@Param("hash") String hash, @Param("now") Instant now);
}
