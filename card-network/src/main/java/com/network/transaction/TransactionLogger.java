package com.network.transaction;

import com.network.domain.Participant;
import com.network.domain.Transaction;
import com.network.iso8583.Field;
import com.network.iso8583.IsoMessage;
import com.network.repository.TransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists ISO 8583 transaction events to the database.
 */
@Component
public class TransactionLogger {

    private final TransactionRepository transactionRepository;

    public TransactionLogger(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public Transaction logIncoming(IsoMessage msg, Participant acquirer, Participant issuer) {
        return logIncoming(msg, acquirer, issuer, null);
    }

    @Transactional
    public Transaction logIncoming(IsoMessage msg, Participant acquirer, Participant issuer,
                                   AgentContext agentCtx) {
        Transaction txn = new Transaction();
        txn.setStan(msg.getStan());
        txn.setRetrievalRef(msg.get(Field.RETRIEVAL_REF));
        txn.setMessageType(msg.getMti());
        txn.setProcessingCode(msg.getProcessingCode());
        txn.setPanMasked(maskPan(msg.getPan()));
        txn.setAmount(parseAmount(msg.getAmount()));
        txn.setCurrency(msg.getCurrency());
        txn.setAcquirer(acquirer);
        txn.setIssuer(issuer);
        txn.setMcc(msg.get(Field.MCC));
        txn.setStatus(Transaction.Status.PENDING);
        if (agentCtx != null) {
            txn.setAgentId(agentCtx.getAgentId());
            txn.setIntentHash(agentCtx.getIntentHash());
            txn.setAgentChain(agentCtx.getAgentChain());
        }
        return transactionRepository.save(txn);
    }

    @Transactional
    public void logResponse(Transaction txn, IsoMessage response) {
        String rc = response.getResponseCode();
        txn.setResponseCode(rc);
        txn.setAuthId(response.get(Field.AUTH_ID_RESPONSE));
        txn.setRespondedAt(Instant.now());
        txn.setStatus("00".equals(rc) ? Transaction.Status.APPROVED : Transaction.Status.DECLINED);
        transactionRepository.save(txn);
    }

    /** Mask PAN: keep first 6 and last 4 digits, replace middle with asterisks */
    public static String maskPan(String pan) {
        if (pan == null || pan.length() < 10) return pan;
        return pan.substring(0, 6) + "****" + pan.substring(pan.length() - 4);
    }

    private Long parseAmount(String amount) {
        if (amount == null || amount.isBlank()) return null;
        try {
            return Long.parseLong(amount.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
