package com.network.gateway;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active Netty channels keyed by participant institution code (DE32/DE33).
 * Both acquirers (inbound) and issuers (outbound) are registered here.
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    // participantCode → Channel
    private final Map<String, Channel> byCode = new ConcurrentHashMap<>();
    // channelId → participantCode (for cleanup on disconnect)
    private final Map<ChannelId, String> byChannel = new ConcurrentHashMap<>();

    public void register(String participantCode, Channel channel) {
        byCode.put(participantCode, channel);
        byChannel.put(channel.id(), participantCode);
        log.info("Registered session: {} ({})", participantCode, channel.remoteAddress());
    }

    public void deregister(Channel channel) {
        String code = byChannel.remove(channel.id());
        if (code != null) {
            byCode.remove(code);
            log.info("Deregistered session: {}", code);
        }
    }

    public Optional<Channel> getChannel(String participantCode) {
        Channel ch = byCode.get(participantCode);
        return (ch != null && ch.isActive()) ? Optional.of(ch) : Optional.empty();
    }

    public boolean isConnected(String participantCode) {
        Channel ch = byCode.get(participantCode);
        return ch != null && ch.isActive();
    }

    public int size() { return byCode.size(); }
}
