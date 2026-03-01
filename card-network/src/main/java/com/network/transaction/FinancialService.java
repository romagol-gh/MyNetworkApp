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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles ISO 8583 Financial Transaction messages (0200/0210).
 * Same flow as authorization but for captured/forced transactions.
 */
@Service
public class FinancialService {

    private static final Logger log = LoggerFactory.getLogger(FinancialService.class);

    @Value("${gateway.response-timeout:30000}")
    private long responseTimeoutMs;

    private final MessageFactory        messageFactory;
    private final BinRouter             binRouter;
    private final ParticipantConnector  participantConnector;
    private final PendingRequests       pendingRequests;
    private final ParticipantRepository participantRepository;
    private final TransactionLogger     transactionLogger;
    private final FraudDetectionService fraudDetectionService;

    public FinancialService(MessageFactory messageFactory, BinRouter binRouter,
                            ParticipantConnector participantConnector, PendingRequests pendingRequests,
                            ParticipantRepository participantRepository, TransactionLogger transactionLogger,
                            FraudDetectionService fraudDetectionService) {
        this.messageFactory       = messageFactory;
        this.binRouter            = binRouter;
        this.participantConnector = participantConnector;
        this.pendingRequests      = pendingRequests;
        this.participantRepository = participantRepository;
        this.transactionLogger    = transactionLogger;
        this.fraudDetectionService = fraudDetectionService;
    }

    public void process(IsoMessage request, Channel acquirerChannel) {
        String acquirerCode = request.getAcquirerCode();
        String stan         = request.getStan();
        String pan          = request.getPan();

        Optional<Participant> acquirerOpt = participantRepository.findByCode(acquirerCode);
        if (acquirerOpt.isEmpty()) { sendError(acquirerChannel, request, ResponseCode.ROUTING_ERROR); return; }

        Optional<Participant> issuerOpt = binRouter.route(pan);
        if (issuerOpt.isEmpty()) { sendError(acquirerChannel, request, ResponseCode.INVALID_CARD_NUMBER); return; }

        Participant acquirer = acquirerOpt.get();
        Participant issuer   = issuerOpt.get();

        FraudResult fraudResult = fraudDetectionService.evaluate(request);
        if (fraudResult.action() == FraudAction.DECLINE) {
            Transaction txn = transactionLogger.logIncoming(request, acquirer, issuer);
            txn.setFraudFlagged(true);
            txn.setStatus(Transaction.Status.DECLINED);
            fraudDetectionService.createAlert(txn, fraudResult);
            sendError(acquirerChannel, request, ResponseCode.SUSPECTED_FRAUD);
            return;
        }

        Transaction txn = transactionLogger.logIncoming(request, acquirer, issuer);
        if (fraudResult.action() == FraudAction.FLAG) txn.setFraudFlagged(true);

        String correlationKey = PendingRequests.key(acquirerCode, stan);
        CompletableFuture<IsoMessage> future = pendingRequests.register(correlationKey);
        request.set(Field.FORWARDING_INSTITUTION, acquirerCode);

        try {
            participantConnector.send(issuer, request);
            IsoMessage response = future.get(responseTimeoutMs, TimeUnit.MILLISECONDS);
            transactionLogger.logResponse(txn, response);
            if (fraudResult.action() == FraudAction.FLAG) fraudDetectionService.createAlert(txn, fraudResult);
            acquirerChannel.writeAndFlush(messageFactory.pack(response));
        } catch (TimeoutException e) {
            pendingRequests.cancel(correlationKey);
            sendError(acquirerChannel, request, ResponseCode.ISSUER_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingRequests.cancel(correlationKey);
            sendError(acquirerChannel, request, ResponseCode.SYSTEM_MALFUNCTION);
        } catch (Exception e) {
            pendingRequests.cancel(correlationKey);
            log.error("Financial processing error: {}", e.getMessage(), e);
            sendError(acquirerChannel, request, ResponseCode.SYSTEM_MALFUNCTION);
        }
    }

    private void sendError(Channel channel, IsoMessage request, String responseCode) {
        channel.writeAndFlush(messageFactory.pack(messageFactory.buildResponse(request, responseCode)));
    }
}
