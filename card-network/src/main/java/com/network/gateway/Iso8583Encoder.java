package com.network.gateway;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Prepends a 2-byte big-endian length header to outbound ISO 8583 byte arrays.
 */
public class Iso8583Encoder extends MessageToByteEncoder<byte[]> {

    @Override
    protected void encode(ChannelHandlerContext ctx, byte[] msg, ByteBuf out) {
        out.writeShort(msg.length);
        out.writeBytes(msg);
    }
}
