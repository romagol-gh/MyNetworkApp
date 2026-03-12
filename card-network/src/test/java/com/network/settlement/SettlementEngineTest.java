package com.network.settlement;

import com.network.domain.ClearingBatch;
import com.network.domain.ClearingRecord;
import com.network.domain.Participant;
import com.network.domain.SettlementRecord;
import com.network.repository.ClearingRecordRepository;
import com.network.repository.SettlementRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SettlementEngineTest {

    private final ClearingRecordRepository  clearingRepo   = Mockito.mock(ClearingRecordRepository.class);
    private final SettlementRecordRepository settlementRepo = Mockito.mock(SettlementRecordRepository.class);
    private final SettlementEngine          engine         = new SettlementEngine(clearingRepo, settlementRepo);

    private static Participant participant(UUID id, Participant.Type type) {
        Participant p = Mockito.mock(Participant.class);
        when(p.getId()).thenReturn(id);
        when(p.getType()).thenReturn(type);
        when(p.getName()).thenReturn(type.name());
        return p;
    }

    private static ClearingRecord record(Participant acquirer, Participant issuer,
                                         long amount, long iFee, long nFee) {
        ClearingRecord r = new ClearingRecord();
        r.setAcquirer(acquirer);
        r.setIssuer(issuer);
        r.setAmount(amount);
        r.setInterchangeFee(iFee);
        r.setNetworkFee(nFee);
        r.setCurrency("USD");
        return r;
    }

    private static ClearingBatch batch() {
        ClearingBatch b = new ClearingBatch();
        b.setBatchDate(LocalDate.of(2026, 3, 1));
        return b;
    }

    // ── Acquirer-side assertions ──────────────────────────────────────────────

    @Test
    void settle_acquirerPaysInterchangeAndNetworkFees() {
        UUID acqId = UUID.randomUUID();
        UUID issId = UUID.randomUUID();
        Participant acq = participant(acqId, Participant.Type.ACQUIRER);
        Participant iss = participant(issId, Participant.Type.ISSUER);

        // $100 txn, $1.28 interchange, $0.07 network
        when(clearingRepo.findByBatchId(any())).thenReturn(List.of(record(acq, iss, 10000, 128, 7)));
        when(settlementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SettlementRecord> results = engine.settle(batch());

        SettlementRecord acqRecord = results.stream()
                .filter(r -> r.getParticipant().getId().equals(acqId))
                .findFirst().orElseThrow();

        assertThat(acqRecord.getDebitTotal()).isEqualTo(10000);
        assertThat(acqRecord.getCreditTotal()).isEqualTo(0);
        assertThat(acqRecord.getInterchangeFeesPaid()).isEqualTo(128);
        assertThat(acqRecord.getInterchangeFeesReceived()).isEqualTo(0);
        assertThat(acqRecord.getNetworkFeesPaid()).isEqualTo(7);
        // netPos = 0 - 10000 + 0 - 128 - 7 = -10135
        assertThat(acqRecord.getNetPosition()).isEqualTo(-10135);
    }

    // ── Issuer-side assertions ────────────────────────────────────────────────

    @Test
    void settle_issuerReceivesInterchangeAndPaysNetworkFee() {
        UUID acqId = UUID.randomUUID();
        UUID issId = UUID.randomUUID();
        Participant acq = participant(acqId, Participant.Type.ACQUIRER);
        Participant iss = participant(issId, Participant.Type.ISSUER);

        when(clearingRepo.findByBatchId(any())).thenReturn(List.of(record(acq, iss, 10000, 128, 7)));
        when(settlementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SettlementRecord> results = engine.settle(batch());

        SettlementRecord issRecord = results.stream()
                .filter(r -> r.getParticipant().getId().equals(issId))
                .findFirst().orElseThrow();

        assertThat(issRecord.getCreditTotal()).isEqualTo(10000);
        assertThat(issRecord.getDebitTotal()).isEqualTo(0);
        assertThat(issRecord.getInterchangeFeesPaid()).isEqualTo(0);
        assertThat(issRecord.getInterchangeFeesReceived()).isEqualTo(128);
        assertThat(issRecord.getNetworkFeesPaid()).isEqualTo(7);
        // netPos = 10000 - 0 + 128 - 0 - 7 = 10121
        assertThat(issRecord.getNetPosition()).isEqualTo(10121);
    }

    // ── Multi-transaction aggregation ─────────────────────────────────────────

    @Test
    void settle_aggregatesFeesAcrossMultipleTransactions() {
        UUID acqId = UUID.randomUUID();
        UUID issId = UUID.randomUUID();
        Participant acq = participant(acqId, Participant.Type.ACQUIRER);
        Participant iss = participant(issId, Participant.Type.ISSUER);

        ClearingRecord rec1 = record(acq, iss, 10000, 128, 7); // $100: iFee=128, nFee=7
        ClearingRecord rec2 = record(acq, iss, 5000, 68, 5);   // $50: iFee=68, nFee=5

        when(clearingRepo.findByBatchId(any())).thenReturn(List.of(rec1, rec2));
        when(settlementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SettlementRecord> results = engine.settle(batch());
        assertThat(results).hasSize(2);

        SettlementRecord acqRecord = results.stream()
                .filter(r -> r.getParticipant().getId().equals(acqId))
                .findFirst().orElseThrow();

        // debit=15000, interchangePaid=196, networkFees=12
        // netPos = 0 - 15000 + 0 - 196 - 12 = -15208
        assertThat(acqRecord.getDebitTotal()).isEqualTo(15000);
        assertThat(acqRecord.getInterchangeFeesPaid()).isEqualTo(196);
        assertThat(acqRecord.getNetworkFeesPaid()).isEqualTo(12);
        assertThat(acqRecord.getNetPosition()).isEqualTo(-15208);

        SettlementRecord issRecord = results.stream()
                .filter(r -> r.getParticipant().getId().equals(issId))
                .findFirst().orElseThrow();

        // credit=15000, interchangeReceived=196, networkFees=12
        // netPos = 15000 - 0 + 196 - 0 - 12 = 15184
        assertThat(issRecord.getCreditTotal()).isEqualTo(15000);
        assertThat(issRecord.getInterchangeFeesReceived()).isEqualTo(196);
        assertThat(issRecord.getNetworkFeesPaid()).isEqualTo(12);
        assertThat(issRecord.getNetPosition()).isEqualTo(15184);
    }

    // ── Zero-fee baseline ─────────────────────────────────────────────────────

    @Test
    void settle_zeroFees_netPositionIsSimpleCreditMinusDebit() {
        UUID acqId = UUID.randomUUID();
        UUID issId = UUID.randomUUID();
        Participant acq = participant(acqId, Participant.Type.ACQUIRER);
        Participant iss = participant(issId, Participant.Type.ISSUER);

        when(clearingRepo.findByBatchId(any())).thenReturn(List.of(record(acq, iss, 10000, 0, 0)));
        when(settlementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SettlementRecord> results = engine.settle(batch());

        SettlementRecord acqRecord = results.stream()
                .filter(r -> r.getParticipant().getId().equals(acqId))
                .findFirst().orElseThrow();
        assertThat(acqRecord.getNetPosition()).isEqualTo(-10000);

        SettlementRecord issRecord = results.stream()
                .filter(r -> r.getParticipant().getId().equals(issId))
                .findFirst().orElseThrow();
        assertThat(issRecord.getNetPosition()).isEqualTo(10000);
    }
}
