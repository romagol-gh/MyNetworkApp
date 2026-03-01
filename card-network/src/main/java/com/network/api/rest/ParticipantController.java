package com.network.api.rest;

import com.network.domain.Participant;
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
@RequestMapping("/api/participants")
public class ParticipantController {

    private final ParticipantRepository repository;

    public ParticipantController(ParticipantRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Participant> list() { return repository.findAll(); }

    @GetMapping("/{id}")
    public Participant get(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Participant create(@Valid @RequestBody ParticipantRequest req) {
        if (repository.existsByCode(req.code())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Participant code already exists");
        }
        Participant p = new Participant();
        p.setName(req.name());
        p.setCode(req.code());
        p.setType(req.type());
        p.setHost(req.host());
        p.setPort(req.port());
        return repository.save(p);
    }

    @PutMapping("/{id}")
    public Participant update(@PathVariable UUID id, @Valid @RequestBody ParticipantRequest req) {
        Participant p = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        p.setName(req.name());
        p.setHost(req.host());
        p.setPort(req.port());
        if (req.status() != null) p.setStatus(req.status());
        return repository.save(p);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id) {
        Participant p = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        p.setStatus(Participant.Status.INACTIVE);
        repository.save(p);
    }

    public record ParticipantRequest(
            @NotBlank String name,
            @NotBlank String code,
            @NotNull Participant.Type type,
            String host,
            Integer port,
            Participant.Status status
    ) {}
}
