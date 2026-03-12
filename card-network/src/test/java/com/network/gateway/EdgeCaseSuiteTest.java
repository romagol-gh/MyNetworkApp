package com.network.gateway;

import com.network.domain.TestRun;
import com.network.transaction.TestLabService;
import com.network.transaction.TestScenarioConfig;
import com.network.transaction.TestScenarioType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive edge-case test suite using a dedicated Testcontainers PostgreSQL instance.
 *
 * Each test method exercises one of the 12 Test Lab scenarios against a real Spring Boot
 * application and real Netty TCP gateway running on port 18586.
 *
 * Run:
 *   DOCKER_HOST=unix:///Users/romagol/.colima/default/docker.sock \
 *   TESTCONTAINERS_RYUK_DISABLED=true \
 *   mvn test -Dtest=EdgeCaseSuiteTest -DfailIfNoTests=false \
 *            -Dtestcontainers.ryuk.disabled=true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EdgeCaseSuiteTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("cardnetwork")
            .withUsername("card")
            .withPassword("card");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("gateway.port", () -> "18586");
    }

    @Autowired
    TestLabService testLabService;

    // ── Scenario tests ───────────────────────────────────────────────────────

    @Test
    void scenario01_happyPath() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.HAPPY_PATH, TestScenarioConfig.defaultConfig());
        assertThat(r.getTotalTxns()).isEqualTo(1);
        assertThat(r.getPassedTxns()).isEqualTo(1);
        assertThat(r.getFailedTxns()).isEqualTo(0);
    }

    @Test
    void scenario02_issuerDecline() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.ISSUER_DECLINE, TestScenarioConfig.defaultConfig());
        assertThat(r.getTotalTxns()).isEqualTo(1);
        assertThat(r.getPassedTxns()).isEqualTo(1); // passed means RC=05 as expected
        assertThat(r.getFailedTxns()).isEqualTo(0);
    }

    @Test
    void scenario03_binNotFound() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.BIN_NOT_FOUND, TestScenarioConfig.defaultConfig());
        assertThat(r.getTotalTxns()).isEqualTo(1);
        assertThat(r.getPassedTxns()).isEqualTo(1); // passed means RC=15 as expected
        assertThat(r.getFailedTxns()).isEqualTo(0);
    }

    @Test
    void scenario04_blacklistedCard() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.BLACKLISTED_CARD, TestScenarioConfig.defaultConfig());
        assertThat(r.getTotalTxns()).isEqualTo(1);
        assertThat(r.getPassedTxns()).isEqualTo(1); // passed means RC=59 as expected
        assertThat(r.getFailedTxns()).isEqualTo(0);
        assertThat(r.getFraudDeclined()).isEqualTo(1);
    }

    @Test
    void scenario05_highRiskMcc() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.HIGH_RISK_MCC,
                new TestScenarioConfig(1, 10_000L, "7995", Map.of()));
        assertThat(r.getTotalTxns()).isEqualTo(1);
        assertThat(r.getPassedTxns()).isEqualTo(1); // approved despite fraud FLAG
        assertThat(r.getFailedTxns()).isEqualTo(0);
    }

    @Test
    void scenario06_largeAmount() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.LARGE_AMOUNT,
                new TestScenarioConfig(1, 600_000L, "5411", Map.of())); // $6000 > $5000 threshold
        assertThat(r.getTotalTxns()).isEqualTo(1);
        assertThat(r.getPassedTxns()).isEqualTo(1); // approved despite fraud FLAG
        assertThat(r.getFailedTxns()).isEqualTo(0);
    }

    @Test
    void scenario07_velocityBreach() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.VELOCITY_BREACH,
                new TestScenarioConfig(7, 10_000L, "5411", Map.of()));
        assertThat(r.getTotalTxns()).isEqualTo(7);
        assertThat(r.getPassedTxns()).isEqualTo(7); // all approved (velocity FLAGs, doesn't block)
        assertThat(r.getFailedTxns()).isEqualTo(0);
    }

    @Test
    void scenario08_declineVelocity() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.DECLINE_VELOCITY,
                new TestScenarioConfig(4, 10_000L, "5411", Map.of()));
        assertThat(r.getTotalTxns()).isEqualTo(4);
        assertThat(r.getPassedTxns()).isEqualTo(4); // 3×RC=05 + 1×RC=59 all match expected
        assertThat(r.getFailedTxns()).isEqualTo(0);
        assertThat(r.getFraudDeclined()).isEqualTo(1); // 4th blocked by fraud
    }

    @Test
    void scenario09_echoTest() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.ECHO_TEST, TestScenarioConfig.defaultConfig());
        assertThat(r.getTotalTxns()).isEqualTo(1);
        assertThat(r.getPassedTxns()).isEqualTo(1);
        assertThat(r.getFailedTxns()).isEqualTo(0);
    }

    @Test
    void scenario10_reversal() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.REVERSAL,
                new TestScenarioConfig(2, 10_000L, "5411", Map.of()));
        assertThat(r.getTotalTxns()).isEqualTo(2); // auth + reversal
        assertThat(r.getPassedTxns()).isEqualTo(2);
        assertThat(r.getFailedTxns()).isEqualTo(0);
    }

    @Test
    void scenario11_volumeLoad_200Transactions() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.VOLUME_LOAD,
                new TestScenarioConfig(200, 10_000L, "5411", Map.of()));
        assertThat(r.getTotalTxns()).isEqualTo(200);
        assertThat(r.getPassedTxns()).isEqualTo(200);
        assertThat(r.getFailedTxns()).isEqualTo(0);
    }

    @Test
    void scenario12_mixedBatch() throws Exception {
        TestRun r = testLabService.run(TestScenarioType.MIXED_BATCH,
                new TestScenarioConfig(8, 10_000L, "5411", Map.of()));
        assertThat(r.getTotalTxns()).isEqualTo(8);
        assertThat(r.getPassedTxns()).isEqualTo(8); // all 8 match their expected RCs
        assertThat(r.getFailedTxns()).isEqualTo(0);
        assertThat(r.getFraudDeclined()).isGreaterThanOrEqualTo(1); // at least the blacklisted txn
    }
}
