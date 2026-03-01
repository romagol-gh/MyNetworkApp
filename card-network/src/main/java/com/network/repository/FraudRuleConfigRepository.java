package com.network.repository;

import com.network.domain.FraudRuleConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface FraudRuleConfigRepository extends JpaRepository<FraudRuleConfig, UUID> {
    List<FraudRuleConfig> findByEnabledTrue();
    List<FraudRuleConfig> findByRuleType(FraudRuleConfig.RuleType ruleType);
}
