package com.network.clearing;

import com.network.domain.ClearingBatch;
import com.network.domain.ClearingRecord;
import com.network.domain.Transaction;
import com.network.repository.ClearingBatchRepository;
import com.network.repository.ClearingRecordRepository;
import com.network.repository.TransactionRepository;
import com.network.settlement.InterchangeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Batches all APPROVED, uncleared transactions into a daily clearing batch.
 */
@Service
public class ClearingEngine {

    private static final Logger log = LoggerFactory.getLogger(ClearingEngine.class);

    private final TransactionRepository   transactionRepository;
    private final ClearingBatchRepository batchRepository;
    private final ClearingRecordRepository recordRepository;
    private final InterchangeService      interchangeService;

    public ClearingEngine(TransactionRepository transactionRepository,
                          ClearingBatchRepository batchRepository,
                          ClearingRecordRepository recordRepository,
                          InterchangeService interchangeService) {
        this.transactionRepository = transactionRepository;
        this.batchRepository       = batchRepository;
        this.recordRepository      = recordRepository;
        this.interchangeService    = interchangeService;
    }

    @Transactional
    public ClearingBatch runClearing(LocalDate batchDate) {
        if (batchRepository.existsByBatchDate(batchDate)) {
            log.warn("Clearing already run for date: {}", batchDate);
            return batchRepository.findByBatchDate(batchDate).orElseThrow();
        }

        List<Transaction> approved = transactionRepository.findApprovedUncleared();
        if (approved.isEmpty()) {
            log.info("No transactions to clear for {}", batchDate);
            return null;
        }

        ClearingBatch batch = new ClearingBatch();
        batch.setBatchDate(batchDate);
        batch.setStatus(ClearingBatch.Status.PROCESSING);
        batch = batchRepository.save(batch);

        long totalAmount = 0;
        long totalFees   = 0;
        for (Transaction txn : approved) {
            ClearingRecord record = new ClearingRecord();
            record.setBatch(batch);
            record.setTransaction(txn);
            record.setAcquirer(txn.getAcquirer());
            record.setIssuer(txn.getIssuer());
            long amount = txn.getAmount() != null ? txn.getAmount() : 0L;
            record.setAmount(amount);
            record.setCurrency(txn.getCurrency() != null ? txn.getCurrency() : "USD");

            String mcc = txn.getMcc() != null ? txn.getMcc() : "DEFAULT";
            long iFee = interchangeService.calculateInterchangeFee(amount, mcc);
            long nFee = interchangeService.calculateNetworkFee(amount, mcc);
            record.setInterchangeFee(iFee);
            record.setNetworkFee(nFee);
            totalFees += iFee + nFee;

            recordRepository.save(record);

            txn.setClearingBatch(batch);
            transactionRepository.save(txn);

            totalAmount += amount;
        }

        batch.setRecordCount(approved.size());
        batch.setTotalAmount(totalAmount);
        batch.setTotalFees(totalFees);
        batch.setStatus(ClearingBatch.Status.COMPLETE);
        batch.setCompletedAt(Instant.now());
        batch = batchRepository.save(batch);

        log.info("Clearing complete: date={} records={} totalAmount={}", batchDate, approved.size(), totalAmount);
        return batch;
    }
}
