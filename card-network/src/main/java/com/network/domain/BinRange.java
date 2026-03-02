package com.network.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bin_ranges")
public class BinRange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 19)
    private String low;  // e.g. "400000"

    @Column(nullable = false, length = 19)
    private String high; // e.g. "499999"

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "issuer_id", nullable = false)
    private Participant issuer;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public String getLow() { return low; }
    public void setLow(String low) { this.low = low; }
    public String getHigh() { return high; }
    public void setHigh(String high) { this.high = high; }
    public Participant getIssuer() { return issuer; }
    public void setIssuer(Participant issuer) { this.issuer = issuer; }
    public Instant getCreatedAt() { return createdAt; }
}
