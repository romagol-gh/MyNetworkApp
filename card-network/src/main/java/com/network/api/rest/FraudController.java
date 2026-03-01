package com.network.api.rest;

import com.network.domain.BlacklistedCard;
import com.network.domain.FraudAlert;
import com.network.domain.FraudRuleConfig;
import com.network.fraud.rule.BlacklistRule;
import com.network.repository.BlacklistedCardRepository;
import com.network.repository.FraudAlertRepository;
import com.network.repository.FraudRuleConfigRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final FraudRuleConfigRepository ruleRepository;
    private final FraudAlertRepository      alertRepository;
    private final BlacklistedCardRepository blacklistRepository;

    public FraudController(FraudRuleConfigRepository ruleRepository,
                           FraudAlertRepository alertRepository,
                           BlacklistedCardRepository blacklistRepository) {
        this.ruleRepository      = ruleRepository;
        this.alertRepository     = alertRepository;
        this.blacklistRepository = blacklistRepository;
    }

    // --- Rules ---

    @GetMapping("/rules")
    public List<FraudRuleConfig> listRules() { return ruleRepository.findAll(); }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public FraudRuleConfig createRule(@Valid @RequestBody RuleRequest req) {
        FraudRuleConfig rule = new FraudRuleConfig();
        rule.setName(req.name());
        rule.setRuleType(req.ruleType());
        rule.setParameters(req.parameters() != null ? req.parameters() : "{}");
        rule.setScoreWeight(req.scoreWeight());
        rule.setAction(req.action());
        return ruleRepository.save(rule);
    }

    @PutMapping("/rules/{id}")
    public FraudRuleConfig updateRule(@PathVariable UUID id, @Valid @RequestBody RuleRequest req) {
        FraudRuleConfig rule = ruleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        rule.setName(req.name());
        rule.setParameters(req.parameters() != null ? req.parameters() : "{}");
        rule.setScoreWeight(req.scoreWeight());
        rule.setAction(req.action());
        rule.setEnabled(req.enabled());
        return ruleRepository.save(rule);
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id) {
        if (!ruleRepository.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        ruleRepository.deleteById(id);
    }

    // --- Alerts ---

    @GetMapping("/alerts")
    public Page<FraudAlert> listAlerts(
            @RequestParam(required = false) Boolean reviewed,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200),
                Sort.by("createdAt").descending());
        if (reviewed != null) return alertRepository.findByReviewed(reviewed, pageable);
        return alertRepository.findAll(pageable);
    }

    @PutMapping("/alerts/{id}/review")
    public FraudAlert reviewAlert(@PathVariable UUID id,
                                  @RequestParam(defaultValue = "admin") String reviewedBy) {
        FraudAlert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        alert.setReviewed(true);
        alert.setReviewedBy(reviewedBy);
        alert.setReviewedAt(Instant.now());
        return alertRepository.save(alert);
    }

    // --- Blacklist ---

    @GetMapping("/blacklist")
    public List<BlacklistedCard> listBlacklist() { return blacklistRepository.findAll(); }

    @PostMapping("/blacklist")
    @ResponseStatus(HttpStatus.CREATED)
    public BlacklistedCard addToBlacklist(@Valid @RequestBody BlacklistRequest req) {
        String hash = BlacklistRule.sha256Hex(req.pan());
        if (blacklistRepository.findByPanHash(hash).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PAN already blacklisted");
        }
        BlacklistedCard card = new BlacklistedCard();
        card.setPanHash(hash);
        card.setReason(req.reason());
        card.setExpiresAt(req.expiresAt());
        return blacklistRepository.save(card);
    }

    @DeleteMapping("/blacklist/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromBlacklist(@PathVariable UUID id) {
        if (!blacklistRepository.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        blacklistRepository.deleteById(id);
    }

    // --- Request records ---

    public record RuleRequest(
            @NotBlank String name,
            @NotNull FraudRuleConfig.RuleType ruleType,
            String parameters,
            int scoreWeight,
            @NotNull FraudRuleConfig.Action action,
            boolean enabled
    ) {}

    public record BlacklistRequest(
            @NotBlank String pan,   // raw PAN — hashed server-side, never persisted
            String reason,
            Instant expiresAt
    ) {}
}
