package com.network.gateway;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Netty TCP server that listens for ISO 8583 connections from acquirers and issuers.
 * Lifecycle is tied to the Spring application context.
 */
@Component
public class GatewayServer {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    @Value("${gateway.port:8583}")
    private int port;

    private final GatewayChannelInitializer channelInitializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public GatewayServer(GatewayChannelInitializer channelInitializer) {
        this.channelInitializer = channelInitializer;
    }

    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup  = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(channelInitializer);

        ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();
        log.info("ISO 8583 Gateway listening on port {}", port);
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping ISO 8583 Gateway...");
        if (serverChannel != null) serverChannel.close();
        if (workerGroup != null)   workerGroup.shutdownGracefully();
        if (bossGroup  != null)    bossGroup.shutdownGracefully();
    }
}
