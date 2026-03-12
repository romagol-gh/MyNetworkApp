package com.network.transaction;

/**
 * Per-transaction result from a Test Lab scenario execution.
 *
 * @param pan              primary account number sent in the request
 * @param stan             system trace audit number
 * @param amountMinorUnits transaction amount in minor units
 * @param responseMti      MTI of the response (e.g. "0110", "0810", "0420")
 * @param responseCode     ISO 8583 response code (DE39)
 * @param passed           whether the actual RC matched the expected outcome
 * @param failureReason    human-readable explanation when passed=false, null otherwise
 */
public record TestTransactionResult(
        String pan,
        String stan,
        long amountMinorUnits,
        String responseMti,
        String responseCode,
        boolean passed,
        String failureReason
) {}
