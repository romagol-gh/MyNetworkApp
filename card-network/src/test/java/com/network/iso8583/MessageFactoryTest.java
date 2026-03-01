package com.network.iso8583;

import com.network.transaction.TransactionLogger;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOPackager;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFactoryTest {

    private MessageFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        ISOPackager packager = new GenericPackager(
                getClass().getResourceAsStream("/packager/iso87binary.xml"));
        factory = new MessageFactory(packager);
    }

    @Test
    void packAndUnpack_roundtrip() throws Exception {
        // Build a raw ISOMsg for an auth request
        ISOMsg raw = new ISOMsg();
        raw.setPackager(new GenericPackager(
                getClass().getResourceAsStream("/packager/iso87binary.xml")));
        raw.setMTI("0100");
        raw.set(Field.PAN, "4111111111111111");
        raw.set(Field.PROCESSING_CODE, "000000");
        raw.set(Field.AMOUNT, "000000010000");
        raw.set(Field.STAN, "123456");
        raw.set(Field.ACQUIRING_INSTITUTION, "12345");

        IsoMessage msg = new IsoMessage(raw);

        byte[] packed   = factory.pack(msg);
        IsoMessage back = factory.unpack(packed);

        assertThat(back.getMti()).isEqualTo("0100");
        assertThat(back.getPan()).isEqualTo("4111111111111111");
        assertThat(back.getStan()).isEqualTo("123456");
        assertThat(back.getAcquirerCode()).isEqualTo("12345");
    }

    @Test
    void buildResponse_setsCorrectMti() throws Exception {
        ISOMsg raw = new ISOMsg();
        raw.setPackager(new GenericPackager(
                getClass().getResourceAsStream("/packager/iso87binary.xml")));
        raw.setMTI("0100");
        raw.set(Field.STAN, "999888");
        raw.set(Field.ACQUIRING_INSTITUTION, "11111");

        IsoMessage req  = new IsoMessage(raw);
        IsoMessage resp = factory.buildResponse(req, ResponseCode.APPROVED);

        assertThat(resp.getMti()).isEqualTo("0110");
        assertThat(resp.getResponseCode()).isEqualTo("00");
        assertThat(resp.getStan()).isEqualTo("999888");
    }

    @Test
    void maskPan_correctlyMasks() {
        assertThat(TransactionLogger.maskPan("4111111111111111"))
                .isEqualTo("411111****1111");
        assertThat(TransactionLogger.maskPan("5500005555555559"))
                .isEqualTo("550000****5559");
    }
}
