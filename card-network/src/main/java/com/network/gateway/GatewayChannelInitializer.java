package com.network.gateway;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GatewayChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final GatewayMessageHandler messageHandler;

    public GatewayChannelInitializer(GatewayMessageHandler messageHandler) {
        this.messageHandler = messageHandler;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // Idle detection: close channels silent for more than 5 minutes
        pipeline.addLast(new IdleStateHandler(300, 0, 0, TimeUnit.SECONDS));

        // Framing: 2-byte length prefix
        pipeline.addLast(new Iso8583FrameDecoder());
        pipeline.addLast(new Iso8583Encoder());

        // Business logic handler (shared singleton)
        pipeline.addLast(messageHandler);
    }
}
