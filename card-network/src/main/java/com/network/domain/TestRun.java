package com.network.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_runs")
public class TestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String scenario;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String configJson;

    @Column(nullable = false)
    private int totalTxns;

    @Column(nullable = false)
    private int passedTxns;

    @Column(nullable = false)
    private int failedTxns;

    @Column(nullable = false)
    private int fraudFlagged;

    @Column(nullable = false)
    private int fraudDeclined;

    @Column(columnDefinition = "TEXT")
    private String detailJson;

    @Column(nullable = false)
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    private Long durationMs;

    public UUID getId() { return id; }
    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public int getTotalTxns() { return totalTxns; }
    public void setTotalTxns(int totalTxns) { this.totalTxns = totalTxns; }
    public int getPassedTxns() { return passedTxns; }
    public void setPassedTxns(int passedTxns) { this.passedTxns = passedTxns; }
    public int getFailedTxns() { return failedTxns; }
    public void setFailedTxns(int failedTxns) { this.failedTxns = failedTxns; }
    public int getFraudFlagged() { return fraudFlagged; }
    public void setFraudFlagged(int fraudFlagged) { this.fraudFlagged = fraudFlagged; }
    public int getFraudDeclined() { return fraudDeclined; }
    public void setFraudDeclined(int fraudDeclined) { this.fraudDeclined = fraudDeclined; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
}
