package com.network.fraud;

import com.network.domain.FraudAlert;
import com.network.domain.FraudRuleConfig;
import com.network.domain.Transaction;
import com.network.fraud.rule.FraudRule;
import com.network.iso8583.IsoMessage;
import com.network.repository.FraudAlertRepository;
import com.network.repository.FraudRuleConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates all fraud detection rules against an incoming ISO 8583 message.
 *
 * Rules are loaded from the database and cached (refreshed every 60 seconds).
 * Score aggregation:
 *   - Each triggered rule contributes its score_weight to the total
 *   - Score is capped at 100
 *   - score >= 80  → DECLINE
 *   - score >= 50  → FLAG
 *   - score <  50  → APPROVE
 */
@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    private static final int DECLINE_THRESHOLD = 80;
    private static final int FLAG_THRESHOLD    = 50;

    private final FraudRuleConfigRepository ruleConfigRepository;
    private final FraudAlertRepository      alertRepository;
    private final Map<FraudRuleConfig.RuleType, FraudRule> rulesByType;

    private volatile List<FraudRuleConfig> cachedRules = List.of();

    public FraudDetectionService(FraudRuleConfigRepository ruleConfigRepository,
                                 FraudAlertRepository alertRepository,
                                 List<FraudRule> rules) {
        this.ruleConfigRepository = ruleConfigRepository;
        this.alertRepository      = alertRepository;
        this.rulesByType = rules.stream().collect(
                Collectors.toMap(FraudRule::supportedType, Function.identity()));
    }

    /** Force fraud rules to reload from DB on next evaluate() call (used by TestLabService). */
    public void refreshCache() {
        cachedRules = List.of();
    }

    /** Refresh rule configs from DB every 60 seconds */
    @Scheduled(fixedDelay = 60_000)
    public void refreshRules() {
        cachedRules = ruleConfigRepository.findByEnabledTrue();
        log.debug("Fraud rules refreshed: {} active rules", cachedRules.size());
    }

    public FraudResult evaluate(IsoMessage msg) {
        List<FraudRuleConfig> rules = cachedRules;
        if (rules.isEmpty()) {
            // First call before scheduler runs — load synchronously
            rules = ruleConfigRepository.findByEnabledTrue();
            cachedRules = rules;
        }

        int totalScore = 0;
        List<String> triggeredNames = new ArrayList<>();

        for (FraudRuleConfig config : rules) {
            FraudRule rule = rulesByType.get(config.getRuleType());
            if (rule == null) continue;

            boolean triggered = rule.evaluate(msg, config);
            if (triggered) {
                triggeredNames.add(config.getName());
                // DECLINE-action rules that trigger force a DECLINE regardless of score
                if (config.getAction() == FraudRuleConfig.Action.DECLINE) {
                    totalScore = Math.max(totalScore, DECLINE_THRESHOLD);
                }
                totalScore = Math.min(100, totalScore + config.getScoreWeight());
            }
        }

        FraudAction action;
        if (totalScore >= DECLINE_THRESHOLD) {
            action = FraudAction.DECLINE;
        } else if (totalScore >= FLAG_THRESHOLD) {
            action = FraudAction.FLAG;
        } else {
            action = FraudAction.APPROVE;
        }

        if (!triggeredNames.isEmpty()) {
            log.info("Fraud evaluation: score={} action={} rules={}", totalScore, action, triggeredNames);
        }

        return new FraudResult(totalScore, action, triggeredNames);
    }

    @Transactional
    public void createAlert(Transaction txn, FraudResult result) {
        FraudAlert alert = new FraudAlert();
        alert.setTransaction(txn);
        alert.setFraudScore(result.score());
        alert.setActionTaken(result.action().name());
        alert.setTriggeredRules(result.triggeredRulesAsString());
        alertRepository.save(alert);
        log.info("Fraud alert created: txnId={} score={} action={}", txn.getId(), result.score(), result.action());
    }
}
