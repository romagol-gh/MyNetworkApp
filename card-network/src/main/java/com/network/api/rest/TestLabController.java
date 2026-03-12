package com.network.api.rest;

import com.network.domain.TestRun;
import com.network.repository.TestRunRepository;
import com.network.transaction.TestLabService;
import com.network.transaction.TestScenarioConfig;
import com.network.transaction.TestScenarioType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/test-lab")
public class TestLabController {

    private final TestLabService    testLabService;
    private final TestRunRepository testRunRepository;

    public TestLabController(TestLabService testLabService,
                             TestRunRepository testRunRepository) {
        this.testLabService    = testLabService;
        this.testRunRepository = testRunRepository;
    }

    @GetMapping("/scenarios")
    public List<ScenarioMetadata> scenarios() {
        return Arrays.stream(TestScenarioType.values())
                .map(s -> new ScenarioMetadata(
                        s.name(),
                        s.getDisplayName(),
                        s.getDescription(),
                        s.getBadgeColour(),
                        s.getDefaultCount(),
                        s.getDefaultAmountMinorUnits(),
                        s.getDefaultMcc()))
                .toList();
    }

    @PostMapping("/scenarios/{name}/run")
    public TestRun runScenario(@PathVariable String name,
                               @RequestBody(required = false) TestScenarioConfig config) {
        TestScenarioType type;
        try {
            type = TestScenarioType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown scenario: " + name);
        }
        return testLabService.run(type, config);
    }

    @GetMapping("/runs")
    public List<TestRun> recentRuns() {
        return testLabService.recentRuns();
    }

    @GetMapping("/runs/{id}")
    public TestRun getRun(@PathVariable UUID id) {
        return testRunRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record ScenarioMetadata(
            String name,
            String displayName,
            String description,
            String badgeColour,
            int defaultCount,
            long defaultAmountMinorUnits,
            String defaultMcc
    ) {}
}
