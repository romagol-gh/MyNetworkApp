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
 * Flags transactions where the agent's DE48 SCP (scope) field is populated but
 * the transaction MCC is not within the declared scope — belt-and-suspenders
 * check complementing the hard spend control in AgentRegistrationService.
 *
 * Parameters (JSON): {"enforceStrict": true}
 */
@Component
public class AgentScopeViolationRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeViolationRule.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public FraudRuleConfig.RuleType supportedType() { return FraudRuleConfig.RuleType.AGENT_SCOPE; }

    @Override
    public boolean evaluate(IsoMessage msg, FraudRuleConfig config) {
        String de48 = msg.get(Field.ADDITIONAL_DATA);
        if (de48 == null || de48.isBlank()) return false;

        AgentContext ctx = AgentContext.parse(de48);
        if (ctx == null || ctx.getMccScope().isEmpty()) return false;

        String mcc = msg.getMcc();
        if (mcc == null) return false;

        try {
            JsonNode params   = MAPPER.readTree(config.getParameters());
            boolean strict    = params.path("enforceStrict").asBoolean(true);
            if (!strict) return false;

            boolean permitted = ctx.getMccScope().contains(mcc);
            if (!permitted) {
                log.debug("AgentScopeViolationRule triggered: agentId={} mcc={} scope={}",
                        ctx.getAgentId(), mcc, ctx.getMccScope());
                return true;
            }
        } catch (Exception e) {
            log.error("AgentScopeViolationRule evaluation error: {}", e.getMessage());
        }
        return false;
    }
}
