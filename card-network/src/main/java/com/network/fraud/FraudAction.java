package com.network.fraud;

public enum FraudAction {
    APPROVE,  // No fraud detected, proceed normally
    FLAG,     // Suspicious — proceed to issuer but create an alert
    DECLINE   // High confidence fraud — reject without contacting issuer
}
