package com.network.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "interchange_rates")
public class InterchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    private String description;

    @Column(nullable = false)
    private int percentageBps = 0;   // basis points: 150 = 1.50%

    @Column(nullable = false)
    private long flatAmountMinor = 0; // minor units: 10 = $0.10

    @Column(nullable = false, length = 20)
    private String feeCategory = "INTERCHANGE"; // INTERCHANGE | NETWORK

    @Column(nullable = false, length = 10)
    private String mccPattern = "DEFAULT";

    @Column(nullable = false)
    private int priority = 0;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Fee = round(amount * bps / 10_000) + flat */
    public long calculateFee(long amountMinor) {
        return Math.round(amountMinor * percentageBps / 10_000.0) + flatAmountMinor;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getPercentageBps() { return percentageBps; }
    public void setPercentageBps(int percentageBps) { this.percentageBps = percentageBps; }
    public long getFlatAmountMinor() { return flatAmountMinor; }
    public void setFlatAmountMinor(long flatAmountMinor) { this.flatAmountMinor = flatAmountMinor; }
    public String getFeeCategory() { return feeCategory; }
    public void setFeeCategory(String feeCategory) { this.feeCategory = feeCategory; }
    public String getMccPattern() { return mccPattern; }
    public void setMccPattern(String mccPattern) { this.mccPattern = mccPattern; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
