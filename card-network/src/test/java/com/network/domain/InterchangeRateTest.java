package com.network.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InterchangeRateTest {

    private static InterchangeRate rate(int bps, long flat) {
        InterchangeRate r = new InterchangeRate();
        r.setPercentageBps(bps);
        r.setFlatAmountMinor(flat);
        return r;
    }

    @Test
    void calculateFee_standardRate() {
        // 150 bps (1.50%) + $0.10 flat on $100.00 (10000 minor units)
        // = round(10000 * 150 / 10000.0) + 10 = 150 + 10 = 160
        assertThat(rate(150, 10).calculateFee(10000)).isEqualTo(160);
    }

    @Test
    void calculateFee_bpsOnly() {
        // 200 bps (2.00%) on $50.00 = round(5000 * 200 / 10000.0) = 100
        assertThat(rate(200, 0).calculateFee(5000)).isEqualTo(100);
    }

    @Test
    void calculateFee_flatOnly() {
        // 0 bps + $0.25 flat = 25 minor units regardless of amount
        assertThat(rate(0, 25).calculateFee(10000)).isEqualTo(25);
        assertThat(rate(0, 25).calculateFee(0)).isEqualTo(25);
    }

    @Test
    void calculateFee_zeroRate() {
        assertThat(rate(0, 0).calculateFee(10000)).isEqualTo(0);
        assertThat(rate(0, 0).calculateFee(0)).isEqualTo(0);
    }

    @Test
    void calculateFee_roundsHalfUp() {
        // 150 bps on $100.01 (10001 minor units):
        // 10001 * 150 / 10000.0 = 150.015 → Math.round = 150
        assertThat(rate(150, 0).calculateFee(10001)).isEqualTo(150);

        // 150 bps on $100.03 (10003 minor units):
        // 10003 * 150 / 10000.0 = 150.045 → Math.round = 150
        assertThat(rate(150, 0).calculateFee(10003)).isEqualTo(150);

        // 33 bps on $100.00 = round(10000 * 33 / 10000.0) = 33
        assertThat(rate(33, 0).calculateFee(10000)).isEqualTo(33);
    }

    @Test
    void calculateFee_highRiskRate() {
        // 250 bps (2.50%) + $0.15 flat on $100.00
        // = round(10000 * 250 / 10000.0) + 15 = 250 + 15 = 265
        assertThat(rate(250, 15).calculateFee(10000)).isEqualTo(265);
    }
}
