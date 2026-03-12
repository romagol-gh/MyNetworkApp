package com.network.settlement;

import com.network.clearing.ClearingEngine;
import com.network.domain.ClearingBatch;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Triggers settlement at 23:30 UTC daily (after clearing completes at 23:00).
 */
@Component
public class SettlementJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementJob.class);

    private final ClearingEngine   clearingEngine;
    private final SettlementEngine settlementEngine;

    public SettlementJob(ClearingEngine clearingEngine, SettlementEngine settlementEngine) {
        this.clearingEngine   = clearingEngine;
        this.settlementEngine = settlementEngine;
    }

    @Scheduled(cron = "${settlement.cron:0 30 23 * * *}", zone = "UTC")
    @SchedulerLock(name = "settlementJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        log.info("Starting scheduled settlement...");
        ClearingBatch batch = clearingEngine.runClearing(LocalDate.now());
        if (batch != null && batch.getStatus() == ClearingBatch.Status.COMPLETE) {
            settlementEngine.settle(batch);
        } else {
            log.info("No clearing batch to settle.");
        }
    }
}
