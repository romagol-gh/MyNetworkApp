package com.network.repository;

import com.network.domain.Participant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {
    Optional<Participant> findByCode(String code);
    List<Participant> findByType(Participant.Type type);
    List<Participant> findByStatus(Participant.Status status);
    boolean existsByCode(String code);
}
