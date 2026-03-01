package com.network.api.web;

import com.network.gateway.SessionRegistry;
import com.network.repository.FraudAlertRepository;
import com.network.repository.TransactionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Controller
@RequestMapping("/")
public class DashboardController {

    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository  fraudAlertRepository;
    private final SessionRegistry       sessionRegistry;

    public DashboardController(TransactionRepository transactionRepository,
                               FraudAlertRepository fraudAlertRepository,
                               SessionRegistry sessionRegistry) {
        this.transactionRepository = transactionRepository;
        this.fraudAlertRepository  = fraudAlertRepository;
        this.sessionRegistry       = sessionRegistry;
    }

    @GetMapping
    public String dashboard(Model model) {
        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant now      = Instant.now();

        long total    = transactionRepository.countByPeriod(dayStart, now);
        long approved = transactionRepository.countApprovedByPeriod(dayStart, now);
        long volume   = transactionRepository.sumApprovedAmountByPeriod(dayStart, now);
        long pending  = fraudAlertRepository.countByReviewedFalse();
        int  sessions = sessionRegistry.size();

        double approvalRate = total > 0 ? (double) approved / total * 100 : 0;

        model.addAttribute("totalTransactions", total);
        model.addAttribute("approvedTransactions", approved);
        model.addAttribute("approvalRate", String.format("%.1f", approvalRate));
        model.addAttribute("totalVolume", volume);
        model.addAttribute("pendingFraudAlerts", pending);
        model.addAttribute("activeSessions", sessions);

        return "dashboard";
    }
}
