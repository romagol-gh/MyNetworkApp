package com.network.settlement;

import com.network.domain.InterchangeRate;
import com.network.repository.InterchangeRateRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Looks up interchange and network fee rates and computes per-transaction fees.
 */
@Service
public class InterchangeService {

    private final InterchangeRateRepository rateRepository;

    public InterchangeService(InterchangeRateRepository rateRepository) {
        this.rateRepository = rateRepository;
    }

    /** Interchange fee paid by acquirer to issuer. */
    public long calculateInterchangeFee(long amountMinor, String mcc) {
        return calculateFee(amountMinor, mcc, "INTERCHANGE");
    }

    /** Network/scheme fee paid by both acquirer and issuer to the network. */
    public long calculateNetworkFee(long amountMinor, String mcc) {
        return calculateFee(amountMinor, mcc, "NETWORK");
    }

    private long calculateFee(long amountMinor, String mcc, String category) {
        // Try exact MCC match first
        if (mcc != null && !mcc.isBlank() && !"DEFAULT".equals(mcc)) {
            Optional<InterchangeRate> exact = rateRepository
                    .findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc(mcc, category);
            if (exact.isPresent()) {
                return exact.get().calculateFee(amountMinor);
            }
        }
        // Fall back to DEFAULT
        return rateRepository
                .findTopByMccPatternAndFeeCategoryAndEnabledTrueOrderByPriorityDesc("DEFAULT", category)
                .map(r -> r.calculateFee(amountMinor))
                .orElse(0L);
    }
}
