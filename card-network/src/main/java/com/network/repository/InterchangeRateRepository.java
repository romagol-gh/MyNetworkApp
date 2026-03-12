package com.network.repository;

import com.network.domain.InterchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterchangeRateRepository extends JpaRepository<InterchangeRate, UUID> {

    List<InterchangeRate> findByEnabledTrueOrderByPriorityDesc();

    Optional<InterchangeRate> findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc(
            String mccPattern, String feeCategory);
}
