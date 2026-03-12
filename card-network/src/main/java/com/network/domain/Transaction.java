package com.network.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    public enum Status { PENDING, APPROVED, DECLINED, REVERSED, FLAGGED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 6)
    private String stan; // DE11

    @Column(length = 12)
    private String retrievalRef; // DE37

    @Column(length = 6)
    private String authId; // DE38

    @Column(nullable = false, length = 4)
    private String messageType; // 0100, 0110, 0200 …

    @Column(length = 6)
    private String processingCode; // DE3

    @Column(length = 19)
    private String panMasked; // first6****last4

    private Long amount; // DE4, minor units (cents)

    @Column(length = 3)
    private String currency; // DE49

    @Column(length = 2)
    private String responseCode; // DE39

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acquirer_id")
    private Participant acquirer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issuer_id")
    private Participant issuer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_txn_id")
    private Transaction originalTransaction; // for reversals

    @Column(nullable = false)
    private boolean fraudFlagged = false;

    @Column(nullable = false, updatable = false)
    private Instant transmittedAt = Instant.now();

    private Instant respondedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clearing_batch_id")
    private ClearingBatch clearingBatch;

    @Column(length = 4)
    private String mcc; // DE18

    public UUID getId() { return id; }
    public String getStan() { return stan; }
    public void setStan(String stan) { this.stan = stan; }
    public String getRetrievalRef() { return retrievalRef; }
    public void setRetrievalRef(String retrievalRef) { this.retrievalRef = retrievalRef; }
    public String getAuthId() { return authId; }
    public void setAuthId(String authId) { this.authId = authId; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public String getProcessingCode() { return processingCode; }
    public void setProcessingCode(String processingCode) { this.processingCode = processingCode; }
    public String getPanMasked() { return panMasked; }
    public void setPanMasked(String panMasked) { this.panMasked = panMasked; }
    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getResponseCode() { return responseCode; }
    public void setResponseCode(String responseCode) { this.responseCode = responseCode; }
    public Participant getAcquirer() { return acquirer; }
    public void setAcquirer(Participant acquirer) { this.acquirer = acquirer; }
    public Participant getIssuer() { return issuer; }
    public void setIssuer(Participant issuer) { this.issuer = issuer; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Transaction getOriginalTransaction() { return originalTransaction; }
    public void setOriginalTransaction(Transaction originalTransaction) { this.originalTransaction = originalTransaction; }
    public boolean isFraudFlagged() { return fraudFlagged; }
    public void setFraudFlagged(boolean fraudFlagged) { this.fraudFlagged = fraudFlagged; }
    public Instant getTransmittedAt() { return transmittedAt; }
    public Instant getRespondedAt() { return respondedAt; }
    public void setRespondedAt(Instant respondedAt) { this.respondedAt = respondedAt; }
    public ClearingBatch getClearingBatch() { return clearingBatch; }
    public void setClearingBatch(ClearingBatch clearingBatch) { this.clearingBatch = clearingBatch; }
    public String getMcc() { return mcc; }
    public void setMcc(String mcc) { this.mcc = mcc; }
}
