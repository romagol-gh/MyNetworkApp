package com.network.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "settlement_records")
public class SettlementRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private LocalDate settlementDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    @Column(nullable = false)
    private Long netPosition;  // positive = owed TO participant, negative = participant owes

    @Column(nullable = false)
    private Long debitTotal = 0L;

    @Column(nullable = false)
    private Long creditTotal = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private ClearingBatch batch;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDate settlementDate) { this.settlementDate = settlementDate; }
    public Participant getParticipant() { return participant; }
    public void setParticipant(Participant participant) { this.participant = participant; }
    public Long getNetPosition() { return netPosition; }
    public void setNetPosition(Long netPosition) { this.netPosition = netPosition; }
    public Long getDebitTotal() { return debitTotal; }
    public void setDebitTotal(Long debitTotal) { this.debitTotal = debitTotal; }
    public Long getCreditTotal() { return creditTotal; }
    public void setCreditTotal(Long creditTotal) { this.creditTotal = creditTotal; }
    public ClearingBatch getBatch() { return batch; }
    public void setBatch(ClearingBatch batch) { this.batch = batch; }
    public Instant getCreatedAt() { return createdAt; }
}
