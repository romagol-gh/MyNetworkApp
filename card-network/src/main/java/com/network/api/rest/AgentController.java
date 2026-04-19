package com.network.api.rest;

import com.network.domain.AgentRegistration;
import com.network.domain.Participant;
import com.network.repository.AgentRegistrationRepository;
import com.network.repository.ParticipantRepository;
import com.network.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRegistrationRepository agentRepository;
    private final ParticipantRepository       participantRepository;
    private final TransactionRepository       transactionRepository;

    public AgentController(AgentRegistrationRepository agentRepository,
                           ParticipantRepository participantRepository,
                           TransactionRepository transactionRepository) {
        this.agentRepository      = agentRepository;
        this.participantRepository = participantRepository;
        this.transactionRepository = transactionRepository;
    }

    @GetMapping
    public Page<AgentRegistration> list(@RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page) {
        PageRequest pageable = PageRequest.of(page, 50, Sort.by("registeredAt").descending());
        if (status != null) {
            return agentRepository.findByStatus(AgentRegistration.Status.valueOf(status), pageable);
        }
        return agentRepository.findAll(pageable);
    }

    @GetMapping("/{agentId}")
    public AgentRegistration get(@PathVariable String agentId) {
        return agentRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentRegistration create(@RequestBody CreateAgentRequest req) {
        if (agentRepository.findByAgentId(req.agentId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent ID already registered");
        }
        Participant participant = participantRepository.findById(req.participantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Participant not found"));

        AgentRegistration reg = new AgentRegistration();
        reg.setAgentId(req.agentId());
        reg.setParticipant(participant);
        reg.setPublicKey(req.publicKey());
        reg.setMccScope(req.mccScope());
        reg.setPerTxnLimit(req.perTxnLimit());
        reg.setDailyLimit(req.dailyLimit());
        reg.setTimeWindow(req.timeWindow());
        reg.setStatus(AgentRegistration.Status.ACTIVE);
        return agentRepository.save(reg);
    }

    @PutMapping("/{agentId}")
    public AgentRegistration update(@PathVariable String agentId,
                                    @RequestBody UpdateAgentRequest req) {
        AgentRegistration reg = agentRepository.findByAgentId(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (req.status() != null)       reg.setStatus(AgentRegistration.Status.valueOf(req.status()));
        if (req.mccScope() != null)     reg.setMccScope(req.mccScope());
        if (req.perTxnLimit() != null)  reg.setPerTxnLimit(req.perTxnLimit());
        if (req.dailyLimit() != null)   reg.setDailyLimit(req.dailyLimit());
        if (req.timeWindow() != null)   reg.setTimeWindow(req.timeWindow());
        return agentRepository.save(reg);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        return Map.of(
                "active",      agentRepository.countByStatus(AgentRegistration.Status.ACTIVE),
                "inactive",    agentRepository.countByStatus(AgentRegistration.Status.INACTIVE),
                "suspended",   agentRepository.countByStatus(AgentRegistration.Status.SUSPENDED),
                "txnsToday",   transactionRepository.countAgentTransactionsSince(dayStart),
                "volumeToday", transactionRepository.sumAgentApprovedAmountSince(dayStart)
        );
    }

    record CreateAgentRequest(String agentId, UUID participantId, String publicKey,
                              String mccScope, Long perTxnLimit, Long dailyLimit, String timeWindow) {}
    record UpdateAgentRequest(String status, String mccScope, Long perTxnLimit,
                              Long dailyLimit, String timeWindow) {}
}
