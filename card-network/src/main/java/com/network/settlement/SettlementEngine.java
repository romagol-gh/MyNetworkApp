package com.network.settlement;

import com.network.domain.ClearingBatch;
import com.network.domain.ClearingRecord;
import com.network.domain.Participant;
import com.network.domain.SettlementRecord;
import com.network.repository.ClearingRecordRepository;
import com.network.repository.SettlementRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calculates net settlement positions for each participant from a clearing batch.
 *
 * Net position: creditTotal (amounts received from acquirers) − debitTotal (amounts owed to issuers)
 * Positive = participant is owed money from the network
 * Negative = participant owes money to the network
 */
@Service
public class SettlementEngine {

    private static final Logger log = LoggerFactory.getLogger(SettlementEngine.class);

    private final ClearingRecordRepository  clearingRecordRepository;
    private final SettlementRecordRepository settlementRecordRepository;

    public SettlementEngine(ClearingRecordRepository clearingRecordRepository,
                            SettlementRecordRepository settlementRecordRepository) {
        this.clearingRecordRepository   = clearingRecordRepository;
        this.settlementRecordRepository = settlementRecordRepository;
    }

    @Transactional
    public List<SettlementRecord> settle(ClearingBatch batch) {
        List<ClearingRecord> records = clearingRecordRepository.findByBatchId(batch.getId());
        LocalDate settlementDate = batch.getBatchDate();

        // participant UUID → {debit, credit, interchangePaid, interchangeReceived, networkFees}
        Map<UUID, long[]> positions = new HashMap<>();
        Map<UUID, Participant> participantMap = new HashMap<>();

        for (ClearingRecord record : records) {
            Participant acquirer = record.getAcquirer();
            Participant issuer   = record.getIssuer();
            long amount          = record.getAmount();
            long iFee            = record.getInterchangeFee();
            long nFee            = record.getNetworkFee();

            // Acquirer: debits transaction amount, pays interchange + network fees
            long[] acqPos = positions.computeIfAbsent(acquirer.getId(), k -> new long[5]);
            acqPos[0] += amount;   // debit
            acqPos[2] += iFee;     // interchangePaid
            acqPos[4] += nFee;     // networkFees
            participantMap.put(acquirer.getId(), acquirer);

            // Issuer: credits transaction amount, receives interchange, pays network fee
            long[] issPos = positions.computeIfAbsent(issuer.getId(), k -> new long[5]);
            issPos[1] += amount;   // credit
            issPos[3] += iFee;     // interchangeReceived
            issPos[4] += nFee;     // networkFees
            participantMap.put(issuer.getId(), issuer);
        }

        List<SettlementRecord> settlementRecords = positions.entrySet().stream().map(entry -> {
            UUID participantId = entry.getKey();
            long[] pos = entry.getValue();
            long debit               = pos[0];
            long credit              = pos[1];
            long interchangePaid     = pos[2];
            long interchangeReceived = pos[3];
            long networkFees         = pos[4];

            SettlementRecord sr = new SettlementRecord();
            sr.setSettlementDate(settlementDate);
            sr.setParticipant(participantMap.get(participantId));
            sr.setDebitTotal(debit);
            sr.setCreditTotal(credit);
            sr.setInterchangeFeesPaid(interchangePaid);
            sr.setInterchangeFeesReceived(interchangeReceived);
            sr.setNetworkFeesPaid(networkFees);
            sr.setNetPosition(credit - debit + interchangeReceived - interchangePaid - networkFees);
            sr.setBatch(batch);
            return settlementRecordRepository.save(sr);
        }).toList();

        log.info("Settlement complete: date={} participants={}", settlementDate, settlementRecords.size());
        return settlementRecords;
    }
}
