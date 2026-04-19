package com.network.transaction;

/**
 * Thrown by AgentRegistrationService when an agent transaction violates a spend control:
 *   RC=61 — per-transaction limit or daily limit exceeded
 *   RC=57 — MCC not in agent's allowed scope
 *   RC=54 — delegation time window expired
 */
public class SpendControlException extends RuntimeException {

    private final String responseCode;

    public SpendControlException(String responseCode, String message) {
        super(message);
        this.responseCode = responseCode;
    }

    public String getResponseCode() { return responseCode; }
}
