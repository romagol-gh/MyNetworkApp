package com.network.transaction;

import com.network.domain.Participant;
import com.network.domain.Transaction;
import com.network.gateway.ParticipantConnector;
import com.network.gateway.PendingRequests;
import com.network.iso8583.Field;
import com.network.iso8583.IsoMessage;
import com.network.iso8583.MessageFactory;
import com.network.iso8583.ResponseCode;
import com.network.repository.ParticipantRepository;
import com.network.repository.TransactionRepository;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles ISO 8583 Reversal messages (0400/0420).
 * Looks up the original transaction by DE37 (Retrieval Reference Number)
 * and forwards the reversal to the issuer.
 */
@Service
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    @Value("${gateway.response-timeout:30000}")
    private long responseTimeoutMs;

    private final MessageFactory        messageFactory;
    private final TransactionRepository transactionRepository;
    private final ParticipantRepository participantRepository;
    private final ParticipantConnector  participantConnector;
    private final PendingRequests       pendingRequests;

    public ReversalService(MessageFactory messageFactory,
                           TransactionRepository transactionRepository,
                           ParticipantRepository participantRepository,
                           ParticipantConnector participantConnector,
                           PendingRequests pendingRequests) {
        this.messageFactory       = messageFactory;
        this.transactionRepository = transactionRepository;
        this.participantRepository = participantRepository;
        this.participantConnector = participantConnector;
        this.pendingRequests      = pendingRequests;
    }

    @Transactional
    public void reverse(IsoMessage request, Channel acquirerChannel) {
        String retrievalRef = request.getRetrievalRef();
        String acquirerCode = request.getAcquirerCode();
        String stan         = request.getStan();

        // Find the original transaction
        Optional<Transaction> originalOpt = transactionRepository.findByRetrievalRef(retrievalRef);
        if (originalOpt.isEmpty()) {
            log.warn("Original transaction not found for retrievalRef={}", retrievalRef);
            sendError(acquirerChannel, request, ResponseCode.INVALID_TRANSACTION);
            return;
        }
        Transaction original = originalOpt.get();

        if (original.getStatus() != Transaction.Status.APPROVED) {
            log.warn("Cannot reverse transaction in status: {}", original.getStatus());
            sendError(acquirerChannel, request, ResponseCode.DO_NOT_HONOR);
            return;
        }

        Participant issuer = original.getIssuer();
        String correlationKey = PendingRequests.key(acquirerCode, stan);
        CompletableFuture<IsoMessage> future = pendingRequests.register(correlationKey);
        request.set(Field.FORWARDING_INSTITUTION, acquirerCode);

        try {
            participantConnector.send(issuer, request);
            IsoMessage response = future.get(responseTimeoutMs, TimeUnit.MILLISECONDS);

            if (ResponseCode.APPROVED.equals(response.getResponseCode())) {
                original.setStatus(Transaction.Status.REVERSED);
                original.setRespondedAt(Instant.now());
                transactionRepository.save(original);
            }

            acquirerChannel.writeAndFlush(messageFactory.pack(response));
            log.info("Reversal complete: retrievalRef={} RC={}", retrievalRef, response.getResponseCode());

        } catch (TimeoutException e) {
            pendingRequests.cancel(correlationKey);
            sendError(acquirerChannel, request, ResponseCode.ISSUER_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingRequests.cancel(correlationKey);
            sendError(acquirerChannel, request, ResponseCode.SYSTEM_MALFUNCTION);
        } catch (Exception e) {
            pendingRequests.cancel(correlationKey);
            log.error("Reversal error: {}", e.getMessage(), e);
            sendError(acquirerChannel, request, ResponseCode.SYSTEM_MALFUNCTION);
        }
    }

    private void sendError(Channel channel, IsoMessage request, String responseCode) {
        channel.writeAndFlush(messageFactory.pack(messageFactory.buildResponse(request, responseCode)));
    }
}
