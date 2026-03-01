package com.network.fraud;

import com.network.domain.FraudRuleConfig;
import com.network.fraud.rule.AmountLimitRule;
import com.network.fraud.rule.BlacklistRule;
import com.network.fraud.rule.FraudRule;
import com.network.iso8583.IsoMessage;
import com.network.repository.BlacklistedCardRepository;
import com.network.repository.FraudAlertRepository;
import com.network.repository.FraudRuleConfigRepository;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FraudDetectionServiceTest {

    private FraudRuleConfigRepository ruleRepo;
    private FraudAlertRepository alertRepo;
    private BlacklistedCardRepository blacklistRepo;
    private FraudDetectionService service;

    @BeforeEach
    void setUp() {
        ruleRepo      = Mockito.mock(FraudRuleConfigRepository.class);
        alertRepo     = Mockito.mock(FraudAlertRepository.class);
        blacklistRepo = Mockito.mock(BlacklistedCardRepository.class);

        List<FraudRule> rules = List.of(
                new AmountLimitRule(),
                new BlacklistRule(blacklistRepo)
        );
        service = new FraudDetectionService(ruleRepo, alertRepo, rules);
    }

    @Test
    void evaluate_approve_whenNoRulesTriggered() throws Exception {
        FraudRuleConfig amountRule = new FraudRuleConfig();
        amountRule.setRuleType(FraudRuleConfig.RuleType.AMOUNT_LIMIT);
        amountRule.setParameters("{\"threshold_minor_units\":500000}");
        amountRule.setScoreWeight(50);
        amountRule.setAction(FraudRuleConfig.Action.FLAG);
        amountRule.setEnabled(true);
        amountRule.setName("Large amount");

        when(ruleRepo.findByEnabledTrue()).thenReturn(List.of(amountRule));

        IsoMessage msg = buildMsg("4111111111111111", "000000010000"); // $100

        FraudResult result = service.evaluate(msg);

        assertThat(result.action()).isEqualTo(FraudAction.APPROVE);
        assertThat(result.score()).isEqualTo(0);
    }

    @Test
    void evaluate_flag_whenAmountExceedsThreshold() throws Exception {
        FraudRuleConfig amountRule = new FraudRuleConfig();
        amountRule.setRuleType(FraudRuleConfig.RuleType.AMOUNT_LIMIT);
        amountRule.setParameters("{\"threshold_minor_units\":500000}");
        amountRule.setScoreWeight(50);
        amountRule.setAction(FraudRuleConfig.Action.FLAG);
        amountRule.setEnabled(true);
        amountRule.setName("Large amount");

        when(ruleRepo.findByEnabledTrue()).thenReturn(List.of(amountRule));

        IsoMessage msg = buildMsg("4111111111111111", "000001000000"); // $10,000

        FraudResult result = service.evaluate(msg);

        assertThat(result.action()).isEqualTo(FraudAction.FLAG);
        assertThat(result.score()).isEqualTo(50);
        assertThat(result.triggeredRuleNames()).contains("Large amount");
    }

    @Test
    void evaluate_decline_whenCardBlacklisted() throws Exception {
        FraudRuleConfig blacklistRule = new FraudRuleConfig();
        blacklistRule.setRuleType(FraudRuleConfig.RuleType.BLACKLIST);
        blacklistRule.setParameters("{}");
        blacklistRule.setScoreWeight(100);
        blacklistRule.setAction(FraudRuleConfig.Action.DECLINE);
        blacklistRule.setEnabled(true);
        blacklistRule.setName("Blacklisted card");

        when(ruleRepo.findByEnabledTrue()).thenReturn(List.of(blacklistRule));
        when(blacklistRepo.isBlacklisted(anyString(), any())).thenReturn(true);

        IsoMessage msg = buildMsg("4111111111111111", "000000010000");

        FraudResult result = service.evaluate(msg);

        assertThat(result.action()).isEqualTo(FraudAction.DECLINE);
        assertThat(result.triggeredRuleNames()).contains("Blacklisted card");
    }

    private IsoMessage buildMsg(String pan, String amount) throws Exception {
        ISOMsg raw = new ISOMsg();
        raw.setPackager(new GenericPackager(
                getClass().getResourceAsStream("/packager/iso87binary.xml")));
        raw.setMTI("0100");
        raw.set(2, pan);
        raw.set(4, amount);
        raw.set(11, "123456");
        return new IsoMessage(raw);
    }
}
