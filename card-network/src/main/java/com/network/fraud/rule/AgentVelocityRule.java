package com.network.fraud.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.domain.FraudRuleConfig;
import com.network.iso8583.Field;
import com.network.iso8583.IsoMessage;
import com.network.repository.TransactionRepository;
import com.network.transaction.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Flags transactions when an agent exceeds a maximum number of transactions
 * within a configurable time window.
 *
 * Parameters (JSON): {"maxTransactions": 10, "windowMinutes": 5}
 */
@Component
public class AgentVelocityRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(AgentVelocityRule.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransactionRepository transactionRepository;

    public AgentVelocityRule(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public FraudRuleConfig.RuleType supportedType() { return FraudRuleConfig.RuleType.AGENT_VELOCITY; }

    @Override
    public boolean evaluate(IsoMessage msg, FraudRuleConfig config) {
        String de48 = msg.get(Field.ADDITIONAL_DATA);
        if (de48 == null || de48.isBlank()) return false;

        AgentContext ctx = AgentContext.parse(de48);
        if (ctx == null || ctx.getAgentId() == null) return false;

        try {
            JsonNode params   = MAPPER.readTree(config.getParameters());
            int maxTxns       = params.path("maxTransactions").asInt(10);
            int windowMinutes = params.path("windowMinutes").asInt(5);

            Instant since = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
            long count = transactionRepository.countByAgentIdAndTransmittedAtAfter(ctx.getAgentId(), since);

            if (count >= maxTxns) {
                log.debug("AgentVelocityRule triggered: agentId={} count={} max={} window={}min",
                        ctx.getAgentId(), count, maxTxns, windowMinutes);
                return true;
            }
        } catch (Exception e) {
            log.error("AgentVelocityRule evaluation error: {}", e.getMessage());
        }
        return false;
    }
}
