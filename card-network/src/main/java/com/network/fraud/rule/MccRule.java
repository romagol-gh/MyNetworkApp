package com.network.fraud.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.domain.FraudRuleConfig;
import com.network.iso8583.IsoMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Flags transactions from high-risk Merchant Category Codes (MCCs).
 *
 * Default high-risk MCCs:
 *   7995 = Betting/Casino
 *   5933 = Pawnshops
 *   6051 = Quasi-Cash / Cryptocurrency exchanges
 *
 * Parameters (JSON): {"mcc_codes": ["7995", "5933", "6051"]}
 */
@Component
public class MccRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(MccRule.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public FraudRuleConfig.RuleType supportedType() { return FraudRuleConfig.RuleType.MCC; }

    @Override
    public boolean evaluate(IsoMessage msg, FraudRuleConfig config) {
        String mcc = msg.getMcc();
        if (mcc == null || mcc.isBlank()) return false;

        try {
            JsonNode params = MAPPER.readTree(config.getParameters());
            JsonNode codes  = params.path("mcc_codes");
            if (!codes.isArray()) return false;

            Set<String> blocked = new HashSet<>();
            codes.forEach(node -> blocked.add(node.asText()));

            if (blocked.contains(mcc)) {
                log.debug("MccRule triggered: mcc={}", mcc);
                return true;
            }
        } catch (Exception e) {
            log.error("MccRule evaluation error: {}", e.getMessage());
        }
        return false;
    }
}
