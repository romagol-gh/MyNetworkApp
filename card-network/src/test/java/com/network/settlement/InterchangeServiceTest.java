package com.network.settlement;

import com.network.domain.InterchangeRate;
import com.network.repository.InterchangeRateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class InterchangeServiceTest {

    private final InterchangeRateRepository repo    = Mockito.mock(InterchangeRateRepository.class);
    private final InterchangeService        service = new InterchangeService(repo);

    private static InterchangeRate rateOf(int bps, long flat) {
        InterchangeRate r = new InterchangeRate();
        r.setPercentageBps(bps);
        r.setFlatAmountMinor(flat);
        return r;
    }

    // ── Interchange fee tests ─────────────────────────────────────────────────

    @Test
    void calculateInterchangeFee_exactMccMatch() {
        // MCC 5411 → retail rate 120 bps + $0.08 flat
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("5411", "INTERCHANGE"))
                .thenReturn(Optional.of(rateOf(120, 8)));

        // $100.00 (10000 minor): round(10000 * 120 / 10000.0) + 8 = 120 + 8 = 128
        assertThat(service.calculateInterchangeFee(10000, "5411")).isEqualTo(128);
    }

    @Test
    void calculateInterchangeFee_fallsBackToDefault_whenNoExactMccMatch() {
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("9999", "INTERCHANGE"))
                .thenReturn(Optional.empty());
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("DEFAULT", "INTERCHANGE"))
                .thenReturn(Optional.of(rateOf(150, 10)));

        // $100.00: 150 + 10 = 160
        assertThat(service.calculateInterchangeFee(10000, "9999")).isEqualTo(160);
    }

    @Test
    void calculateInterchangeFee_nullMcc_usesDefault() {
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("DEFAULT", "INTERCHANGE"))
                .thenReturn(Optional.of(rateOf(150, 10)));

        assertThat(service.calculateInterchangeFee(10000, null)).isEqualTo(160);
    }

    @Test
    void calculateInterchangeFee_blankMcc_usesDefault() {
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("DEFAULT", "INTERCHANGE"))
                .thenReturn(Optional.of(rateOf(150, 10)));

        assertThat(service.calculateInterchangeFee(10000, "")).isEqualTo(160);
    }

    @Test
    void calculateInterchangeFee_noRateFound_returnsZero() {
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("5411", "INTERCHANGE"))
                .thenReturn(Optional.empty());
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("DEFAULT", "INTERCHANGE"))
                .thenReturn(Optional.empty());

        assertThat(service.calculateInterchangeFee(10000, "5411")).isEqualTo(0);
    }

    // ── Network fee tests ─────────────────────────────────────────────────────

    @Test
    void calculateNetworkFee_exactMccMatch() {
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("5411", "NETWORK"))
                .thenReturn(Optional.of(rateOf(5, 2)));

        // $100.00: round(10000 * 5 / 10000.0) + 2 = 5 + 2 = 7
        assertThat(service.calculateNetworkFee(10000, "5411")).isEqualTo(7);
    }

    @Test
    void calculateNetworkFee_fallsBackToDefault() {
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("5411", "NETWORK"))
                .thenReturn(Optional.empty());
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("DEFAULT", "NETWORK"))
                .thenReturn(Optional.of(rateOf(5, 2)));

        assertThat(service.calculateNetworkFee(10000, "5411")).isEqualTo(7);
    }

    @Test
    void calculateNetworkFee_noRateFound_returnsZero() {
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("5411", "NETWORK"))
                .thenReturn(Optional.empty());
        when(repo.findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("DEFAULT", "NETWORK"))
                .thenReturn(Optional.empty());

        assertThat(service.calculateNetworkFee(10000, "5411")).isEqualTo(0);
    }
}
