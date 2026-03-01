package com.network.api.web;

import com.network.clearing.ClearingEngine;
import com.network.domain.ClearingBatch;
import com.network.repository.ClearingBatchRepository;
import com.network.repository.SettlementRecordRepository;
import com.network.settlement.SettlementEngine;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
public class ClearingWebController {

    private final ClearingBatchRepository    batchRepository;
    private final SettlementRecordRepository settlementRepository;
    private final ClearingEngine             clearingEngine;
    private final SettlementEngine           settlementEngine;

    public ClearingWebController(ClearingBatchRepository batchRepository,
                                 SettlementRecordRepository settlementRepository,
                                 ClearingEngine clearingEngine,
                                 SettlementEngine settlementEngine) {
        this.batchRepository     = batchRepository;
        this.settlementRepository = settlementRepository;
        this.clearingEngine      = clearingEngine;
        this.settlementEngine    = settlementEngine;
    }

    @GetMapping("/clearing")
    public String clearing(Model model) {
        model.addAttribute("batches", batchRepository.findAll());
        return "clearing";
    }

    @PostMapping("/clearing/run")
    public String runClearing(RedirectAttributes ra) {
        ClearingBatch batch = clearingEngine.runClearing(LocalDate.now());
        if (batch != null) {
            settlementEngine.settle(batch);
            ra.addFlashAttribute("success", "Clearing and settlement complete for " + batch.getBatchDate());
        } else {
            ra.addFlashAttribute("warning", "No transactions to clear.");
        }
        return "redirect:/clearing";
    }

    @GetMapping("/settlement")
    public String settlement(Model model,
                             @RequestParam(required = false) String date) {
        List<?> records = date != null
                ? settlementRepository.findBySettlementDate(LocalDate.parse(date))
                : settlementRepository.findAll();
        model.addAttribute("records", records);
        model.addAttribute("selectedDate", date);
        return "settlement";
    }
}
