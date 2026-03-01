package com.network.fraud.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.domain.FraudRuleConfig;
import com.network.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Flags transactions whose amount exceeds a configurable threshold.
 *
 * Parameters (JSON): {"threshold_minor_units": 500000, "currency": "USD"}
 * threshold_minor_units is in minor units (e.g. cents): 500000 = $5,000.00
 */
@Component
public class AmountLimitRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(AmountLimitRule.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public FraudRuleConfig.RuleType supportedType() { return FraudRuleConfig.RuleType.AMOUNT_LIMIT; }

    @Override
    public boolean evaluate(IsoMessage msg, FraudRuleConfig config) {
        String amountStr = msg.getAmount();
        if (amountStr == null || amountStr.isBlank()) return false;

        try {
            long amount = Long.parseLong(amountStr.trim());
            JsonNode params = MAPPER.readTree(config.getParameters());
            long threshold  = params.path("threshold_minor_units").asLong(Long.MAX_VALUE);

            if (amount > threshold) {
                log.debug("AmountLimitRule triggered: amount={} threshold={}", amount, threshold);
                return true;
            }
        } catch (Exception e) {
            log.error("AmountLimitRule evaluation error: {}", e.getMessage());
        }
        return false;
    }
}
