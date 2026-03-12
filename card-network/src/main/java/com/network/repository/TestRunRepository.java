package com.network.repository;

import com.network.domain.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TestRunRepository extends JpaRepository<TestRun, UUID> {
    List<TestRun> findTop50ByOrderByStartedAtDesc();
}
