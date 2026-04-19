package com.network.fraud.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.domain.FraudRuleConfig;
import com.network.iso8583.Field;
import com.network.iso8583.IsoMessage;
import com.network.transaction.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Flags transactions where the agent chain (DE48 CHN field) exceeds a configured
 * maximum depth, indicating potentially anomalous multi-agent delegation.
 *
 * Chain depth is measured by counting the number of ">" separators in the CHN value.
 * Example: "AGENT001>AGENT002>AGENT003" has depth 3 (3 agents = 2 separators + 1).
 *
 * Parameters (JSON): {"maxChainDepth": 3}
 */
@Component
public class AgentChainAnomalyRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(AgentChainAnomalyRule.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public FraudRuleConfig.RuleType supportedType() { return FraudRuleConfig.RuleType.AGENT_CHAIN; }

    @Override
    public boolean evaluate(IsoMessage msg, FraudRuleConfig config) {
        String de48 = msg.get(Field.ADDITIONAL_DATA);
        if (de48 == null || de48.isBlank()) return false;

        AgentContext ctx = AgentContext.parse(de48);
        if (ctx == null || ctx.getAgentChain() == null) return false;

        try {
            JsonNode params  = MAPPER.readTree(config.getParameters());
            int maxDepth     = params.path("maxChainDepth").asInt(3);

            // Count agents in chain: "A>B>C" has 3 agents (2 separators + 1)
            String chain = ctx.getAgentChain();
            int depth = chain.split(">", -1).length;

            if (depth > maxDepth) {
                log.debug("AgentChainAnomalyRule triggered: agentId={} chain='{}' depth={} max={}",
                        ctx.getAgentId(), chain, depth, maxDepth);
                return true;
            }
        } catch (Exception e) {
            log.error("AgentChainAnomalyRule evaluation error: {}", e.getMessage());
        }
        return false;
    }
}
