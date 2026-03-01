package com.network.gateway;

import com.network.iso8583.Field;
import com.network.iso8583.IsoMessage;
import com.network.iso8583.MessageFactory;
import com.network.iso8583.MessageType;
import com.network.iso8583.ResponseCode;
import com.network.transaction.AuthorizationService;
import com.network.transaction.FinancialService;
import com.network.transaction.NetworkManagementService;
import com.network.transaction.ReversalService;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Netty inbound handler: parses ISO 8583 frames and dispatches to the appropriate service.
 * Marked @Sharable because all mutable state lives in injected Spring beans.
 */
@Component
@ChannelHandler.Sharable
public class GatewayMessageHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(GatewayMessageHandler.class);

    private final MessageFactory messageFactory;
    private final SessionRegistry sessionRegistry;
    private final PendingRequests pendingRequests;
    private final NetworkManagementService networkMgmtService;
    private final AuthorizationService authorizationService;
    private final FinancialService financialService;
    private final ReversalService reversalService;

    public GatewayMessageHandler(
            MessageFactory messageFactory,
            SessionRegistry sessionRegistry,
            PendingRequests pendingRequests,
            NetworkManagementService networkMgmtService,
            AuthorizationService authorizationService,
            FinancialService financialService,
            ReversalService reversalService) {
        this.messageFactory      = messageFactory;
        this.sessionRegistry     = sessionRegistry;
        this.pendingRequests     = pendingRequests;
        this.networkMgmtService  = networkMgmtService;
        this.authorizationService = authorizationService;
        this.financialService    = financialService;
        this.reversalService     = reversalService;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        try {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);

            IsoMessage isoMsg = messageFactory.unpack(bytes);
            String mti = isoMsg.getMti();
            log.debug("Received MTI={} from {}", mti, ctx.channel().remoteAddress());

            dispatch(ctx, isoMsg, mti);
        } catch (Exception e) {
            log.error("Error processing message from {}: {}", ctx.channel().remoteAddress(), e.getMessage(), e);
            ctx.channel().close();
        } finally {
            buf.release();
        }
    }

    private void dispatch(ChannelHandlerContext ctx, IsoMessage msg, String mti) {
        switch (mti) {
            case "0800" -> networkMgmtService.handle(msg, ctx.channel());
            case "0100" -> authorizationService.authorize(msg, ctx.channel());
            case "0200" -> financialService.process(msg, ctx.channel());
            case "0400" -> reversalService.reverse(msg, ctx.channel());

            // Responses from issuers — complete the pending future
            case "0110", "0210", "0420" -> handleIssuerResponse(msg);

            default -> {
                log.warn("Unhandled MTI: {}", mti);
                sendError(ctx, msg);
            }
        }
    }

    private void handleIssuerResponse(IsoMessage response) {
        String stan           = response.getStan();
        String acquirerCode   = response.get(Field.FORWARDING_INSTITUTION);
        if (acquirerCode == null) acquirerCode = response.getAcquirerCode();
        String correlationKey = PendingRequests.key(acquirerCode != null ? acquirerCode : "", stan);

        boolean completed = pendingRequests.complete(correlationKey, response);
        if (!completed) {
            log.warn("No pending request for correlation key: {}", correlationKey);
        }
    }

    private void sendError(ChannelHandlerContext ctx, IsoMessage request) {
        try {
            IsoMessage resp = messageFactory.buildResponse(request, ResponseCode.SYSTEM_MALFUNCTION);
            ctx.writeAndFlush(messageFactory.pack(resp));
        } catch (Exception e) {
            log.error("Failed to send error response", e);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        sessionRegistry.deregister(ctx.channel());
        log.info("Channel disconnected: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel exception from {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }
}
