package com.network.iso8583;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOPackager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Builds and parses ISO 8583 messages using jPOS.
 */
@Component
public class MessageFactory {

    private static final DateTimeFormatter TX_DATETIME = DateTimeFormatter.ofPattern("MMddHHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_TIME  = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_DATE  = DateTimeFormatter.ofPattern("MMdd").withZone(ZoneOffset.UTC);

    private final ISOPackager packager;

    public MessageFactory(ISOPackager packager) {
        this.packager = packager;
    }

    public byte[] pack(IsoMessage msg) {
        try {
            msg.inner().setPackager(packager);
            return msg.inner().pack();
        } catch (ISOException e) {
            throw new IllegalStateException("Failed to pack ISO message", e);
        }
    }

    public IsoMessage unpack(byte[] bytes) {
        try {
            ISOMsg raw = new ISOMsg();
            raw.setPackager(packager);
            raw.unpack(bytes);
            return new IsoMessage(raw);
        } catch (ISOException e) {
            throw new IllegalArgumentException("Failed to unpack ISO message", e);
        }
    }

    /** Build a response from a request, copying key fields and setting the response MTI */
    public IsoMessage buildResponse(IsoMessage request, String responseCode) {
        try {
            ISOMsg resp = new ISOMsg();
            resp.setPackager(packager);
            resp.setMTI(MessageType.responseMti(request.getMti()));

            // Copy standard echo fields
            copyIfPresent(request, resp, Field.STAN);
            copyIfPresent(request, resp, Field.PROCESSING_CODE);
            copyIfPresent(request, resp, Field.AMOUNT);
            copyIfPresent(request, resp, Field.CURRENCY);
            copyIfPresent(request, resp, Field.RETRIEVAL_REF);
            copyIfPresent(request, resp, Field.ACQUIRING_INSTITUTION);
            copyIfPresent(request, resp, Field.TERMINAL_ID);
            copyIfPresent(request, resp, Field.MERCHANT_ID);

            Instant now = Instant.now();
            resp.set(Field.TRANSMISSION_DATETIME, TX_DATETIME.format(now));
            resp.set(Field.LOCAL_TIME, LOCAL_TIME.format(now));
            resp.set(Field.LOCAL_DATE, LOCAL_DATE.format(now));
            resp.set(Field.RESPONSE_CODE, responseCode);

            return new IsoMessage(resp);
        } catch (ISOException e) {
            throw new IllegalStateException("Failed to build response", e);
        }
    }

    /** Build a network management response (0810) */
    public IsoMessage buildNetworkResponse(IsoMessage request, String responseCode) {
        try {
            ISOMsg resp = new ISOMsg();
            resp.setPackager(packager);
            resp.setMTI("0810");
            copyIfPresent(request, resp, Field.STAN);
            copyIfPresent(request, resp, Field.NETWORK_MGMT_CODE);
            resp.set(Field.TRANSMISSION_DATETIME, TX_DATETIME.format(Instant.now()));
            resp.set(Field.RESPONSE_CODE, responseCode);
            return new IsoMessage(resp);
        } catch (ISOException e) {
            throw new IllegalStateException("Failed to build network response", e);
        }
    }

    private void copyIfPresent(IsoMessage src, ISOMsg dest, int fieldNo) throws ISOException {
        if (src.has(fieldNo)) {
            dest.set(fieldNo, src.get(fieldNo));
        }
    }
}
