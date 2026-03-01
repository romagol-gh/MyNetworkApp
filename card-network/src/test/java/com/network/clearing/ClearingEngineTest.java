package com.network.clearing;

import com.network.domain.ClearingBatch;
import com.network.domain.Transaction;
import com.network.repository.ClearingBatchRepository;
import com.network.repository.ClearingRecordRepository;
import com.network.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
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

    private final ClearingEngine engine = new ClearingEngine(txnRepo, batchRepo, recRepo);

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
