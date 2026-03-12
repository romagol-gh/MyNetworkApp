package com.network.gateway;

import com.network.clearing.ClearingEngine;
import com.network.domain.ClearingBatch;
import com.network.domain.ClearingRecord;
import com.network.domain.SettlementRecord;
import com.network.repository.ClearingRecordRepository;
import com.network.repository.SettlementRecordRepository;
import com.network.settlement.SettlementEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the interchange fee module.
 *
 * Validates end-to-end fee calculation against a real PostgreSQL instance
 * with the V10 Flyway migration applied (7 seeded interchange rates).
 * Tests run against a fresh Testcontainers database with no gateway interaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FeesIntegrationTest {

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
        registry.add("gateway.port", () -> "18588");
    }

    @Autowired JdbcTemplate            jdbc;
    @Autowired ClearingEngine          clearingEngine;
    @Autowired SettlementEngine        settlementEngine;
    @Autowired ClearingRecordRepository clearingRecordRepository;
    @Autowired SettlementRecordRepository settlementRecordRepository;

    // ── Flyway seed verification ──────────────────────────────────────────────

    @Test
    void flyway_seedsSevenInterchangeRates() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM interchange_rates", Long.class);
        assertThat(count).isEqualTo(7);
    }

    @Test
    void flyway_seedsInterchangeAndNetworkCategories() {
        Long interchange = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interchange_rates WHERE fee_category = 'INTERCHANGE'", Long.class);
        Long network = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interchange_rates WHERE fee_category = 'NETWORK'", Long.class);

        assertThat(interchange).isEqualTo(5); // DEFAULT + 3 high-risk + 1 retail
        assertThat(network).isEqualTo(2);     // acquirer + issuer network fees
    }

    // ── ClearingEngine fee calculation ────────────────────────────────────────

    @Test
    void clearingEngine_appliesSeededRatesToClearingRecords() {
        // Seed participants
        UUID acquirerId = UUID.randomUUID();
        UUID issuerId   = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO participants (id, name, code, type, status, created_at)
                VALUES (?, 'Clearing Test Acquirer', '110000001', 'ACQUIRER', 'ACTIVE', NOW())
                """, acquirerId);
        jdbc.update("""
                INSERT INTO participants (id, name, code, type, status, created_at)
                VALUES (?, 'Clearing Test Issuer', '110000002', 'ISSUER', 'ACTIVE', NOW())
                """, issuerId);

        // Seed one approved transaction with MCC=5411 (retail rate: 120 bps + $0.08 flat)
        UUID txnId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions
                  (id, stan, message_type, status, amount, currency, mcc,
                   acquirer_id, issuer_id, fraud_flagged, transmitted_at)
                VALUES (?, 'CL0001', '0100', 'APPROVED', 10000, 'USD', '5411', ?, ?, false, NOW())
                """, txnId, acquirerId, issuerId);

        ClearingBatch batch = clearingEngine.runClearing(LocalDate.of(2025, 6, 1));

        assertThat(batch).isNotNull();
        assertThat(batch.getStatus()).isEqualTo(ClearingBatch.Status.COMPLETE);

        List<ClearingRecord> records = clearingRecordRepository.findByBatchId(batch.getId());
        assertThat(records).hasSize(1);

        ClearingRecord record = records.get(0);
        // MCC 5411 → retail rate: 120 bps + 8 flat on 10000
        // interchange = round(10000 * 120 / 10000.0) + 8 = 120 + 8 = 128
        assertThat(record.getInterchangeFee()).isEqualTo(128);
        // Network DEFAULT: 5 bps + 2 flat on 10000
        // network = round(10000 * 5 / 10000.0) + 2 = 5 + 2 = 7
        assertThat(record.getNetworkFee()).isEqualTo(7);
        // batch total = interchange + network = 135
        assertThat(batch.getTotalFees()).isEqualTo(135);
    }

    @Test
    void clearingEngine_usesHighRiskRateForMcc7995() {
        UUID acquirerId = UUID.randomUUID();
        UUID issuerId   = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO participants (id, name, code, type, status, created_at)
                VALUES (?, 'HR Acquirer', '120000001', 'ACQUIRER', 'ACTIVE', NOW())
                """, acquirerId);
        jdbc.update("""
                INSERT INTO participants (id, name, code, type, status, created_at)
                VALUES (?, 'HR Issuer', '120000002', 'ISSUER', 'ACTIVE', NOW())
                """, issuerId);

        UUID txnId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions
                  (id, stan, message_type, status, amount, currency, mcc,
                   acquirer_id, issuer_id, fraud_flagged, transmitted_at)
                VALUES (?, 'HR0001', '0100', 'APPROVED', 10000, 'USD', '7995', ?, ?, false, NOW())
                """, txnId, acquirerId, issuerId);

        ClearingBatch batch = clearingEngine.runClearing(LocalDate.of(2025, 6, 2));

        List<ClearingRecord> records = clearingRecordRepository.findByBatchId(batch.getId());
        ClearingRecord record = records.get(0);

        // MCC 7995 → high-risk rate: 250 bps + 15 flat on 10000
        // interchange = round(10000 * 250 / 10000.0) + 15 = 250 + 15 = 265
        assertThat(record.getInterchangeFee()).isEqualTo(265);
        assertThat(record.getNetworkFee()).isEqualTo(7); // network DEFAULT unchanged
    }

    // ── SettlementEngine fee aggregation ─────────────────────────────────────

    @Test
    void settlementEngine_netPositionReflectsInterchangeAndNetworkFees() {
        // Seed participants
        UUID acquirerId = UUID.randomUUID();
        UUID issuerId   = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO participants (id, name, code, type, status, created_at)
                VALUES (?, 'Settlement Test Acquirer', '130000001', 'ACQUIRER', 'ACTIVE', NOW())
                """, acquirerId);
        jdbc.update("""
                INSERT INTO participants (id, name, code, type, status, created_at)
                VALUES (?, 'Settlement Test Issuer', '130000002', 'ISSUER', 'ACTIVE', NOW())
                """, issuerId);

        // Seed one approved transaction: $100, MCC=5411
        UUID txnId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions
                  (id, stan, message_type, status, amount, currency, mcc,
                   acquirer_id, issuer_id, fraud_flagged, transmitted_at)
                VALUES (?, 'SE0001', '0100', 'APPROVED', 10000, 'USD', '5411', ?, ?, false, NOW())
                """, txnId, acquirerId, issuerId);

        // Run clearing then settlement
        ClearingBatch batch = clearingEngine.runClearing(LocalDate.of(2025, 6, 3));
        assertThat(batch).isNotNull();

        List<SettlementRecord> records = settlementEngine.settle(batch);
        assertThat(records).hasSize(2);

        SettlementRecord acqRecord = records.stream()
                .filter(r -> r.getParticipant().getId().equals(acquirerId))
                .findFirst().orElseThrow();
        SettlementRecord issRecord = records.stream()
                .filter(r -> r.getParticipant().getId().equals(issuerId))
                .findFirst().orElseThrow();

        // Acquirer: paid $100 + $1.28 interchange + $0.07 network = net -$101.35
        assertThat(acqRecord.getDebitTotal()).isEqualTo(10000);
        assertThat(acqRecord.getInterchangeFeesPaid()).isEqualTo(128);
        assertThat(acqRecord.getNetworkFeesPaid()).isEqualTo(7);
        assertThat(acqRecord.getNetPosition()).isEqualTo(-10135); // 0-10000+0-128-7

        // Issuer: received $100 + $1.28 interchange - $0.07 network = net +$101.21
        assertThat(issRecord.getCreditTotal()).isEqualTo(10000);
        assertThat(issRecord.getInterchangeFeesReceived()).isEqualTo(128);
        assertThat(issRecord.getNetworkFeesPaid()).isEqualTo(7);
        assertThat(issRecord.getNetPosition()).isEqualTo(10121); // 10000-0+128-0-7
    }
}
