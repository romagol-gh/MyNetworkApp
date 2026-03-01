package com.network.routing;

import com.network.domain.BinRange;
import com.network.domain.Participant;
import com.network.repository.BinRangeRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Routes a PAN to the correct issuer bank by looking up BIN ranges.
 * Uses the first 6–8 digits of the PAN (BIN) for lookup.
 */
@Component
public class BinRouter {

    private final BinRangeRepository binRangeRepository;

    public BinRouter(BinRangeRepository binRangeRepository) {
        this.binRangeRepository = binRangeRepository;
    }

    /**
     * Find the issuer for a given PAN.
     * @param pan full PAN string (e.g. "4111111111111111")
     * @return issuer Participant, or empty if no BIN range matches
     */
    public Optional<Participant> route(String pan) {
        if (pan == null || pan.length() < 6) return Optional.empty();

        return binRangeRepository.findByPan(pan)
                .map(BinRange::getIssuer);
    }
}
