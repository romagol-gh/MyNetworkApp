package com.network.transaction;

import com.network.domain.Participant;
import com.network.domain.Transaction;
import com.network.fraud.FraudAction;
import com.network.fraud.FraudDetectionService;
import com.network.fraud.FraudResult;
import com.network.gateway.ParticipantConnector;
import com.network.gateway.PendingRequests;
import com.network.iso8583.Field;
import com.network.iso8583.IsoMessage;
import com.network.iso8583.MessageFactory;
import com.network.iso8583.ResponseCode;
import com.network.repository.ParticipantRepository;
import com.network.routing.BinRouter;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles ISO 8583 Authorization (0100/0110):
 *   acquirer → network → fraud check → issuer → network → acquirer
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    @Value("${gateway.response-timeout:30000}")
    private long responseTimeoutMs;

    private final MessageFactory           messageFactory;
    private final BinRouter                binRouter;
    private final ParticipantConnector     participantConnector;
    private final PendingRequests          pendingRequests;
    private final ParticipantRepository    participantRepository;
    private final TransactionLogger        transactionLogger;
    private final FraudDetectionService    fraudDetectionService;
    private final AgentRegistrationService agentRegistrationService;

    public AuthorizationService(
            MessageFactory messageFactory,
            BinRouter binRouter,
            ParticipantConnector participantConnector,
            PendingRequests pendingRequests,
            ParticipantRepository participantRepository,
            TransactionLogger transactionLogger,
            FraudDetectionService fraudDetectionService,
            AgentRegistrationService agentRegistrationService) {
        this.messageFactory           = messageFactory;
        this.binRouter                = binRouter;
        this.participantConnector     = participantConnector;
        this.pendingRequests          = pendingRequests;
        this.participantRepository    = participantRepository;
        this.transactionLogger        = transactionLogger;
        this.fraudDetectionService    = fraudDetectionService;
        this.agentRegistrationService = agentRegistrationService;
    }

    public void authorize(IsoMessage request, Channel acquirerChannel) {
        String acquirerCode = request.getAcquirerCode();
        String stan         = request.getStan();
        String pan          = request.getPan();

        // Validate required fields
        if (pan == null || stan == null || acquirerCode == null) {
            log.warn("Missing required fields: PAN={}, STAN={}, acquirer={}", pan, stan, acquirerCode);
            sendError(acquirerChannel, request, ResponseCode.INVALID_TRANSACTION);
            return;
        }

        // Resolve acquirer participant
        Optional<Participant> acquirerOpt = participantRepository.findByCode(acquirerCode);
        if (acquirerOpt.isEmpty()) {
            sendError(acquirerChannel, request, ResponseCode.ROUTING_ERROR);
            return;
        }
        Participant acquirer = acquirerOpt.get();

        // Route to issuer by BIN
        Optional<Participant> issuerOpt = binRouter.route(pan);
        if (issuerOpt.isEmpty()) {
            log.warn("No BIN match for PAN prefix: {}", pan.substring(0, Math.min(6, pan.length())));
            sendError(acquirerChannel, request, ResponseCode.NO_SUCH_ISSUER);
            return;
        }
        Participant issuer = issuerOpt.get();

        // Parse DE48 agent context (if present)
        AgentContext agentCtx = null;
        String de48 = request.get(Field.ADDITIONAL_DATA);
        if (de48 != null && !de48.isBlank()) {
            agentCtx = AgentContext.parse(de48);
        }

        // Agent spend controls — enforce before fraud check; short-circuit if violated
        if (agentCtx != null) {
            long amount = parseAmountLong(request.getAmount());
            String mcc  = request.getMcc();
            try {
                agentRegistrationService.checkSpendControls(agentCtx, amount, mcc);
            } catch (SpendControlException e) {
                log.info("Agent spend control rejected STAN={}: {}", stan, e.getMessage());
                sendError(acquirerChannel, request, e.getResponseCode());
                return;
            }
        }

        // Fraud check — before touching the issuer
        FraudResult fraudResult = fraudDetectionService.evaluate(request);
        if (fraudResult.action() == FraudAction.DECLINE) {
            log.info("Transaction DECLINED by fraud engine: score={}, rules={}", fraudResult.score(), fraudResult.triggeredRuleNames());
            Transaction txn = transactionLogger.logIncoming(request, acquirer, issuer, agentCtx);
            txn.setFraudFlagged(true);
            txn.setStatus(Transaction.Status.DECLINED);
            transactionLogger.logResponse(txn, messageFactory.buildResponse(request, ResponseCode.SUSPECTED_FRAUD));
            sendError(acquirerChannel, request, ResponseCode.SUSPECTED_FRAUD);
            fraudDetectionService.createAlert(txn, fraudResult);
            return;
        }

        // Log the incoming transaction
        Transaction txn = transactionLogger.logIncoming(request, acquirer, issuer, agentCtx);
        if (fraudResult.action() == FraudAction.FLAG) {
            txn.setFraudFlagged(true);
        }

        // Check issuer is connected
        if (!isIssuerAvailable(issuer)) {
            log.warn("Issuer unavailable: {}", issuer.getCode());
            transactionLogger.logResponse(txn,
                    messageFactory.buildResponse(request, ResponseCode.ISSUER_UNAVAILABLE));
            sendError(acquirerChannel, request, ResponseCode.ISSUER_UNAVAILABLE);
            return;
        }

        // Register pending request and forward to issuer
        String correlationKey = PendingRequests.key(acquirerCode, stan);
        CompletableFuture<IsoMessage> future = pendingRequests.register(correlationKey);

        // Add forwarding institution field before sending to issuer
        request.set(Field.FORWARDING_INSTITUTION, acquirerCode);

        try {
            participantConnector.send(issuer, request);

            // Await issuer response
            IsoMessage response = future.get(responseTimeoutMs, TimeUnit.MILLISECONDS);

            // Generate auth ID if approved
            if (ResponseCode.APPROVED.equals(response.getResponseCode()) && !response.has(Field.AUTH_ID_RESPONSE)) {
                response.set(Field.AUTH_ID_RESPONSE, generateAuthId());
            }

            // Echo DE48 agent context back to acquirer
            if (de48 != null && !de48.isBlank()) {
                response.set(Field.ADDITIONAL_DATA, de48);
            }

            transactionLogger.logResponse(txn, response);

            if (fraudResult.action() == FraudAction.FLAG) {
                fraudDetectionService.createAlert(txn, fraudResult);
            }

            // Record agent activity for daily limit tracking
            if (agentCtx != null && agentCtx.getAgentId() != null
                    && ResponseCode.APPROVED.equals(response.getResponseCode())) {
                agentRegistrationService.recordActivity(agentCtx.getAgentId(), parseAmountLong(request.getAmount()));
            }

            acquirerChannel.writeAndFlush(messageFactory.pack(response));
            log.info("Authorization complete: STAN={} RC={}", stan, response.getResponseCode());

        } catch (TimeoutException e) {
            pendingRequests.cancel(correlationKey);
            log.error("Issuer timeout for STAN={}", stan);
            transactionLogger.logResponse(txn,
                    messageFactory.buildResponse(request, ResponseCode.ISSUER_UNAVAILABLE));
            sendError(acquirerChannel, request, ResponseCode.ISSUER_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingRequests.cancel(correlationKey);
            sendError(acquirerChannel, request, ResponseCode.SYSTEM_MALFUNCTION);
        } catch (Exception e) {
            pendingRequests.cancel(correlationKey);
            log.error("Authorization error for STAN={}: {}", stan, e.getMessage(), e);
            sendError(acquirerChannel, request, ResponseCode.SYSTEM_MALFUNCTION);
        }
    }

    private boolean isIssuerAvailable(Participant issuer) {
        // Issuer is available if already connected OR has host/port configured (we'll connect on demand)
        return issuer.getHost() != null && issuer.getPort() != null;
    }

    private void sendError(Channel channel, IsoMessage request, String responseCode) {
        IsoMessage resp = messageFactory.buildResponse(request, responseCode);
        channel.writeAndFlush(messageFactory.pack(resp));
    }

    private String generateAuthId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private long parseAmountLong(String amount) {
        if (amount == null || amount.isBlank()) return 0L;
        try { return Long.parseLong(amount.trim()); } catch (NumberFormatException e) { return 0L; }
    }
}
