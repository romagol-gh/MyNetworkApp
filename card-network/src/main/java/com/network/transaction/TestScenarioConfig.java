package com.network.transaction;

import java.util.Map;

/**
 * Configuration for a single Test Lab scenario run.
 *
 * @param transactionCount  how many transactions to send (scenario may override minimum)
 * @param amountMinorUnits  transaction amount in minor currency units (e.g. 10000 = $100.00)
 * @param mcc               merchant category code (4-digit string)
 * @param fraudRuleOverrides  optional per-run rule overrides: rule name → enabled flag
 */
public record TestScenarioConfig(
        int transactionCount,
        long amountMinorUnits,
        String mcc,
        Map<String, Boolean> fraudRuleOverrides
) {
    public static TestScenarioConfig defaultConfig() {
        return new TestScenarioConfig(1, 10_000L, "5411", Map.of());
    }
}
