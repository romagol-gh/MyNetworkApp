package com.network.fraud.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.domain.FraudRuleConfig;
import com.network.iso8583.IsoMessage;
import com.network.repository.TransactionRepository;
import com.network.transaction.TransactionLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Flags transactions when a card exceeds a maximum number of transactions
 * within a configurable time window.
 *
 * Parameters (JSON): {"max_count": 5, "window_minutes": 60}
 */
@Component
public class VelocityRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(VelocityRule.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransactionRepository transactionRepository;

    public VelocityRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public FraudRuleConfig.RuleType supportedType() { return FraudRuleConfig.RuleType.VELOCITY; }

    @Override
    public boolean evaluate(IsoMessage msg, FraudRuleConfig config) {
        String pan = msg.getPan();
        if (pan == null || pan.length() < 6) return false;

        try {
            JsonNode params    = MAPPER.readTree(config.getParameters());
            int maxCount       = params.path("max_count").asInt(5);
            int windowMinutes  = params.path("window_minutes").asInt(60);

            Instant since = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
            String panPrefix = TransactionLogger.maskPan(pan); // compare by masked PAN
            long count = transactionRepository.countRecentByPanPrefix(panPrefix, since);

            if (count >= maxCount) {
                log.debug("VelocityRule triggered: count={} max={} window={}min", count, maxCount, windowMinutes);
                return true;
            }
        } catch (Exception e) {
            log.error("VelocityRule evaluation error: {}", e.getMessage());
        }
        return false;
    }
}
