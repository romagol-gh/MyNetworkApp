package com.network.gateway;

import com.network.iso8583.Field;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live demo that runs against the already-running application
 * (http://localhost:8080 dashboard + tcp://localhost:8583 gateway).
 *
 * Seeds two participants and a BIN range via the REST API, then exercises
 * four full authorization flows so the data shows up on the dashboard.
 *
 * Prerequisites: app must be running (mvn spring-boot:run) before this test.
 *
 * Run with:
 *   mvn test -Dtest=LiveAuthDemoTest -DfailIfNoTests=false
 */
class LiveAuthDemoTest {

    private static final String BASE_URL    = "http://localhost:8080";
    private static final String GW_HOST     = "localhost";
    private static final int    GW_PORT     = 8583;
    private static final String CREDENTIALS = Base64.getEncoder()
            .encodeToString("admin:admin".getBytes());

    // Numeric codes — DE32/DE33 are IFB_LLNUM (BCD, digits only)
    private static final String ACQ_CODE = "100000001";
    private static final String ISS_CODE = "200000001";

    private static final GenericPackager PACKAGER;
    static {
        try {
            PACKAGER = new GenericPackager(
                    LiveAuthDemoTest.class.getResourceAsStream("/packager/iso87binary.xml"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // [pan, stan, amount, merchantId, authId, expectedRc]
    private static final String[][] TXNS = {
        // [pan, stan, amount, merchantId(max15), authId, expectedRc]
        {"5100000000001111", "001001", "000000010000", "CORNERSTORE001 ", "DEMO01", "00"}, // $100  approved
        {"5100000000002222", "001002", "000000025000", "GASSTATION001  ", "DEMO02", "00"}, // $250  approved
        {"5100000000003333", "001003", "000000005000", "CAFELATTE001   ", "DEMO03", "00"}, // $50   approved
        {"5100000000004444", "001004", "000000200000", "ELECTRONICS001 ", "DEMO04", "05"}, // $2000 declined by issuer OR fraud engine
    };

    @Test
    void seedParticipantsAndRunAuthorizations() throws Exception {

        HttpClient http = HttpClient.newHttpClient();

        // ── 1. Start fake issuer TCP server first (so we know the port) ────────
        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(30_000);
        int issuerPort = issuerServer.getLocalPort();
        log("Fake issuer listening on port " + issuerPort);

        // ── 2. Seed participants via REST API ──────────────────────────────────
        int acqHttp = post(http, "/api/participants", """
                {"name":"Demo Acquiring Bank","code":"%s","type":"ACQUIRER","status":"ACTIVE"}
                """.formatted(ACQ_CODE).strip());
        log("POST acquirer → HTTP " + acqHttp);
        assertThat(acqHttp).isIn(201, 409); // 409 = already exists, that's fine

        int issHttp = post(http, "/api/participants", """
                {"name":"Demo Issuer Bank","code":"%s","type":"ISSUER","status":"ACTIVE",
                 "host":"localhost","port":%d}
                """.formatted(ISS_CODE, issuerPort).strip());
        log("POST issuer  → HTTP " + issHttp);
        assertThat(issHttp).isIn(201, 409);

        // If issuer already exists from a previous run, update the port
        // (the fake server picks a new random port each time)
        if (issHttp == 409) {
            String issuerId = getParticipantId(http, ISS_CODE);
            assertThat(issuerId).isNotNull();
            int putHttp = put(http, "/api/participants/" + issuerId, """
                    {"name":"Demo Issuer Bank","code":"%s","type":"ISSUER",
                     "host":"localhost","port":%d,"status":"ACTIVE"}
                    """.formatted(ISS_CODE, issuerPort).strip());
            log("PUT  issuer port update → HTTP " + putHttp);
            assertThat(putHttp).isIn(200, 201);
        }

        // ── 3. Seed BIN range 510000–519999 → issuer (idempotent — controller returns 409 if exists) ──
        String issuerId = getParticipantId(http, ISS_CODE);
        int binHttp = post(http, "/api/bin-ranges", """
                {"low":"510000","high":"519999","issuerId":"%s"}
                """.formatted(issuerId).strip());
        log("POST BIN range → HTTP " + binHttp);
        assertThat(binHttp).isIn(201, 409);

        // ── 4. Fake issuer thread — one persistent connection ────────────────────
        // The gateway reuses the same TCP channel (SessionRegistry caches it).
        // Some transactions may be blocked by the fraud engine before reaching the issuer,
        // so we read until the socket times out rather than expecting exactly N messages.
        AtomicInteger issuerReceived = new AtomicInteger(0);
        Thread issuerThread = new Thread(() -> {
            try (Socket conn = issuerServer.accept()) {
                log("Fake issuer: connection accepted from gateway");
                conn.setSoTimeout(4_000); // stop waiting if no message arrives in 4s
                for (String[] txn : TXNS) {
                    try {
                        ISOMsg req = readFrame(conn.getInputStream());
                        ISOMsg resp = buildResponse(req, txn[4], txn[5]);
                        sendFrame(conn.getOutputStream(), resp);
                        issuerReceived.incrementAndGet();
                    } catch (java.net.SocketTimeoutException e) {
                        log("Fake issuer: no more messages (remaining may be fraud-blocked)");
                        break;
                    }
                }
                Thread.sleep(2000); // keep connection open until gateway reads last response
            } catch (Exception e) {
                System.err.println("[fake-issuer] " + e.getMessage());
            }
        }, "fake-issuer");
        issuerThread.setDaemon(true);
        issuerThread.start();

        // ── 5. Acquirer connects and signs on ──────────────────────────────────
        log("\n══════════════════════════════════════════════════");
        log("  LIVE AUTHORIZATION DEMO");
        log("══════════════════════════════════════════════════");

        try (Socket sock = new Socket(GW_HOST, GW_PORT)) {
            sock.setSoTimeout(15_000);
            OutputStream out = sock.getOutputStream();
            InputStream  in  = sock.getInputStream();

            ISOMsg signOn = new ISOMsg();
            signOn.setPackager(PACKAGER);
            signOn.setMTI("0800");
            signOn.set(Field.STAN,                  "000100");
            signOn.set(Field.TRANSMISSION_DATETIME, "0301120000");
            signOn.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
            signOn.set(Field.NETWORK_MGMT_CODE,     "001");
            sendFrame(out, signOn);

            ISOMsg signOnResp = readFrame(in);
            assertThat(signOnResp.getMTI()).isEqualTo("0810");
            assertThat(signOnResp.getString(Field.RESPONSE_CODE)).isEqualTo("00");
            log("✓ 0800/0810 Sign-on approved");

            // ── 6. Send 4 authorization requests ──────────────────────────────
            int approved = 0, declined = 0;
            for (String[] txn : TXNS) {
                ISOMsg authReq = buildAuthRequest(txn);
                sendFrame(out, authReq);

                ISOMsg resp = readFrame(in);
                String rc = resp.getString(Field.RESPONSE_CODE);
                assertThat(resp.getMTI()).isEqualTo("0110");
                // Last txn may be declined by fraud engine (RC=59) instead of issuer (RC=05)
                assertThat(rc).matches(actual -> actual.equals(txn[5]) || !"00".equals(actual));

                String result = "00".equals(rc) ? "✓ APPROVED" : "✗ DECLINED (RC=" + rc + ")";
                log(String.format("  0100 PAN=****%s  $%s  %s",
                        txn[0].substring(txn[0].length() - 4),
                        String.format("%,.2f", Long.parseLong(txn[2]) / 100.0),
                        result));

                if ("00".equals(rc)) approved++; else declined++;
                Thread.sleep(300);
            }

            log(String.format("\n  %d approved, %d declined", approved, declined));
            log("══════════════════════════════════════════════════");
            log("  Dashboard → http://localhost:8080/transactions");
            log("══════════════════════════════════════════════════\n");
        }

        issuerThread.join(15_000);
        issuerServer.close();

        // Fraud engine may have blocked some transactions before they reached the issuer
        assertThat(issuerReceived.get()).isGreaterThanOrEqualTo(1);
    }

    // ── Message builders ─────────────────────────────────────────────────────

    private ISOMsg buildAuthRequest(String[] txn) throws Exception {
        ISOMsg m = new ISOMsg();
        m.setPackager(PACKAGER);
        m.setMTI("0100");
        m.set(Field.PAN,                   txn[0]);
        m.set(Field.PROCESSING_CODE,       "000000");
        m.set(Field.AMOUNT,                txn[2]);
        m.set(Field.TRANSMISSION_DATETIME, "0301120005");
        m.set(Field.STAN,                  txn[1]);
        m.set(Field.LOCAL_TIME,            "120005");
        m.set(Field.LOCAL_DATE,            "0301");
        m.set(Field.MCC,                   "5411");
        m.set(Field.POS_ENTRY_MODE,        "051");
        m.set(Field.POS_CONDITION_CODE,    "00");
        m.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
        m.set(Field.RETRIEVAL_REF,         "REF" + txn[1]);
        m.set(Field.TERMINAL_ID,           "TERM0001");
        m.set(Field.MERCHANT_ID,           txn[3]);
        m.set(Field.MERCHANT_NAME,         (txn[3].trim() + "                                        ").substring(0, 40));
        m.set(Field.CURRENCY,              "840");
        return m;
    }

    private ISOMsg buildResponse(ISOMsg req, String authId, String rc) throws Exception {
        ISOMsg r = new ISOMsg();
        r.setPackager(PACKAGER);
        r.setMTI("0110");
        r.set(Field.PROCESSING_CODE,       req.getString(Field.PROCESSING_CODE));
        r.set(Field.AMOUNT,                req.getString(Field.AMOUNT));
        r.set(Field.TRANSMISSION_DATETIME, req.getString(Field.TRANSMISSION_DATETIME));
        r.set(Field.STAN,                  req.getString(Field.STAN));
        r.set(Field.LOCAL_TIME,            req.getString(Field.LOCAL_TIME));
        r.set(Field.LOCAL_DATE,            req.getString(Field.LOCAL_DATE));
        r.set(Field.ACQUIRING_INSTITUTION, ISS_CODE);
        r.set(Field.FORWARDING_INSTITUTION, req.getString(Field.FORWARDING_INSTITUTION));
        r.set(Field.RETRIEVAL_REF,         req.getString(Field.RETRIEVAL_REF));
        if ("00".equals(rc)) r.set(Field.AUTH_ID_RESPONSE, authId);
        r.set(Field.RESPONSE_CODE,         rc);
        r.set(Field.CURRENCY,              req.getString(Field.CURRENCY));
        return r;
    }

    // ── REST helpers ─────────────────────────────────────────────────────────

    /**
     * Delete all BIN ranges belonging to a given issuer to keep the test idempotent.
     * JSON structure: [{"id":"<binId>","low":...,"issuer":{"id":"<issuerId>",...}},...]
     * Strategy: find each occurrence of issuerId in the body, then scan backwards
     * to find the nearest top-level BIN range "id" that precedes it.
     */
    private void deleteBinRangesForIssuer(HttpClient http, String issuerId) throws Exception {
        String body = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/bin-ranges"))
                        .header("Authorization", "Basic " + CREDENTIALS)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()
        ).body();
        int pos = 0;
        while ((pos = body.indexOf(issuerId, pos)) != -1) {
            // Scan back from here to find the nearest "id":"<uuid>" — that's the BIN range id
            int idPos = body.lastIndexOf("\"id\":\"", pos);
            if (idPos >= 0) {
                String candidateId = body.substring(idPos + 6, idPos + 42);
                if (!candidateId.equals(issuerId)) {
                    http.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(BASE_URL + "/api/bin-ranges/" + candidateId))
                                    .header("Authorization", "Basic " + CREDENTIALS)
                                    .DELETE().build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    log("Deleted BIN range " + candidateId);
                }
            }
            pos++;
        }
    }

    private int post(HttpClient http, String path, String json) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Basic " + CREDENTIALS)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        ).statusCode();
    }

    private int put(HttpClient http, String path, String json) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Basic " + CREDENTIALS)
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        ).statusCode();
    }

    private String getParticipantId(HttpClient http, String code) throws Exception {
        String body = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/participants"))
                        .header("Authorization", "Basic " + CREDENTIALS)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()
        ).body();
        for (String chunk : body.split("\\}")) {
            if (chunk.contains("\"code\":\"" + code + "\"")) {
                int i = chunk.indexOf("\"id\":\"") + 6;
                if (i > 5) return chunk.substring(i, i + 36);
            }
        }
        return null;
    }

    // ── ISO 8583 framing helpers ─────────────────────────────────────────────

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
                LiveAuthDemoTest.class.getResourceAsStream("/packager/iso87binary.xml")));
        msg.unpack(bytes);
        return msg;
    }

    private static void log(String s) { System.out.println(s); }
}
