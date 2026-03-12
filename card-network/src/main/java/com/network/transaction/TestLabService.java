package com.network.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.domain.FraudRuleConfig;
import com.network.domain.TestRun;
import com.network.fraud.FraudDetectionService;
import com.network.repository.FraudRuleConfigRepository;
import com.network.repository.TestRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates Test Lab scenario execution:
 *   1. Applies fraud rule overrides from the config
 *   2. Runs the scenario via TestScenarioRunner
 *   3. Restores original fraud rule states
 *   4. Persists and returns a TestRun record
 */
@Service
public class TestLabService {

    private static final Logger log = LoggerFactory.getLogger(TestLabService.class);

    private final TestScenarioRunner       runner;
    private final TestRunRepository        testRunRepository;
    private final FraudRuleConfigRepository fraudRuleConfigRepository;
    private final FraudDetectionService    fraudDetectionService;
    private final ObjectMapper             objectMapper;

    public TestLabService(TestScenarioRunner runner,
                          TestRunRepository testRunRepository,
                          FraudRuleConfigRepository fraudRuleConfigRepository,
                          FraudDetectionService fraudDetectionService,
                          ObjectMapper objectMapper) {
        this.runner                    = runner;
        this.testRunRepository         = testRunRepository;
        this.fraudRuleConfigRepository = fraudRuleConfigRepository;
        this.fraudDetectionService     = fraudDetectionService;
        this.objectMapper              = objectMapper;
    }

    /**
     * Run a scenario with the given config.
     * Fraud rule overrides in the config are applied before running and restored afterward.
     */
    public TestRun run(TestScenarioType scenario, TestScenarioConfig config) {
        if (config == null) config = scenario.defaultConfig();

        Instant startedAt = Instant.now();
        Map<UUID, Boolean> savedRuleStates = applyFraudRuleOverrides(config.fraudRuleOverrides());
        if (!savedRuleStates.isEmpty()) {
            fraudDetectionService.refreshCache();
        }

        List<TestTransactionResult> results = List.of();
        String errorMessage = null;

        try {
            results = runner.run(scenario, config);
        } catch (Exception e) {
            log.error("Scenario {} failed: {}", scenario, e.getMessage(), e);
            errorMessage = e.getMessage();
        } finally {
            if (!savedRuleStates.isEmpty()) {
                restoreFraudRuleStates(savedRuleStates);
                fraudDetectionService.refreshCache();
            }
        }

        TestRun run = new TestRun();
        run.setScenario(scenario.name());
        run.setConfigJson(toJson(config));
        run.setStartedAt(startedAt);
        run.setCompletedAt(Instant.now());
        run.setDurationMs(run.getCompletedAt().toEpochMilli() - startedAt.toEpochMilli());

        int total   = results.size();
        int passed  = (int) results.stream().filter(TestTransactionResult::passed).count();
        int failed  = total - passed;
        int fraudDeclined = (int) results.stream()
                .filter(r -> "59".equals(r.responseCode())).count();

        run.setTotalTxns(total);
        run.setPassedTxns(passed);
        run.setFailedTxns(failed);
        run.setFraudDeclined(fraudDeclined);
        run.setDetailJson(errorMessage != null
                ? "{\"error\":\"" + errorMessage.replace("\"", "'") + "\"}"
                : toJson(results));

        return testRunRepository.save(run);
    }

    public List<TestRun> recentRuns() {
        return testRunRepository.findTop50ByOrderByStartedAtDesc();
    }

    // ── Fraud rule override helpers ──────────────────────────────────────────

    private Map<UUID, Boolean> applyFraudRuleOverrides(Map<String, Boolean> overrides) {
        if (overrides == null || overrides.isEmpty()) return Map.of();

        Map<UUID, Boolean> saved = new HashMap<>();
        for (FraudRuleConfig rule : fraudRuleConfigRepository.findAll()) {
            Boolean override = overrides.get(rule.getName());
            if (override != null) {
                saved.put(rule.getId(), rule.isEnabled());
                rule.setEnabled(override);
                fraudRuleConfigRepository.save(rule);
            }
        }
        return saved;
    }

    private void restoreFraudRuleStates(Map<UUID, Boolean> saved) {
        for (Map.Entry<UUID, Boolean> entry : saved.entrySet()) {
            fraudRuleConfigRepository.findById(entry.getKey()).ifPresent(rule -> {
                rule.setEnabled(entry.getValue());
                fraudRuleConfigRepository.save(rule);
            });
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
