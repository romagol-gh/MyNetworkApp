package com.network.api.web;

import com.network.domain.Dispute;
import com.network.repository.DisputeRepository;
import com.network.transaction.ChargebackReasonCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/disputes")
public class DisputeWebController {

    private static final List<Dispute.Status> PENDING_STATUSES = List.of(
            Dispute.Status.INITIATED, Dispute.Status.PENDING_ISSUER_RESPONSE,
            Dispute.Status.ACCEPTED, Dispute.Status.REPRESENTMENT, Dispute.Status.ARBITRATION);

    private static final Set<Dispute.Status> TERMINAL = EnumSet.of(
            Dispute.Status.WON, Dispute.Status.LOST, Dispute.Status.WITHDRAWN);

    private final DisputeRepository disputeRepository;

    public DisputeWebController(DisputeRepository disputeRepository) {
        this.disputeRepository = disputeRepository;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page) {
        PageRequest pageable = PageRequest.of(page, 50, Sort.by("initiatedAt").descending());
        Page<Dispute> disputes = status != null
                ? disputeRepository.findByStatus(Dispute.Status.valueOf(status), pageable)
                : disputeRepository.findAll(pageable);

        model.addAttribute("disputes", disputes);
        model.addAttribute("statuses", Dispute.Status.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("pendingCount", disputeRepository.countByStatusIn(PENDING_STATUSES));
        model.addAttribute("reasonDescriptions", ChargebackReasonCode.DESCRIPTIONS);
        return "disputes";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        Dispute dispute = disputeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dispute not found: " + id));

        List<Dispute.Status> allowed = allowedTransitions(dispute.getStatus());

        model.addAttribute("dispute", dispute);
        model.addAttribute("reasonDescription", ChargebackReasonCode.describe(dispute.getReasonCode()));
        model.addAttribute("allowedTransitions", allowed);
        model.addAttribute("isTerminal", TERMINAL.contains(dispute.getStatus()));
        return "dispute-detail";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable UUID id,
                         @RequestParam String status,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes ra) {
        Dispute dispute = disputeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dispute not found: " + id));

        dispute.setStatus(Dispute.Status.valueOf(status));
        if (notes != null && !notes.isBlank()) dispute.setNotes(notes);
        if (TERMINAL.contains(dispute.getStatus())) dispute.setResolvedAt(Instant.now());
        disputeRepository.save(dispute);

        ra.addFlashAttribute("success", "Dispute status updated to " + status);
        return "redirect:/disputes/" + id;
    }

    private List<Dispute.Status> allowedTransitions(Dispute.Status current) {
        return switch (current) {
            case INITIATED, PENDING_ISSUER_RESPONSE ->
                    List.of(Dispute.Status.ACCEPTED, Dispute.Status.REJECTED_BY_ISSUER,
                            Dispute.Status.WITHDRAWN);
            case ACCEPTED ->
                    List.of(Dispute.Status.REPRESENTMENT, Dispute.Status.LOST,
                            Dispute.Status.WITHDRAWN);
            case REJECTED_BY_ISSUER ->
                    List.of(Dispute.Status.REPRESENTMENT, Dispute.Status.WON,
                            Dispute.Status.WITHDRAWN);
            case REPRESENTMENT ->
                    List.of(Dispute.Status.ARBITRATION, Dispute.Status.WON,
                            Dispute.Status.LOST, Dispute.Status.WITHDRAWN);
            case ARBITRATION ->
                    List.of(Dispute.Status.WON, Dispute.Status.LOST, Dispute.Status.WITHDRAWN);
            default -> List.of(); // terminal states
        };
    }
}
