package com.network.api.rest;

import com.network.clearing.ClearingEngine;
import com.network.domain.ClearingBatch;
import com.network.domain.SettlementRecord;
import com.network.repository.ClearingBatchRepository;
import com.network.repository.SettlementRecordRepository;
import com.network.settlement.SettlementEngine;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class SettlementController {

    private final ClearingBatchRepository    batchRepository;
    private final SettlementRecordRepository settlementRepository;
    private final ClearingEngine             clearingEngine;
    private final SettlementEngine           settlementEngine;

    public SettlementController(ClearingBatchRepository batchRepository,
                                SettlementRecordRepository settlementRepository,
                                ClearingEngine clearingEngine,
                                SettlementEngine settlementEngine) {
        this.batchRepository     = batchRepository;
        this.settlementRepository = settlementRepository;
        this.clearingEngine      = clearingEngine;
        this.settlementEngine    = settlementEngine;
    }

    @GetMapping("/clearing/batches")
    public List<ClearingBatch> listBatches() { return batchRepository.findAll(); }

    @PostMapping("/clearing/run")
    @ResponseStatus(HttpStatus.OK)
    public ClearingBatch runClearing(@RequestParam(required = false) String date) {
        LocalDate batchDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        ClearingBatch batch = clearingEngine.runClearing(batchDate);
        if (batch == null) throw new ResponseStatusException(HttpStatus.NO_CONTENT, "No transactions to clear");
        return batch;
    }

    @GetMapping("/settlement")
    public List<SettlementRecord> listSettlement(@RequestParam(required = false) String date) {
        if (date != null) return settlementRepository.findBySettlementDate(LocalDate.parse(date));
        return settlementRepository.findAll();
    }

    @GetMapping("/settlement/{date}/report")
    public void downloadReport(@PathVariable String date, HttpServletResponse response) throws Exception {
        List<SettlementRecord> records = settlementRepository.findBySettlementDate(LocalDate.parse(date));
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=settlement-" + date + ".csv");

        PrintWriter writer = response.getWriter();
        writer.println("participant_id,participant_name,settlement_date,debit_total,credit_total," +
                "interchange_fees_paid,interchange_fees_received,network_fees_paid,net_position");
        for (SettlementRecord r : records) {
            writer.printf("%s,%s,%s,%d,%d,%d,%d,%d,%d%n",
                    r.getParticipant().getId(),
                    r.getParticipant().getName(),
                    r.getSettlementDate(),
                    r.getDebitTotal(),
                    r.getCreditTotal(),
                    r.getInterchangeFeesPaid(),
                    r.getInterchangeFeesReceived(),
                    r.getNetworkFeesPaid(),
                    r.getNetPosition());
        }
    }
}
