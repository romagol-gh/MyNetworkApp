package com.network.api.web;

import com.network.domain.FraudRuleConfig;
import com.network.repository.BlacklistedCardRepository;
import com.network.repository.FraudAlertRepository;
import com.network.repository.FraudRuleConfigRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/fraud")
public class FraudWebController {

    private final FraudRuleConfigRepository ruleRepository;
    private final FraudAlertRepository      alertRepository;
    private final BlacklistedCardRepository blacklistRepository;

    public FraudWebController(FraudRuleConfigRepository ruleRepository,
                              FraudAlertRepository alertRepository,
                              BlacklistedCardRepository blacklistRepository) {
        this.ruleRepository      = ruleRepository;
        this.alertRepository     = alertRepository;
        this.blacklistRepository = blacklistRepository;
    }

    @GetMapping("/rules")
    public String rules(Model model) {
        model.addAttribute("rules", ruleRepository.findAll());
        model.addAttribute("ruleTypes", FraudRuleConfig.RuleType.values());
        model.addAttribute("actions", FraudRuleConfig.Action.values());
        return "fraud/rules";
    }

    @GetMapping("/alerts")
    public String alerts(Model model,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(required = false) Boolean reviewed) {
        PageRequest pageable = PageRequest.of(page, 50, Sort.by("createdAt").descending());
        if (reviewed != null) {
            model.addAttribute("alerts", alertRepository.findByReviewed(reviewed, pageable));
        } else {
            model.addAttribute("alerts", alertRepository.findAll(pageable));
        }
        model.addAttribute("pendingCount", alertRepository.countByReviewedFalse());
        return "fraud/alerts";
    }

    @GetMapping("/blacklist")
    public String blacklist(Model model) {
        model.addAttribute("blacklist", blacklistRepository.findAll());
        return "fraud/blacklist";
    }
}
