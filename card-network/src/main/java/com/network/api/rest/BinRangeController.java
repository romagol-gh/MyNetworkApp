package com.network.api.rest;

import com.network.domain.BinRange;
import com.network.domain.Participant;
import com.network.repository.BinRangeRepository;
import com.network.repository.ParticipantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bin-ranges")
public class BinRangeController {

    private final BinRangeRepository    binRangeRepository;
    private final ParticipantRepository participantRepository;

    public BinRangeController(BinRangeRepository binRangeRepository,
                              ParticipantRepository participantRepository) {
        this.binRangeRepository   = binRangeRepository;
        this.participantRepository = participantRepository;
    }

    @GetMapping
    public List<BinRange> list() { return binRangeRepository.findAll(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BinRange create(@Valid @RequestBody BinRangeRequest req) {
        Participant issuer = participantRepository.findById(req.issuerId())
                .filter(p -> p.getType() == Participant.Type.ISSUER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Issuer not found"));

        if (binRangeRepository.existsByLowAndHigh(req.low(), req.high())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BIN range already exists");
        }

        BinRange range = new BinRange();
        range.setLow(req.low());
        range.setHigh(req.high());
        range.setIssuer(issuer);
        return binRangeRepository.save(range);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        if (!binRangeRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        binRangeRepository.deleteById(id);
    }

    public record BinRangeRequest(
            @NotBlank String low,
            @NotBlank String high,
            @NotNull UUID issuerId
    ) {}
}
