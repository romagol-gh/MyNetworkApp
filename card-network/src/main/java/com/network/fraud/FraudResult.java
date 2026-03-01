package com.network.fraud;

import java.util.List;

/**
 * Immutable result from the fraud detection engine.
 *
 * @param score              Aggregate fraud score 0-100 (higher = more suspicious)
 * @param action             What to do with this transaction
 * @param triggeredRuleNames Names of rules that fired
 */
public record FraudResult(int score, FraudAction action, List<String> triggeredRuleNames) {

    public static FraudResult approve() {
        return new FraudResult(0, FraudAction.APPROVE, List.of());
    }

    public String triggeredRulesAsString() {
        return String.join(", ", triggeredRuleNames);
    }
}
