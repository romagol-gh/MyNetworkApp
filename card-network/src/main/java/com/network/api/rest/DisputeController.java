package com.network.api.rest;

import com.network.domain.Dispute;
import com.network.domain.Participant;
import com.network.domain.Transaction;
import com.network.repository.DisputeRepository;
import com.network.repository.ParticipantRepository;
import com.network.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    private final DisputeRepository     disputeRepository;
    private final TransactionRepository transactionRepository;
    private final ParticipantRepository participantRepository;

    public DisputeController(DisputeRepository disputeRepository,
                             TransactionRepository transactionRepository,
                             ParticipantRepository participantRepository) {
        this.disputeRepository     = disputeRepository;
        this.transactionRepository = transactionRepository;
        this.participantRepository = participantRepository;
    }

    @GetMapping
    public Page<Dispute> list(@RequestParam(required = false) String status,
                              @RequestParam(defaultValue = "0") int page) {
        PageRequest pageable = PageRequest.of(page, 50, Sort.by("initiatedAt").descending());
        if (status != null) {
            return disputeRepository.findByStatus(Dispute.Status.valueOf(status), pageable);
        }
        return disputeRepository.findAll(pageable);
    }

    @GetMapping("/{id}")
    public Dispute get(@PathVariable UUID id) {
        return disputeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dispute create(@RequestBody CreateDisputeRequest req) {
        Transaction txn = transactionRepository.findById(req.txnId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));

        if (txn.getStatus() != Transaction.Status.APPROVED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only APPROVED transactions can be disputed");
        }

        Participant initiator = txn.getAcquirer();
        if (req.initiatorId() != null) {
            initiator = participantRepository.findById(req.initiatorId())
                    .orElse(initiator);
        }

        Dispute dispute = new Dispute();
        dispute.setTransaction(txn);
        dispute.setInitiator(initiator);
        dispute.setReasonCode(req.reasonCode());
        dispute.setReasonNetwork(req.reasonNetwork() != null
                ? Dispute.ReasonNetwork.valueOf(req.reasonNetwork())
                : Dispute.ReasonNetwork.VISA);
        dispute.setChargebackAmount(req.amount() != null ? req.amount() : txn.getAmount());
        dispute.setNotes(req.notes());
        dispute.setStatus(Dispute.Status.INITIATED);
        return disputeRepository.save(dispute);
    }

    @PutMapping("/{id}")
    public Dispute update(@PathVariable UUID id, @RequestBody UpdateDisputeRequest req) {
        Dispute dispute = disputeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        dispute.setStatus(Dispute.Status.valueOf(req.status()));
        if (req.notes() != null) dispute.setNotes(req.notes());

        Dispute.Status newStatus = dispute.getStatus();
        if (newStatus == Dispute.Status.WON || newStatus == Dispute.Status.LOST
                || newStatus == Dispute.Status.WITHDRAWN) {
            dispute.setResolvedAt(Instant.now());
        }
        return disputeRepository.save(dispute);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        List<Dispute.Status> pending = List.of(
                Dispute.Status.INITIATED, Dispute.Status.PENDING_ISSUER_RESPONSE,
                Dispute.Status.ACCEPTED, Dispute.Status.REPRESENTMENT, Dispute.Status.ARBITRATION);
        return Map.of(
                "total",   disputeRepository.count(),
                "pending", disputeRepository.countByStatusIn(pending),
                "won",     disputeRepository.countByStatusIn(List.of(Dispute.Status.WON)),
                "lost",    disputeRepository.countByStatusIn(List.of(Dispute.Status.LOST))
        );
    }

    record CreateDisputeRequest(UUID txnId, UUID initiatorId, String reasonCode,
                                String reasonNetwork, Long amount, String notes) {}
    record UpdateDisputeRequest(String status, String notes) {}
}
