package com.network.gateway;

import com.network.iso8583.IsoMessage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correlates outbound ISO 8583 requests with inbound responses using STAN
 * as the correlation key (acquirer code + STAN for uniqueness).
 */
@Component
public class PendingRequests {

    private final Map<String, CompletableFuture<IsoMessage>> pending = new ConcurrentHashMap<>();

    /** Register a pending request; returns the future to await on. */
    public CompletableFuture<IsoMessage> register(String correlationKey) {
        CompletableFuture<IsoMessage> future = new CompletableFuture<>();
        pending.put(correlationKey, future);
        return future;
    }

    /** Complete a pending request with the response message. */
    public boolean complete(String correlationKey, IsoMessage response) {
        CompletableFuture<IsoMessage> future = pending.remove(correlationKey);
        if (future != null) {
            future.complete(response);
            return true;
        }
        return false;
    }

    /** Cancel a pending request (e.g. on timeout). */
    public void cancel(String correlationKey) {
        CompletableFuture<IsoMessage> future = pending.remove(correlationKey);
        if (future != null) future.cancel(false);
    }

    /** Build correlation key from acquirer code and STAN */
    public static String key(String acquirerCode, String stan) {
        return acquirerCode + ":" + stan;
    }
}
