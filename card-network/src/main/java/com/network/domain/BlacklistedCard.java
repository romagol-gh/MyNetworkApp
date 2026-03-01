package com.network.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "blacklisted_cards")
public class BlacklistedCard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String panHash; // SHA-256 of full PAN — never store raw PAN

    @Column(length = 255)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant expiresAt; // NULL = permanent block

    public UUID getId() { return id; }
    public String getPanHash() { return panHash; }
    public void setPanHash(String panHash) { this.panHash = panHash; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
