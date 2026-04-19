package com.network.api.web;

import com.network.domain.AgentRegistration;
import com.network.domain.ClearingBatch;
import com.network.gateway.SessionRegistry;
import com.network.domain.Dispute;
import com.network.repository.AgentRegistrationRepository;
import com.network.repository.ClearingBatchRepository;
import com.network.repository.DisputeRepository;
import com.network.repository.FraudAlertRepository;
import com.network.repository.InterchangeRateRepository;
import com.network.repository.TransactionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Controller
@RequestMapping("/")
public class DashboardController {

    private final TransactionRepository     transactionRepository;
    private final FraudAlertRepository      fraudAlertRepository;
    private final SessionRegistry           sessionRegistry;
    private final InterchangeRateRepository rateRepository;
    private final ClearingBatchRepository   batchRepository;
    private final DisputeRepository         disputeRepository;
    private final AgentRegistrationRepository agentRepository;

    public DashboardController(TransactionRepository transactionRepository,
                               FraudAlertRepository fraudAlertRepository,
                               SessionRegistry sessionRegistry,
                               InterchangeRateRepository rateRepository,
                               ClearingBatchRepository batchRepository,
                               DisputeRepository disputeRepository,
                               AgentRegistrationRepository agentRepository) {
        this.transactionRepository = transactionRepository;
        this.fraudAlertRepository  = fraudAlertRepository;
        this.sessionRegistry       = sessionRegistry;
        this.rateRepository        = rateRepository;
        this.batchRepository       = batchRepository;
        this.disputeRepository     = disputeRepository;
        this.agentRepository       = agentRepository;
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

        long activeRates = rateRepository.findByEnabledTrueOrderByPriorityDesc().size();
        long latestBatchFees = batchRepository
                .findTopByStatusOrderByBatchDateDesc(ClearingBatch.Status.COMPLETE)
                .map(b -> b.getTotalFees() != null ? b.getTotalFees() : 0L)
                .orElse(0L);

        model.addAttribute("totalTransactions", total);
        model.addAttribute("approvedTransactions", approved);
        model.addAttribute("approvalRate", String.format("%.1f", approvalRate));
        model.addAttribute("totalVolume", String.format("$%,.2f", volume / 100.0));
        model.addAttribute("pendingFraudAlerts", pending);
        model.addAttribute("activeSessions", sessions);
        model.addAttribute("activeRates", activeRates);
        model.addAttribute("latestBatchFees", String.format("$%,.2f", latestBatchFees / 100.0));

        long pendingDisputes = disputeRepository.countByStatusIn(List.of(
                Dispute.Status.INITIATED, Dispute.Status.PENDING_ISSUER_RESPONSE,
                Dispute.Status.ACCEPTED, Dispute.Status.REPRESENTMENT, Dispute.Status.ARBITRATION));
        model.addAttribute("pendingDisputes", pendingDisputes);

        model.addAttribute("activeAgents",    agentRepository.countByStatus(AgentRegistration.Status.ACTIVE));
        model.addAttribute("agentTxnsToday",  transactionRepository.countAgentTransactionsSince(dayStart));
        model.addAttribute("agentVolumeToday",
                String.format("$%,.2f", transactionRepository.sumAgentApprovedAmountSince(dayStart) / 100.0));

        return "dashboard";
    }
}
