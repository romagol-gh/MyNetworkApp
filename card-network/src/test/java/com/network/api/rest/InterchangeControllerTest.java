package com.network.api.rest;

import com.network.domain.InterchangeRate;
import com.network.domain.Participant;
import com.network.domain.SettlementRecord;
import com.network.repository.InterchangeRateRepository;
import com.network.repository.SettlementRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterchangeControllerTest {

    private final InterchangeRateRepository  rateRepo = Mockito.mock(InterchangeRateRepository.class);
    private final SettlementRecordRepository settRepo = Mockito.mock(SettlementRecordRepository.class);
    private final InterchangeController controller = new InterchangeController(rateRepo, settRepo);

    private static InterchangeRate sampleRate() {
        InterchangeRate r = new InterchangeRate();
        r.setName("Standard Interchange");
        r.setPercentageBps(150);
        r.setFlatAmountMinor(10);
        r.setFeeCategory("INTERCHANGE");
        r.setMccPattern("DEFAULT");
        r.setPriority(0);
        return r;
    }

    @Test
    void listRates_returnsAllRates() {
        List<InterchangeRate> rates = List.of(sampleRate());
        when(rateRepo.findAll()).thenReturn(rates);

        assertThat(controller.listRates()).isEqualTo(rates);
    }

    @Test
    void createRate_savesAndReturnsNewRate() {
        InterchangeRate rate = sampleRate();
        when(rateRepo.save(any())).thenReturn(rate);

        InterchangeRate result = controller.createRate(rate);

        assertThat(result).isEqualTo(rate);
        verify(rateRepo).save(rate);
    }

    @Test
    void updateRate_modifiesExistingRateFields() {
        UUID id = UUID.randomUUID();
        InterchangeRate existing = sampleRate();

        InterchangeRate update = sampleRate();
        update.setName("Updated Rate");
        update.setPercentageBps(200);
        update.setFlatAmountMinor(15);

        when(rateRepo.existsById(id)).thenReturn(true);
        when(rateRepo.findById(id)).thenReturn(Optional.of(existing));
        when(rateRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InterchangeRate result = controller.updateRate(id, update);

        assertThat(result.getName()).isEqualTo("Updated Rate");
        assertThat(result.getPercentageBps()).isEqualTo(200);
        assertThat(result.getFlatAmountMinor()).isEqualTo(15);
        verify(rateRepo).save(existing);
    }

    @Test
    void deleteRate_callsDeleteById() {
        UUID id = UUID.randomUUID();
        controller.deleteRate(id);
        verify(rateRepo).deleteById(id);
    }

    @Test
    void report_returnsSettlementRecordsForDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 3, 1);
        List<SettlementRecord> records = List.of(new SettlementRecord());
        when(settRepo.findBySettlementDate(date)).thenReturn(records);

        Object result = controller.report("2026-03-01", null, new MockHttpServletResponse());

        assertThat(result).isEqualTo(records);
    }

    @Test
    void report_csvFormat_writesHeaderAndRowsToResponse() throws Exception {
        LocalDate date = LocalDate.of(2026, 3, 1);

        Participant participant = Mockito.mock(Participant.class);
        when(participant.getId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        when(participant.getName()).thenReturn("Acquiring Bank");

        SettlementRecord record = new SettlementRecord();
        record.setSettlementDate(date);
        record.setParticipant(participant);
        record.setDebitTotal(10000L);
        record.setCreditTotal(0L);
        record.setInterchangeFeesPaid(128L);
        record.setInterchangeFeesReceived(0L);
        record.setNetworkFeesPaid(7L);
        record.setNetPosition(-10135L);

        when(settRepo.findBySettlementDate(date)).thenReturn(List.of(record));

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.report("2026-03-01", "csv", response);

        assertThat(response.getContentType()).isEqualTo("text/csv");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=fees-2026-03-01.csv");

        String body = response.getContentAsString();
        assertThat(body).contains("participant_id,participant_name,settlement_date");
        assertThat(body).contains("interchange_fees_paid,interchange_fees_received,network_fees_paid");
        assertThat(body).contains("Acquiring Bank");
        assertThat(body).contains("128");
        assertThat(body).contains("-10135");
    }
}
