package com.network.repository;

import com.network.domain.AgentRegistration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRegistrationRepository extends JpaRepository<AgentRegistration, UUID> {

    Optional<AgentRegistration> findByAgentId(String agentId);

    List<AgentRegistration> findByParticipantId(UUID participantId);

    long countByStatus(AgentRegistration.Status status);

    Page<AgentRegistration> findByStatus(AgentRegistration.Status status, Pageable pageable);
}
