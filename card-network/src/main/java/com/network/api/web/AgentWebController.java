package com.network.api.web;

import com.network.domain.AgentRegistration;
import com.network.repository.AgentRegistrationRepository;
import com.network.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Controller
@RequestMapping("/agents")
public class AgentWebController {

    private final AgentRegistrationRepository agentRepository;
    private final TransactionRepository       transactionRepository;

    public AgentWebController(AgentRegistrationRepository agentRepository,
                              TransactionRepository transactionRepository) {
        this.agentRepository      = agentRepository;
        this.transactionRepository = transactionRepository;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page) {
        PageRequest pageable = PageRequest.of(page, 50, Sort.by("registeredAt").descending());
        Page<AgentRegistration> agents = status != null
                ? agentRepository.findByStatus(AgentRegistration.Status.valueOf(status), pageable)
                : agentRepository.findAll(pageable);

        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        model.addAttribute("agents", agents);
        model.addAttribute("statuses", AgentRegistration.Status.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("activeCount", agentRepository.countByStatus(AgentRegistration.Status.ACTIVE));
        model.addAttribute("agentTxnsToday", transactionRepository.countAgentTransactionsSince(dayStart));
        model.addAttribute("agentVolumeToday",
                String.format("$%,.2f", transactionRepository.sumAgentApprovedAmountSince(dayStart) / 100.0));
        return "agents";
    }

    @GetMapping("/{agentId}")
    public String detail(@PathVariable String agentId, Model model) {
        AgentRegistration agent = agentRepository.findByAgentId(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        model.addAttribute("agent", agent);
        model.addAttribute("recentTxns",
                transactionRepository.findTop20ByAgentIdOrderByTransmittedAtDesc(agentId));
        model.addAttribute("txnsToday",
                transactionRepository.countAgentTransactionsSince(dayStart));
        model.addAttribute("volumeToday",
                String.format("$%,.2f", transactionRepository.sumAgentApprovedAmountSince(dayStart) / 100.0));
        return "agent-detail";
    }
}
