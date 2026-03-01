package com.network.transaction;

import com.network.domain.Participant;
import com.network.gateway.SessionRegistry;
import com.network.iso8583.Field;
import com.network.iso8583.IsoMessage;
import com.network.iso8583.MessageFactory;
import com.network.iso8583.ResponseCode;
import com.network.repository.ParticipantRepository;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Handles ISO 8583 Network Management messages (0800/0810):
 *   - NMC 001 = Sign-On
 *   - NMC 002 = Sign-Off
 *   - NMC 301 = Echo Test
 */
@Service
public class NetworkManagementService {

    private static final Logger log = LoggerFactory.getLogger(NetworkManagementService.class);

    private static final String SIGN_ON  = "001";
    private static final String SIGN_OFF = "002";
    private static final String ECHO     = "301";

    private final MessageFactory messageFactory;
    private final SessionRegistry sessionRegistry;
    private final ParticipantRepository participantRepository;

    public NetworkManagementService(MessageFactory messageFactory,
                                    SessionRegistry sessionRegistry,
                                    ParticipantRepository participantRepository) {
        this.messageFactory       = messageFactory;
        this.sessionRegistry      = sessionRegistry;
        this.participantRepository = participantRepository;
    }

    public void handle(IsoMessage msg, Channel channel) {
        String nmc = msg.get(Field.NETWORK_MGMT_CODE);
        String acquirerCode = msg.getAcquirerCode();

        log.info("NMC={} from acquirerCode={}", nmc, acquirerCode);

        IsoMessage resp;
        if (SIGN_ON.equals(nmc)) {
            resp = handleSignOn(msg, channel, acquirerCode);
        } else if (SIGN_OFF.equals(nmc)) {
            resp = handleSignOff(msg, channel, acquirerCode);
        } else if (ECHO.equals(nmc)) {
            resp = messageFactory.buildNetworkResponse(msg, ResponseCode.APPROVED);
        } else {
            log.warn("Unknown NMC: {}", nmc);
            resp = messageFactory.buildNetworkResponse(msg, ResponseCode.INVALID_TRANSACTION);
        }

        channel.writeAndFlush(messageFactory.pack(resp));
    }

    private IsoMessage handleSignOn(IsoMessage msg, Channel channel, String acquirerCode) {
        Optional<Participant> participant = participantRepository.findByCode(acquirerCode);
        if (participant.isEmpty() || participant.get().getStatus() == Participant.Status.INACTIVE) {
            log.warn("Sign-on rejected for unknown/inactive participant: {}", acquirerCode);
            return messageFactory.buildNetworkResponse(msg, ResponseCode.DO_NOT_HONOR);
        }
        sessionRegistry.register(acquirerCode, channel);
        log.info("Participant signed on: {} ({})", participant.get().getName(), acquirerCode);
        return messageFactory.buildNetworkResponse(msg, ResponseCode.APPROVED);
    }

    private IsoMessage handleSignOff(IsoMessage msg, Channel channel, String acquirerCode) {
        sessionRegistry.deregister(channel);
        log.info("Participant signed off: {}", acquirerCode);
        return messageFactory.buildNetworkResponse(msg, ResponseCode.APPROVED);
    }
}
