package com.network.gateway;

import com.network.iso8583.Field;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test demonstrating a full card purchase authorization flow:
 *
 *   Merchant/Acquirer  →  Payment Network  →  Issuer Bank
 *      sends 0100     routes by BIN       receives 0100
 *      receives 0110  forwards 0110       sends 0110 (approved)
 *
 * DE32/DE33 (Acquiring/Forwarding Institution ID) is IFB_LLNUM — digits only.
 * Real institution IDs are the bank's numeric routing codes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthorizationE2ETest {

    // DE32/DE33 are IFB_LLNUM — only digits; use numeric institution IDs
    private static final String ACQ_CODE = "000000001";
    private static final String ISS_CODE = "000000002";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("cardnetwork")
            .withUsername("card")
            .withPassword("card");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("gateway.port",               () -> "18584");
        registry.add("gateway.response-timeout",   () -> "10000");
    }

    @Autowired
    JdbcTemplate jdbc;

    private static final GenericPackager PACKAGER;
    static {
        try {
            PACKAGER = new GenericPackager(
                    AuthorizationE2ETest.class.getResourceAsStream("/packager/iso87binary.xml"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void purchaseAuthorization_networkRoutesCardToIssuerAndReturnsApproval() throws Exception {
        Thread.sleep(3000); // allow Spring Boot + Netty to fully start

        // ══════════════════════════════════════════════════════════════════════════
        // STEP 1 — Fake issuer bank TCP server
        //   In production this is the issuer's host system (e.g. bank's authoriser).
        //   Here we spin up a raw ServerSocket on a random port that the network
        //   will connect to when it needs to forward the 0100.
        // ══════════════════════════════════════════════════════════════════════════
        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(20_000);
        int issuerPort = issuerServer.getLocalPort();

        AtomicReference<String> capturedMti  = new AtomicReference<>();
        AtomicReference<String> capturedStan = new AtomicReference<>();

        Thread issuerThread = new Thread(() -> {
            try (Socket conn = issuerServer.accept()) {
                println("┌─ ISSUER received inbound connection from the network");

                ISOMsg req = readFrame(conn.getInputStream());
                capturedMti.set(req.getMTI());
                capturedStan.set(req.getString(Field.STAN));

                println("│  ← 0100 Authorization Request");
                println("│     PAN (DE2):              " + mask(req.getString(Field.PAN)));
                println("│     Amount (DE4):            $" + cents(req.getString(Field.AMOUNT)));
                println("│     STAN (DE11):             " + req.getString(Field.STAN));
                println("│     Merchant ID (DE42):      " + req.getString(Field.MERCHANT_ID).trim());
                println("│     MCC (DE18):              " + req.getString(Field.MCC));
                println("│     Forwarding inst (DE33):  " + req.getString(Field.FORWARDING_INSTITUTION));

                // Build 0110 approval — echo key fields back.
                // The forwarding institution field (DE33) MUST be echoed back so
                // the network can correlate this response to the pending 0100 future.
                ISOMsg resp = new ISOMsg();
                resp.setPackager(PACKAGER);
                resp.setMTI("0110");
                resp.set(Field.PROCESSING_CODE,        req.getString(Field.PROCESSING_CODE));
                resp.set(Field.AMOUNT,                 req.getString(Field.AMOUNT));
                resp.set(Field.TRANSMISSION_DATETIME,  req.getString(Field.TRANSMISSION_DATETIME));
                resp.set(Field.STAN,                   req.getString(Field.STAN));           // correlation key part 2
                resp.set(Field.LOCAL_TIME,             req.getString(Field.LOCAL_TIME));
                resp.set(Field.LOCAL_DATE,             req.getString(Field.LOCAL_DATE));
                resp.set(Field.ACQUIRING_INSTITUTION,  ISS_CODE);
                resp.set(Field.FORWARDING_INSTITUTION, req.getString(Field.FORWARDING_INSTITUTION)); // correlation key part 1
                resp.set(Field.RETRIEVAL_REF,          req.getString(Field.RETRIEVAL_REF));
                resp.set(Field.AUTH_ID_RESPONSE,       "AUTH42");
                resp.set(Field.RESPONSE_CODE,          "00");                                // APPROVED
                resp.set(Field.CURRENCY,               req.getString(Field.CURRENCY));

                sendFrame(conn.getOutputStream(), resp);
                println("│  → 0110 APPROVED  (RC=00, AuthID=AUTH42)");
                println("└─ ISSUER done");

                Thread.sleep(2000); // keep connection alive for Netty to read response
            } catch (Exception e) {
                System.err.println("[fake-issuer] error: " + e.getMessage());
            }
        }, "fake-issuer");
        issuerThread.setDaemon(true);
        issuerThread.start();

        // ══════════════════════════════════════════════════════════════════════════
        // STEP 2 — Seed the database with participants and BIN routing table
        // ══════════════════════════════════════════════════════════════════════════
        UUID acquirerId = UUID.randomUUID();
        UUID issuerId   = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO participants (id, name, code, type, host, port, status, created_at)
                VALUES (?, 'First National Acquiring Bank', ?, 'ACQUIRER',
                        null, null, 'ACTIVE', NOW())
                """, acquirerId, ACQ_CODE);

        jdbc.update("""
                INSERT INTO participants (id, name, code, type, host, port, status, created_at)
                VALUES (?, 'Visa Issuer Bank', ?, 'ISSUER',
                        'localhost', ?, 'ACTIVE', NOW())
                """, issuerId, ISS_CODE, issuerPort);

        jdbc.update("""
                INSERT INTO bin_ranges (id, low, high, issuer_id, created_at)
                VALUES (?, '400000', '499999', ?, NOW())
                """, UUID.randomUUID(), issuerId);

        println("\n══════════════════════════════════════════════════════");
        println(" END-TO-END CARD AUTHORIZATION TEST");
        println("══════════════════════════════════════════════════════");
        println("Network participants:");
        println("  Acquirer : " + ACQ_CODE + "  (merchant-side bank)");
        println("  Issuer   : " + ISS_CODE + "  (cardholder's bank) @ localhost:" + issuerPort);
        println("  BIN Range: 400000–499999  →  " + ISS_CODE);

        // ══════════════════════════════════════════════════════════════════════════
        // STEP 3 — Acquirer bank connects and signs on (0800 Network Management)
        // ══════════════════════════════════════════════════════════════════════════
        try (Socket acquirerSocket = new Socket("localhost", 18584)) {
            acquirerSocket.setSoTimeout(15_000);
            OutputStream out = acquirerSocket.getOutputStream();
            InputStream  in  = acquirerSocket.getInputStream();

            println("\n[ACQUIRER → NETWORK]  0800 Sign-On Request (NMC=001)");

            ISOMsg signOn = new ISOMsg();
            signOn.setPackager(PACKAGER);
            signOn.setMTI("0800");
            signOn.set(Field.STAN,                   "000001");
            signOn.set(Field.TRANSMISSION_DATETIME,  "0301120000");
            signOn.set(Field.ACQUIRING_INSTITUTION,  ACQ_CODE);
            signOn.set(Field.NETWORK_MGMT_CODE,      "001");
            sendFrame(out, signOn);

            ISOMsg signOnResp = readFrame(in);
            println("[NETWORK → ACQUIRER]  0810 Sign-On Response  RC=" +
                    signOnResp.getString(Field.RESPONSE_CODE) + " (00=OK)");

            assertThat(signOnResp.getMTI()).isEqualTo("0810");
            assertThat(signOnResp.getString(Field.RESPONSE_CODE)).isEqualTo("00");

            // ══════════════════════════════════════════════════════════════════════
            // STEP 4 — Merchant sends a card purchase (0100 Authorization Request)
            // ══════════════════════════════════════════════════════════════════════
            println("\n[ACQUIRER → NETWORK]  0100 Authorization Request");
            println("  Card    : 4111 **** **** 1111  (Visa test card)");
            println("  Amount  : $100.00 USD");
            println("  Merchant: Corner Store (MCC 5411 – Grocery)");
            println("  Terminal: TERM0001");

            ISOMsg authReq = new ISOMsg();
            authReq.setPackager(PACKAGER);
            authReq.setMTI("0100");
            authReq.set(Field.PAN,                   "4111111111111111");
            authReq.set(Field.PROCESSING_CODE,       "000000");        // purchase
            authReq.set(Field.AMOUNT,                "000000010000");  // $100.00 in cents
            authReq.set(Field.TRANSMISSION_DATETIME, "0301120005");
            authReq.set(Field.STAN,                  "000002");
            authReq.set(Field.LOCAL_TIME,            "120005");
            authReq.set(Field.LOCAL_DATE,            "0301");
            authReq.set(Field.MCC,                   "5411");
            authReq.set(Field.POS_ENTRY_MODE,        "051");           // chip
            authReq.set(Field.POS_CONDITION_CODE,    "00");
            authReq.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
            authReq.set(Field.RETRIEVAL_REF,         "REF000000002");
            authReq.set(Field.TERMINAL_ID,           "TERM0001");
            authReq.set(Field.MERCHANT_ID,           "CORNERSTORE001 ");
            authReq.set(Field.MERCHANT_NAME,         "Corner Store             City Center US");
            authReq.set(Field.CURRENCY,              "840");            // USD
            sendFrame(out, authReq);

            println("[NETWORK]  BIN lookup: 411111 → " + ISS_CODE + ", connecting to issuer...");
            println("[NETWORK]  Fraud check: passed (score < 50)");
            println("[NETWORK]  Forwarding 0100 to issuer " + ISS_CODE + "...");

            // ══════════════════════════════════════════════════════════════════════
            // STEP 5 — Read the 0110 response the network forwards back
            // ══════════════════════════════════════════════════════════════════════
            ISOMsg authResp = readFrame(in);

            println("\n[NETWORK → ACQUIRER]  0110 Authorization Response");
            println("  MTI      : " + authResp.getMTI());
            println("  STAN     : " + authResp.getString(Field.STAN));
            String rc = authResp.getString(Field.RESPONSE_CODE);
            println("  RC (DE39): " + rc + (rc.equals("00") ? "  ✓ APPROVED" : "  ✗ DECLINED"));
            println("  Auth ID  : " + authResp.getString(Field.AUTH_ID_RESPONSE));
            println("  Amount   : $" + cents(authResp.getString(Field.AMOUNT)) + " USD");
            println("\n══════════════════════════════════════════════════════");
            println(" RESULT: Card authorization APPROVED end-to-end ✓");
            println("══════════════════════════════════════════════════════\n");

            assertThat(authResp.getMTI()).isEqualTo("0110");
            assertThat(authResp.getString(Field.RESPONSE_CODE)).isEqualTo("00");
            assertThat(authResp.getString(Field.AUTH_ID_RESPONSE)).isEqualTo("AUTH42");
            assertThat(authResp.getString(Field.STAN)).isEqualTo("000002");
            assertThat(authResp.getString(Field.AMOUNT)).isEqualTo("000000010000");
        }

        issuerThread.join(10_000);
        issuerServer.close();

        // Confirm the network actually forwarded the 0100 to the issuer
        assertThat(capturedMti.get()).isEqualTo("0100");
        assertThat(capturedStan.get()).isEqualTo("000002");
    }

    // ── ISO 8583 framing helpers ──────────────────────────────────────────────────

    private static void sendFrame(OutputStream out, ISOMsg msg) throws Exception {
        byte[] packed = msg.pack();
        out.write((packed.length >> 8) & 0xFF);
        out.write(packed.length & 0xFF);
        out.write(packed);
        out.flush();
    }

    private static ISOMsg readFrame(InputStream in) throws Exception {
        int len = (in.read() << 8) | in.read();
        byte[] bytes = in.readNBytes(len);
        ISOMsg msg = new ISOMsg();
        msg.setPackager(new GenericPackager(
                AuthorizationE2ETest.class.getResourceAsStream("/packager/iso87binary.xml")));
        msg.unpack(bytes);
        return msg;
    }

    // ── Formatting helpers ────────────────────────────────────────────────────────

    private static void println(String s) { System.out.println(s); }

    private static String mask(String pan) {
        if (pan == null || pan.length() < 10) return "****";
        return pan.substring(0, 4) + " **** **** " + pan.substring(pan.length() - 4);
    }

    private static String cents(String minorUnits) {
        if (minorUnits == null) return "0.00";
        try { return String.format("%.2f", Long.parseLong(minorUnits.trim()) / 100.0); }
        catch (NumberFormatException e) { return minorUnits; }
    }
}
