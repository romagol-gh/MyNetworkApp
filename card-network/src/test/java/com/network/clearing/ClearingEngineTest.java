package com.network.clearing;

import com.network.domain.ClearingBatch;
import com.network.domain.ClearingRecord;
import com.network.domain.Transaction;
import com.network.repository.ClearingBatchRepository;
import com.network.repository.ClearingRecordRepository;
import com.network.repository.TransactionRepository;
import com.network.settlement.InterchangeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClearingEngineTest {

    private final TransactionRepository   txnRepo    = Mockito.mock(TransactionRepository.class);
    private final ClearingBatchRepository batchRepo  = Mockito.mock(ClearingBatchRepository.class);
    private final ClearingRecordRepository recRepo   = Mockito.mock(ClearingRecordRepository.class);
    private final InterchangeService      feeService = Mockito.mock(InterchangeService.class);

    private final ClearingEngine engine = new ClearingEngine(txnRepo, batchRepo, recRepo, feeService);

    @Test
    void runClearing_createsCompleteeBatch_forApprovedTransactions() {
        LocalDate date = LocalDate.of(2026, 2, 28);

        Transaction txn = new Transaction();
        txn.setStan("000001");
        txn.setAmount(10000L);
        txn.setCurrency("USD");
        txn.setStatus(Transaction.Status.APPROVED);

        when(batchRepo.existsByBatchDate(date)).thenReturn(false);
        when(txnRepo.findApprovedUncleared()).thenReturn(List.of(txn));
        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClearingBatch batch = engine.runClearing(date);

        assertThat(batch).isNotNull();
        assertThat(batch.getStatus()).isEqualTo(ClearingBatch.Status.COMPLETE);
        assertThat(batch.getRecordCount()).isEqualTo(1);
        assertThat(batch.getTotalAmount()).isEqualTo(10000L);
        verify(recRepo, times(1)).save(any());
    }

    @Test
    void runClearing_returnsNull_whenNothingToProcess() {
        LocalDate date = LocalDate.of(2026, 2, 28);
        when(batchRepo.existsByBatchDate(date)).thenReturn(false);
        when(txnRepo.findApprovedUncleared()).thenReturn(List.of());

        ClearingBatch batch = engine.runClearing(date);
        assertThat(batch).isNull();
    }

    // ── Fee calculation tests ──────────────────────────────────────────────────

    @Test
    void runClearing_setsInterchangeAndNetworkFeesOnRecord() {
        LocalDate date = LocalDate.of(2026, 3, 10);
        Transaction txn = new Transaction();
        txn.setStan("FEE001");
        txn.setAmount(10000L);
        txn.setCurrency("USD");
        txn.setMcc("5411");
        txn.setStatus(Transaction.Status.APPROVED);

        when(batchRepo.existsByBatchDate(date)).thenReturn(false);
        when(txnRepo.findApprovedUncleared()).thenReturn(List.of(txn));
        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(feeService.calculateInterchangeFee(10000L, "5411")).thenReturn(128L);
        when(feeService.calculateNetworkFee(10000L, "5411")).thenReturn(7L);

        ArgumentCaptor<ClearingRecord> captor = ArgumentCaptor.forClass(ClearingRecord.class);
        engine.runClearing(date);

        verify(recRepo).save(captor.capture());
        ClearingRecord saved = captor.getValue();
        assertThat(saved.getInterchangeFee()).isEqualTo(128L);
        assertThat(saved.getNetworkFee()).isEqualTo(7L);
    }

    @Test
    void runClearing_setsTotalFeesOnBatch() {
        LocalDate date = LocalDate.of(2026, 3, 11);

        Transaction txn1 = new Transaction();
        txn1.setStan("FEE002");
        txn1.setAmount(10000L);
        txn1.setCurrency("USD");
        txn1.setMcc("5411");
        txn1.setStatus(Transaction.Status.APPROVED);

        Transaction txn2 = new Transaction();
        txn2.setStan("FEE003");
        txn2.setAmount(5000L);
        txn2.setCurrency("USD");
        txn2.setMcc("7995");
        txn2.setStatus(Transaction.Status.APPROVED);

        when(batchRepo.existsByBatchDate(date)).thenReturn(false);
        when(txnRepo.findApprovedUncleared()).thenReturn(List.of(txn1, txn2));
        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(feeService.calculateInterchangeFee(10000L, "5411")).thenReturn(128L);
        when(feeService.calculateNetworkFee(10000L, "5411")).thenReturn(7L);
        when(feeService.calculateInterchangeFee(5000L, "7995")).thenReturn(140L);
        when(feeService.calculateNetworkFee(5000L, "7995")).thenReturn(5L);

        ArgumentCaptor<ClearingBatch> batchCaptor = ArgumentCaptor.forClass(ClearingBatch.class);
        engine.runClearing(date);

        // batchRepo.save is called twice: once for PROCESSING, once for COMPLETE
        verify(batchRepo, times(2)).save(batchCaptor.capture());
        ClearingBatch completedBatch = batchCaptor.getAllValues().get(1);
        assertThat(completedBatch.getTotalFees()).isEqualTo(128 + 7 + 140 + 5); // 280
    }

    @Test
    void runClearing_passesTransactionMccToFeeService() {
        LocalDate date = LocalDate.of(2026, 3, 12);
        Transaction txn = new Transaction();
        txn.setStan("FEE004");
        txn.setAmount(10000L);
        txn.setCurrency("USD");
        txn.setMcc("7995");
        txn.setStatus(Transaction.Status.APPROVED);

        when(batchRepo.existsByBatchDate(date)).thenReturn(false);
        when(txnRepo.findApprovedUncleared()).thenReturn(List.of(txn));
        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(feeService.calculateInterchangeFee(10000L, "7995")).thenReturn(265L);
        when(feeService.calculateNetworkFee(10000L, "7995")).thenReturn(7L);

        engine.runClearing(date);

        verify(feeService).calculateInterchangeFee(10000L, "7995");
        verify(feeService).calculateNetworkFee(10000L, "7995");
    }

    @Test
    void runClearing_usesDefaultMcc_whenTransactionMccIsNull() {
        LocalDate date = LocalDate.of(2026, 3, 13);
        Transaction txn = new Transaction();
        txn.setStan("FEE005");
        txn.setAmount(10000L);
        txn.setCurrency("USD");
        // mcc intentionally null
        txn.setStatus(Transaction.Status.APPROVED);

        when(batchRepo.existsByBatchDate(date)).thenReturn(false);
        when(txnRepo.findApprovedUncleared()).thenReturn(List.of(txn));
        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(recRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(txnRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(feeService.calculateInterchangeFee(10000L, "DEFAULT")).thenReturn(160L);
        when(feeService.calculateNetworkFee(10000L, "DEFAULT")).thenReturn(7L);

        engine.runClearing(date);

        verify(feeService).calculateInterchangeFee(10000L, "DEFAULT");
        verify(feeService).calculateNetworkFee(10000L, "DEFAULT");
    }

    // ── Existing tests ─────────────────────────────────────────────────────────

    @Test
    void runClearing_skips_whenAlreadyRun() {
        LocalDate date = LocalDate.of(2026, 2, 28);
        ClearingBatch existing = new ClearingBatch();
        existing.setBatchDate(date);
        existing.setStatus(ClearingBatch.Status.COMPLETE);

        when(batchRepo.existsByBatchDate(date)).thenReturn(true);
        when(batchRepo.findByBatchDate(date)).thenReturn(Optional.of(existing));

        ClearingBatch result = engine.runClearing(date);
        assertThat(result.getStatus()).isEqualTo(ClearingBatch.Status.COMPLETE);
        verify(txnRepo, never()).findApprovedUncleared();
    }
}
