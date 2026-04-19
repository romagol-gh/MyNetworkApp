package com.network.transaction;

import com.network.domain.AgentRegistration;
import com.network.domain.Participant;
import com.network.iso8583.Field;
import com.network.iso8583.IsoMessage;
import com.network.iso8583.MessageFactory;
import com.network.iso8583.ResponseCode;
import com.network.repository.AgentRegistrationRepository;
import com.network.repository.ParticipantRepository;
import com.network.repository.TransactionRepository;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Handles ISO 8583 NMC 080/081 for agent sign-on/sign-off,
 * and enforces per-transaction spend controls for agentic commerce.
 *
 * DB operations are wrapped in explicit TransactionTemplate calls so the
 * transaction commits before the ISO 8583 response is flushed to the channel
 * (avoids a race where the client reads the response before the commit lands).
 */
@Service
public class AgentRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistrationService.class);

    private final AgentRegistrationRepository agentRepository;
    private final ParticipantRepository participantRepository;
    private final TransactionRepository transactionRepository;
    private final MessageFactory messageFactory;
    private final TransactionTemplate txTemplate;

    public AgentRegistrationService(AgentRegistrationRepository agentRepository,
                                    ParticipantRepository participantRepository,
                                    TransactionRepository transactionRepository,
                                    MessageFactory messageFactory,
                                    PlatformTransactionManager txManager) {
        this.agentRepository      = agentRepository;
        this.participantRepository = participantRepository;
        this.transactionRepository = transactionRepository;
        this.messageFactory       = messageFactory;
        this.txTemplate           = new TransactionTemplate(txManager);
    }

    public void processSignOn(IsoMessage msg, Channel channel) {
        String de48 = msg.get(Field.ADDITIONAL_DATA);
        AgentContext ctx = de48 != null ? AgentContext.parse(de48) : null;
        if (ctx == null || ctx.getAgentId() == null) {
            log.warn("Agent sign-on missing DE48 agent context");
            channel.writeAndFlush(messageFactory.pack(
                    messageFactory.buildNetworkResponse(msg, ResponseCode.INVALID_TRANSACTION)));
            return;
        }

        String acquirerCode = msg.getAcquirerCode();
        Optional<Participant> participant = participantRepository.findByCode(acquirerCode);
        if (participant.isEmpty()) {
            log.warn("Agent sign-on: unknown participant {}", acquirerCode);
            channel.writeAndFlush(messageFactory.pack(
                    messageFactory.buildNetworkResponse(msg, ResponseCode.DO_NOT_HONOR)));
            return;
        }

        // Execute DB work inside explicit transaction — commits before writeAndFlush
        AgentContext finalCtx = ctx;
        Participant finalParticipant = participant.get();
        txTemplate.execute(status -> {
            AgentRegistration reg = agentRepository.findByAgentId(finalCtx.getAgentId())
                    .orElseGet(AgentRegistration::new);
            reg.setAgentId(finalCtx.getAgentId());
            reg.setParticipant(finalParticipant);
            reg.setStatus(AgentRegistration.Status.ACTIVE);
            reg.setLastSeenAt(Instant.now());
            if (!finalCtx.getMccScope().isEmpty()) {
                reg.setMccScope(String.join(",", finalCtx.getMccScope()));
            }
            if (finalCtx.getPerTxnLimit() != null) {
                reg.setPerTxnLimit(finalCtx.getPerTxnLimit());
            }
            if (finalCtx.getTimeWindow() != null) {
                reg.setTimeWindow(finalCtx.getTimeWindow());
            }
            agentRepository.save(reg);
            return null;
        });

        log.info("Agent signed on: agentId={} participant={}", ctx.getAgentId(), acquirerCode);
        channel.writeAndFlush(messageFactory.pack(
                messageFactory.buildNetworkResponse(msg, ResponseCode.APPROVED)));
    }

    public void processSignOff(IsoMessage msg, Channel channel) {
        String de48 = msg.get(Field.ADDITIONAL_DATA);
        AgentContext ctx = de48 != null ? AgentContext.parse(de48) : null;
        if (ctx == null || ctx.getAgentId() == null) {
            channel.writeAndFlush(messageFactory.pack(
                    messageFactory.buildNetworkResponse(msg, ResponseCode.INVALID_TRANSACTION)));
            return;
        }

        // Execute DB work inside explicit transaction — commits before writeAndFlush
        String agentId = ctx.getAgentId();
        txTemplate.execute(status -> {
            agentRepository.findByAgentId(agentId).ifPresent(reg -> {
                reg.setStatus(AgentRegistration.Status.INACTIVE);
                reg.setLastSeenAt(Instant.now());
                agentRepository.save(reg);
            });
            return null;
        });

        log.info("Agent signed off: agentId={}", agentId);
        channel.writeAndFlush(messageFactory.pack(
                messageFactory.buildNetworkResponse(msg, ResponseCode.APPROVED)));
    }

    /**
     * Enforce spend controls from DE48 context and agent registration.
     * Throws SpendControlException with the appropriate response code if any limit is violated.
     */
    public void checkSpendControls(AgentContext ctx, long amount, String mcc) {
        if (ctx == null) return;

        // Per-transaction cap from DE48
        if (ctx.getPerTxnLimit() != null && amount > ctx.getPerTxnLimit()) {
            throw new SpendControlException(ResponseCode.EXCEEDS_AMOUNT_LIMIT,
                    "Agent per-txn limit exceeded: " + amount + " > " + ctx.getPerTxnLimit());
        }

        // Daily rolling limit and MCC scope from agent registration record
        if (ctx.getAgentId() != null) {
            agentRepository.findByAgentId(ctx.getAgentId()).ifPresent(reg -> {
                // Daily limit
                if (reg.getDailyLimit() != null) {
                    Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
                    long dailyUsed = transactionRepository.sumApprovedAmountByAgentIdSince(ctx.getAgentId(), dayStart);
                    if (dailyUsed + amount > reg.getDailyLimit()) {
                        throw new SpendControlException(ResponseCode.EXCEEDS_AMOUNT_LIMIT,
                                "Agent daily limit exceeded: used=" + dailyUsed + " adding=" + amount + " limit=" + reg.getDailyLimit());
                    }
                }

                // MCC scope from registration (registration wins if set)
                if (reg.getMccScope() != null && !reg.getMccScope().isBlank() && mcc != null) {
                    String[] allowed = reg.getMccScope().split(",");
                    boolean permitted = false;
                    for (String allowedMcc : allowed) {
                        if (allowedMcc.trim().equals(mcc)) { permitted = true; break; }
                    }
                    if (!permitted) {
                        throw new SpendControlException(ResponseCode.TRANSACTION_NOT_PERMITTED,
                                "Agent MCC scope violation: mcc=" + mcc + " not in " + reg.getMccScope());
                    }
                }
            });
        }

        // MCC scope from DE48 (checked if registration has no scope set)
        if (!ctx.getMccScope().isEmpty() && mcc != null) {
            if (!ctx.getMccScope().contains(mcc)) {
                throw new SpendControlException(ResponseCode.TRANSACTION_NOT_PERMITTED,
                        "Agent DE48 MCC scope violation: mcc=" + mcc + " not in " + ctx.getMccScope());
            }
        }

        // Time window
        if (ctx.getTimeWindow() != null) {
            checkTimeWindow(ctx.getTimeWindow());
        }
    }

    public void recordActivity(String agentId, long amount) {
        txTemplate.execute(status -> {
            agentRepository.findByAgentId(agentId).ifPresent(reg -> {
                reg.setLastSeenAt(Instant.now());
                agentRepository.save(reg);
            });
            return null;
        });
    }

    private void checkTimeWindow(String timeWindow) {
        try {
            String[] parts = timeWindow.split("-");
            if (parts.length != 2) return;
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
            LocalTime start = LocalTime.parse(parts[0].trim(), fmt);
            LocalTime end   = LocalTime.parse(parts[1].trim(), fmt);
            LocalTime now   = LocalTime.now();
            if (now.isBefore(start) || now.isAfter(end)) {
                throw new SpendControlException(ResponseCode.EXPIRED_CARD,
                        "Agent time window violation: " + now + " not in " + timeWindow);
            }
        } catch (SpendControlException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not parse agent time window '{}': {}", timeWindow, e.getMessage());
        }
    }
}
