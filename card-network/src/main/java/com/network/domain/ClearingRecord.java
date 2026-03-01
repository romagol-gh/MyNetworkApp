package com.network.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "clearing_records")
public class ClearingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ClearingBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acquirer_id", nullable = false)
    private Participant acquirer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issuer_id", nullable = false)
    private Participant issuer;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    public UUID getId() { return id; }
    public ClearingBatch getBatch() { return batch; }
    public void setBatch(ClearingBatch batch) { this.batch = batch; }
    public Transaction getTransaction() { return transaction; }
    public void setTransaction(Transaction transaction) { this.transaction = transaction; }
    public Participant getAcquirer() { return acquirer; }
    public void setAcquirer(Participant acquirer) { this.acquirer = acquirer; }
    public Participant getIssuer() { return issuer; }
    public void setIssuer(Participant issuer) { this.issuer = issuer; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
