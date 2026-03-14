package com.network.transaction;

import com.network.domain.Dispute;
import com.network.domain.Participant;
import com.network.domain.Transaction;
import com.network.gateway.ParticipantConnector;
import com.network.gateway.PendingRequests;
import com.network.iso8583.Field;
import com.network.iso8583.IsoMessage;
import com.network.iso8583.MessageFactory;
import com.network.iso8583.ResponseCode;
import com.network.repository.DisputeRepository;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles ISO 8583 chargeback requests: MTI 0200 with Processing Code starting with "28".
 *
 * Flow: acquirer sends 0200/PC=280000 with DE37 (retrieval ref of original transaction).
 * We look up the original, create a Dispute, forward to the issuer, and await 0210.
 */
@Service
public class ChargebackService {

    private static final Logger log = LoggerFactory.getLogger(ChargebackService.class);

    @Value("${gateway.response-timeout:30000}")
    private long responseTimeoutMs;

    private final MessageFactory        messageFactory;
    private final TransactionRepository transactionRepository;
    private final ParticipantRepository participantRepository;
    private final DisputeRepository     disputeRepository;
    private final ParticipantConnector  participantConnector;
    private final PendingRequests       pendingRequests;

    public ChargebackService(MessageFactory messageFactory,
                             TransactionRepository transactionRepository,
                             ParticipantRepository participantRepository,
                             DisputeRepository disputeRepository,
                             ParticipantConnector participantConnector,
                             PendingRequests pendingRequests) {
        this.messageFactory        = messageFactory;
        this.transactionRepository = transactionRepository;
        this.participantRepository = participantRepository;
        this.disputeRepository     = disputeRepository;
        this.participantConnector  = participantConnector;
        this.pendingRequests       = pendingRequests;
    }

    @Transactional
    public void processChargeback(IsoMessage request, Channel acquirerChannel) {
        String retrievalRef = request.getRetrievalRef();
        String acquirerCode = request.getAcquirerCode();
        String stan         = request.getStan();

        // Look up the original approved transaction
        Optional<Transaction> originalOpt = transactionRepository.findByRetrievalRef(retrievalRef);
        if (originalOpt.isEmpty()) {
            log.warn("Chargeback: original transaction not found for retrievalRef={}", retrievalRef);
            sendError(acquirerChannel, request, ResponseCode.INVALID_TRANSACTION);
            return;
        }
        Transaction original = originalOpt.get();

        if (original.getStatus() != Transaction.Status.APPROVED) {
            log.warn("Chargeback: transaction {} is in status {}, must be APPROVED",
                    original.getId(), original.getStatus());
            sendError(acquirerChannel, request, ResponseCode.DO_NOT_HONOR);
            return;
        }

        Participant issuer    = original.getIssuer();
        Participant initiator = original.getAcquirer();

        // Determine reason code — use DE25 (POS condition code) as a proxy, or default to 10.4
        String reasonCode = request.get(Field.POS_CONDITION_CODE);
        if (reasonCode == null || reasonCode.isBlank()) reasonCode = ChargebackReasonCode.VISA_10_4;

        long chargebackAmount = original.getAmount() != null ? original.getAmount() : 0L;
        String amountField = request.get(Field.AMOUNT);
        if (amountField != null && !amountField.isBlank()) {
            try { chargebackAmount = Long.parseLong(amountField); } catch (NumberFormatException ignored) {}
        }

        // Create dispute record
        Dispute dispute = new Dispute();
        dispute.setTransaction(original);
        dispute.setInitiator(initiator);
        dispute.setReasonCode(reasonCode);
        dispute.setReasonNetwork(Dispute.ReasonNetwork.VISA);
        dispute.setChargebackAmount(chargebackAmount);
        dispute.setStatus(Dispute.Status.PENDING_ISSUER_RESPONSE);
        dispute = disputeRepository.save(dispute);
        final UUID disputeId = dispute.getId();

        String correlationKey = PendingRequests.key(acquirerCode, stan);
        CompletableFuture<IsoMessage> future = pendingRequests.register(correlationKey);
        request.set(Field.FORWARDING_INSTITUTION, acquirerCode);

        try {
            participantConnector.send(issuer, request);
            IsoMessage response = future.get(responseTimeoutMs, TimeUnit.MILLISECONDS);

            Dispute d = disputeRepository.findById(disputeId).orElseThrow();
            d.setRespondedAt(Instant.now());
            if (ResponseCode.APPROVED.equals(response.getResponseCode())) {
                d.setStatus(Dispute.Status.ACCEPTED);
                log.info("Chargeback ACCEPTED: retrievalRef={} disputeId={}", retrievalRef, disputeId);
            } else {
                d.setStatus(Dispute.Status.REJECTED_BY_ISSUER);
                log.info("Chargeback REJECTED by issuer: retrievalRef={} RC={}", retrievalRef,
                        response.getResponseCode());
            }
            disputeRepository.save(d);

            acquirerChannel.writeAndFlush(messageFactory.pack(response));

        } catch (TimeoutException e) {
            pendingRequests.cancel(correlationKey);
            revertDisputeToInitiated(disputeId);
            sendError(acquirerChannel, request, ResponseCode.ISSUER_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingRequests.cancel(correlationKey);
            revertDisputeToInitiated(disputeId);
            sendError(acquirerChannel, request, ResponseCode.SYSTEM_MALFUNCTION);
        } catch (Exception e) {
            pendingRequests.cancel(correlationKey);
            log.error("Chargeback error: {}", e.getMessage(), e);
            revertDisputeToInitiated(disputeId);
            sendError(acquirerChannel, request, ResponseCode.SYSTEM_MALFUNCTION);
        }
    }

    private void revertDisputeToInitiated(UUID disputeId) {
        disputeRepository.findById(disputeId).ifPresent(d -> {
            d.setStatus(Dispute.Status.INITIATED);
            disputeRepository.save(d);
        });
    }

    private void sendError(Channel channel, IsoMessage request, String responseCode) {
        channel.writeAndFlush(messageFactory.pack(messageFactory.buildResponse(request, responseCode)));
    }
}
