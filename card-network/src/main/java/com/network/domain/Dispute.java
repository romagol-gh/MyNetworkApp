package com.network.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "disputes")
public class Dispute {

    public enum Status {
        INITIATED,
        PENDING_ISSUER_RESPONSE,
        ACCEPTED,
        REJECTED_BY_ISSUER,
        REPRESENTMENT,
        ARBITRATION,
        WON,
        LOST,
        WITHDRAWN
    }

    public enum ReasonNetwork { VISA, MASTERCARD }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiator_id", nullable = false)
    private Participant initiator;

    @Column(name = "reason_code", nullable = false, length = 10)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_network", nullable = false, length = 15)
    private ReasonNetwork reasonNetwork = ReasonNetwork.VISA;

    @Column(name = "chargeback_amount", nullable = false)
    private long chargebackAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.INITIATED;

    @Column(name = "notes")
    private String notes;

    @Column(name = "initiated_at", nullable = false, updatable = false)
    private Instant initiatedAt = Instant.now();

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public Transaction getTransaction() { return transaction; }
    public void setTransaction(Transaction transaction) { this.transaction = transaction; }
    public Participant getInitiator() { return initiator; }
    public void setInitiator(Participant initiator) { this.initiator = initiator; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public ReasonNetwork getReasonNetwork() { return reasonNetwork; }
    public void setReasonNetwork(ReasonNetwork reasonNetwork) { this.reasonNetwork = reasonNetwork; }
    public long getChargebackAmount() { return chargebackAmount; }
    public void setChargebackAmount(long chargebackAmount) { this.chargebackAmount = chargebackAmount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getInitiatedAt() { return initiatedAt; }
    public Instant getRespondedAt() { return respondedAt; }
    public void setRespondedAt(Instant respondedAt) { this.respondedAt = respondedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
