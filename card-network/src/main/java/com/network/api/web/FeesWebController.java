package com.network.api.web;

import com.network.repository.InterchangeRateRepository;
import com.network.repository.SettlementRecordRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/fees")
public class FeesWebController {

    private final InterchangeRateRepository  rateRepository;
    private final SettlementRecordRepository settlementRepository;

    public FeesWebController(InterchangeRateRepository rateRepository,
                             SettlementRecordRepository settlementRepository) {
        this.rateRepository      = rateRepository;
        this.settlementRepository = settlementRepository;
    }

    @GetMapping
    public String fees(Model model, @RequestParam(required = false) String date) {
        List<LocalDate> allDates = settlementRepository.findDistinctSettlementDates();
        LocalDate selected = date != null ? LocalDate.parse(date)
                : (allDates.isEmpty() ? LocalDate.now() : allDates.get(0));

        model.addAttribute("rates", rateRepository.findAll());
        model.addAttribute("records", settlementRepository.findBySettlementDate(selected));
        model.addAttribute("selectedDate", selected);
        model.addAttribute("allDates", allDates);
        return "fees";
    }
}
