package com.network.fraud.rule;

import com.network.domain.FraudRuleConfig;
import com.network.iso8583.IsoMessage;

/**
 * Strategy interface for individual fraud detection rules.
 */
public interface FraudRule {

    /**
     * Evaluate the rule against the incoming ISO 8583 message.
     *
     * @param msg    the incoming authorization/financial request
     * @param config the rule configuration (thresholds, parameters)
     * @return true if this rule is triggered (suspicious)
     */
    boolean evaluate(IsoMessage msg, FraudRuleConfig config);

    /**
     * @return the rule type this implementation handles
     */
    FraudRuleConfig.RuleType supportedType();
}
