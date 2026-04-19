package com.network.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Entity
@Table(name = "agent_registrations")
public class AgentRegistration {

    public enum Status { ACTIVE, INACTIVE, SUSPENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 16)
    private String agentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    @Column(columnDefinition = "TEXT")
    private String publicKey;

    @Column(length = 255)
    private String mccScope;

    private Long perTxnLimit;
    private Long dailyLimit;

    @Column(length = 50)
    private String timeWindow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(nullable = false, updatable = false)
    private Instant registeredAt = Instant.now();

    private Instant lastSeenAt;
    private Instant expiresAt;

    public UUID getId() { return id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public Participant getParticipant() { return participant; }
    public void setParticipant(Participant participant) { this.participant = participant; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public String getMccScope() { return mccScope; }
    public void setMccScope(String mccScope) { this.mccScope = mccScope; }
    public Long getPerTxnLimit() { return perTxnLimit; }
    public void setPerTxnLimit(Long perTxnLimit) { this.perTxnLimit = perTxnLimit; }
    public Long getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(Long dailyLimit) { this.dailyLimit = dailyLimit; }
    public String getTimeWindow() { return timeWindow; }
    public void setTimeWindow(String timeWindow) { this.timeWindow = timeWindow; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
