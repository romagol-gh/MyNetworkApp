package com.network.gateway;

import com.network.domain.Participant;
import com.network.iso8583.IsoMessage;
import com.network.iso8583.MessageFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manages outbound TCP connections to issuer banks and forwards ISO 8583 messages to them.
 *
 * Uses an anonymous ChannelInitializer (not the Spring-managed GatewayChannelInitializer)
 * to avoid Spring CGLIB proxy interaction with Netty's final channelRegistered() method.
 * The cycle AuthorizationService → ParticipantConnector → GatewayMessageHandler →
 * AuthorizationService is broken by @Lazy on GatewayMessageHandler.
 */
@Component
public class ParticipantConnector {

    private static final Logger log = LoggerFactory.getLogger(ParticipantConnector.class);

    private final SessionRegistry sessionRegistry;
    private final GatewayMessageHandler gatewayMessageHandler;
    private final MessageFactory messageFactory;

    private EventLoopGroup clientGroup;

    public ParticipantConnector(SessionRegistry sessionRegistry,
                                @Lazy GatewayMessageHandler gatewayMessageHandler,
                                MessageFactory messageFactory) {
        this.sessionRegistry      = sessionRegistry;
        this.gatewayMessageHandler = gatewayMessageHandler;
        this.messageFactory       = messageFactory;
    }

    @PostConstruct
    public void init() {
        clientGroup = new NioEventLoopGroup();
    }

    @PreDestroy
    public void shutdown() {
        if (clientGroup != null) clientGroup.shutdownGracefully();
    }

    /**
     * Send an ISO 8583 message to an issuer.
     * If the issuer is already connected, reuse the channel.
     * Otherwise, establish a new connection first.
     */
    public void send(Participant issuer, IsoMessage msg) {
        Optional<Channel> existing = sessionRegistry.getChannel(issuer.getCode());
        if (existing.isPresent()) {
            write(existing.get(), msg);
            return;
        }

        if (issuer.getHost() == null || issuer.getPort() == null) {
            throw new IllegalStateException("Issuer " + issuer.getCode() + " has no host/port configured");
        }

        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new IdleStateHandler(300, 0, 0, TimeUnit.SECONDS))
                                    .addLast(new Iso8583FrameDecoder())
                                    .addLast(new Iso8583Encoder())
                                    .addLast(gatewayMessageHandler);
                        }
                    });

            ChannelFuture future = bootstrap.connect(issuer.getHost(), issuer.getPort()).sync();
            Channel ch = future.channel();
            sessionRegistry.register(issuer.getCode(), ch);
            write(ch, msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while connecting to issuer " + issuer.getCode(), e);
        }
    }

    private void write(Channel ch, IsoMessage msg) {
        ch.writeAndFlush(messageFactory.pack(msg));
        log.debug("Sent MTI={} to {}", msg.getMti(), ch.remoteAddress());
    }
}
