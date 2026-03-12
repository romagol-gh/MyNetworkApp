package com.network.api.rest;

import com.network.domain.InterchangeRate;
import com.network.domain.SettlementRecord;
import com.network.repository.InterchangeRateRepository;
import com.network.repository.SettlementRecordRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/interchange")
public class InterchangeController {

    private final InterchangeRateRepository   rateRepository;
    private final SettlementRecordRepository  settlementRepository;

    public InterchangeController(InterchangeRateRepository rateRepository,
                                 SettlementRecordRepository settlementRepository) {
        this.rateRepository      = rateRepository;
        this.settlementRepository = settlementRepository;
    }

    @GetMapping("/rates")
    public List<InterchangeRate> listRates() {
        return rateRepository.findAll();
    }

    @PostMapping("/rates")
    @ResponseStatus(HttpStatus.CREATED)
    public InterchangeRate createRate(@RequestBody InterchangeRate rate) {
        return rateRepository.save(rate);
    }

    @PutMapping("/rates/{id}")
    public InterchangeRate updateRate(@PathVariable UUID id, @RequestBody InterchangeRate rate) {
        if (!rateRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        rate.setFeeCategory(rate.getFeeCategory()); // ensure category preserved
        // save by re-using the existing id (entity is new, so we set id via reflection is not possible;
        // instead load, update fields)
        InterchangeRate existing = rateRepository.findById(id).orElseThrow();
        existing.setName(rate.getName());
        existing.setDescription(rate.getDescription());
        existing.setPercentageBps(rate.getPercentageBps());
        existing.setFlatAmountMinor(rate.getFlatAmountMinor());
        existing.setFeeCategory(rate.getFeeCategory());
        existing.setMccPattern(rate.getMccPattern());
        existing.setPriority(rate.getPriority());
        existing.setEnabled(rate.isEnabled());
        return rateRepository.save(existing);
    }

    @DeleteMapping("/rates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRate(@PathVariable UUID id) {
        rateRepository.deleteById(id);
    }

    @GetMapping("/report")
    public Object report(@RequestParam(required = false) String date,
                         @RequestParam(required = false) String format,
                         HttpServletResponse response) throws Exception {
        LocalDate d = date != null ? LocalDate.parse(date) : LocalDate.now();
        List<SettlementRecord> records = settlementRepository.findBySettlementDate(d);

        if ("csv".equalsIgnoreCase(format)) {
            response.setContentType("text/csv");
            response.setHeader("Content-Disposition", "attachment; filename=fees-" + d + ".csv");
            PrintWriter writer = response.getWriter();
            writer.println("participant_id,participant_name,settlement_date," +
                    "interchange_fees_paid,interchange_fees_received,network_fees_paid," +
                    "net_fees,net_position");
            for (SettlementRecord r : records) {
                long netFees = r.getInterchangeFeesReceived() - r.getInterchangeFeesPaid() - r.getNetworkFeesPaid();
                writer.printf("%s,%s,%s,%d,%d,%d,%d,%d%n",
                        r.getParticipant().getId(),
                        r.getParticipant().getName(),
                        r.getSettlementDate(),
                        r.getInterchangeFeesPaid(),
                        r.getInterchangeFeesReceived(),
                        r.getNetworkFeesPaid(),
                        netFees,
                        r.getNetPosition());
            }
            return null;
        }
        return records;
    }
}
