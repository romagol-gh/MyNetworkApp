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
 * Flags/declines when a card has too many consecutive declines within a window.
 * This pattern is typical of card testing attacks.
 *
 * Parameters (JSON): {"max_declines": 3, "window_minutes": 60}
 */
@Component
public class DeclineVelocityRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(DeclineVelocityRule.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransactionRepository transactionRepository;

    public DeclineVelocityRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public FraudRuleConfig.RuleType supportedType() { return FraudRuleConfig.RuleType.DECLINE_VELOCITY; }

    @Override
    public boolean evaluate(IsoMessage msg, FraudRuleConfig config) {
        String pan = msg.getPan();
        if (pan == null) return false;

        try {
            JsonNode params   = MAPPER.readTree(config.getParameters());
            int maxDeclines   = params.path("max_declines").asInt(3);
            int windowMinutes = params.path("window_minutes").asInt(60);

            Instant since = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
            String panMasked = TransactionLogger.maskPan(pan);
            long declines = transactionRepository.countRecentDeclinesByPanPrefix(panMasked, since);

            if (declines >= maxDeclines) {
                log.debug("DeclineVelocityRule triggered: declines={} max={}", declines, maxDeclines);
                return true;
            }
        } catch (Exception e) {
            log.error("DeclineVelocityRule evaluation error: {}", e.getMessage());
        }
        return false;
    }
}
