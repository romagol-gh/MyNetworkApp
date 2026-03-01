package com.network.clearing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Triggers end-of-day clearing at 23:00 UTC daily.
 */
@Component
public class ClearingJob {

    private static final Logger log = LoggerFactory.getLogger(ClearingJob.class);

    private final ClearingEngine clearingEngine;

    public ClearingJob(ClearingEngine clearingEngine) {
        this.clearingEngine = clearingEngine;
    }

    @Scheduled(cron = "${clearing.cron:0 0 23 * * *}", zone = "UTC")
    public void run() {
        log.info("Starting scheduled clearing...");
        clearingEngine.runClearing(LocalDate.now());
    }
}
