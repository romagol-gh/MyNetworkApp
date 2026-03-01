package com.network.fraud.rule;

import com.network.domain.FraudRuleConfig;
import com.network.iso8583.IsoMessage;
import com.network.repository.BlacklistedCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * Declines transactions from PANs that appear in the blacklist.
 * The PAN is hashed with SHA-256 before lookup — raw PANs are never stored.
 */
@Component
public class BlacklistRule implements FraudRule {

    private static final Logger log = LoggerFactory.getLogger(BlacklistRule.class);

    private final BlacklistedCardRepository blacklistedCardRepository;

    public BlacklistRule(BlacklistedCardRepository blacklistedCardRepository) {
        this.blacklistedCardRepository = blacklistedCardRepository;
    }

    @Override
    public FraudRuleConfig.RuleType supportedType() { return FraudRuleConfig.RuleType.BLACKLIST; }

    @Override
    public boolean evaluate(IsoMessage msg, FraudRuleConfig config) {
        String pan = msg.getPan();
        if (pan == null) return false;

        try {
            String hash = sha256Hex(pan);
            boolean blocked = blacklistedCardRepository.isBlacklisted(hash, Instant.now());
            if (blocked) {
                log.debug("BlacklistRule triggered for PAN (hashed)");
                return true;
            }
        } catch (Exception e) {
            log.error("BlacklistRule evaluation error: {}", e.getMessage());
        }
        return false;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
