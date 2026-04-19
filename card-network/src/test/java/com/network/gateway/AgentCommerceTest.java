package com.network.gateway;

import com.network.iso8583.Field;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the Agentic Commerce feature:
 *
 *   1. Agent sign-on  (0800 NMC=080 with DE48 agent context)
 *   2. Agent authorization — approved (0100 with DE48, issuer approves)
 *   3. Per-txn cap exceeded  → RC=61 (no issuer contact)
 *   4. MCC scope violation   → RC=57 (no issuer contact)
 *   5. Agent sign-off (0800 NMC=081 → INACTIVE in DB)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentCommerceTest {

    private static final String ACQ_CODE = "000000010";
    private static final String ISS_CODE = "000000011";

    // Agent context values
    private static final String AGENT_ID    = "AGNT001";
    private static final String INTENT_HASH = "sha256abc123def456";
    private static final String MCC_SCOPE   = "5411,5812";

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
        registry.add("gateway.port",               () -> "18587");
        registry.add("gateway.response-timeout",   () -> "10000");
    }

    @Autowired
    JdbcTemplate jdbc;

    private static final GenericPackager PACKAGER;
    static {
        try {
            PACKAGER = new GenericPackager(
                    AgentCommerceTest.class.getResourceAsStream("/packager/iso87binary.xml"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── 1. Agent Sign-On ─────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void testAgentSignOn_registersAgentInDatabase() throws Exception {
        Thread.sleep(3000);

        UUID acquirerId = UUID.randomUUID();
        UUID issuerId   = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO participants (id, name, code, type, host, port, status, created_at)
                VALUES (?, 'Test Acquirer Bank', ?, 'ACQUIRER', null, null, 'ACTIVE', NOW())
                """, acquirerId, ACQ_CODE);
        jdbc.update("""
                INSERT INTO participants (id, name, code, type, host, port, status, created_at)
                VALUES (?, 'Test Issuer Bank', ?, 'ISSUER', null, null, 'ACTIVE', NOW())
                """, issuerId, ISS_CODE);
        jdbc.update("""
                INSERT INTO bin_ranges (id, low, high, issuer_id, created_at)
                VALUES (?, '450000', '459999', ?, NOW())
                """, UUID.randomUUID(), issuerId);

        try (Socket socket = new Socket("localhost", 18587)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            // Standard participant sign-on first
            ISOMsg signOn = new ISOMsg();
            signOn.setPackager(PACKAGER);
            signOn.setMTI("0800");
            signOn.set(Field.STAN,                   "100001");
            signOn.set(Field.TRANSMISSION_DATETIME,  "0401120000");
            signOn.set(Field.ACQUIRING_INSTITUTION,  ACQ_CODE);
            signOn.set(Field.NETWORK_MGMT_CODE,      "001");
            sendFrame(out, signOn);
            ISOMsg signOnResp = readFrame(in);
            assertThat(signOnResp.getString(Field.RESPONSE_CODE)).isEqualTo("00");

            // Agent sign-on (NMC=080) with DE48 agent context
            String de48 = "AGT:" + AGENT_ID + "|SCP:" + MCC_SCOPE + "|LMT:50000";
            ISOMsg agentSignOn = new ISOMsg();
            agentSignOn.setPackager(PACKAGER);
            agentSignOn.setMTI("0800");
            agentSignOn.set(Field.STAN,                   "100002");
            agentSignOn.set(Field.TRANSMISSION_DATETIME,  "0401120001");
            agentSignOn.set(Field.ACQUIRING_INSTITUTION,  ACQ_CODE);
            agentSignOn.set(Field.NETWORK_MGMT_CODE,      "080");
            agentSignOn.set(Field.ADDITIONAL_DATA,        de48);
            sendFrame(out, agentSignOn);

            ISOMsg agentSignOnResp = readFrame(in);
            assertThat(agentSignOnResp.getMTI()).isEqualTo("0810");
            assertThat(agentSignOnResp.getString(Field.RESPONSE_CODE)).isEqualTo("00");
        }

        // Verify agent_registrations record was created
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT agent_id, status, mcc_scope FROM agent_registrations WHERE agent_id = ?", AGENT_ID);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("agent_id")).isEqualTo(AGENT_ID);
        assertThat(rows.get(0).get("status")).isEqualTo("ACTIVE");
        assertThat(rows.get(0).get("mcc_scope")).isEqualTo(MCC_SCOPE);
    }

    // ── 2. Agent Authorization (approved) ────────────────────────────────────────

    @Test
    @Order(2)
    void testAgentAuthorization_approved_persistsAgentFields() throws Exception {
        // Spin up fake issuer
        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(15_000);
        int issuerPort = issuerServer.getLocalPort();

        // Update issuer participant with issuer port
        jdbc.update("UPDATE participants SET host='localhost', port=? WHERE code=?",
                issuerPort, ISS_CODE);

        Thread issuerThread = new Thread(() -> {
            try (Socket conn = issuerServer.accept()) {
                ISOMsg req = readFrame(conn.getInputStream());
                ISOMsg resp = new ISOMsg();
                resp.setPackager(PACKAGER);
                resp.setMTI("0110");
                resp.set(Field.PROCESSING_CODE,        req.getString(Field.PROCESSING_CODE));
                resp.set(Field.AMOUNT,                 req.getString(Field.AMOUNT));
                resp.set(Field.TRANSMISSION_DATETIME,  req.getString(Field.TRANSMISSION_DATETIME));
                resp.set(Field.STAN,                   req.getString(Field.STAN));
                resp.set(Field.LOCAL_TIME,             req.getString(Field.LOCAL_TIME));
                resp.set(Field.LOCAL_DATE,             req.getString(Field.LOCAL_DATE));
                resp.set(Field.ACQUIRING_INSTITUTION,  ISS_CODE);
                resp.set(Field.FORWARDING_INSTITUTION, req.getString(Field.FORWARDING_INSTITUTION));
                resp.set(Field.RETRIEVAL_REF,          req.getString(Field.RETRIEVAL_REF));
                resp.set(Field.AUTH_ID_RESPONSE,       "AGAUTH");
                resp.set(Field.RESPONSE_CODE,          "00");
                resp.set(Field.CURRENCY,               req.getString(Field.CURRENCY));
                sendFrame(conn.getOutputStream(), resp);
                Thread.sleep(2000);
            } catch (Exception e) {
                System.err.println("[fake-issuer-agent] " + e.getMessage());
            }
        }, "fake-issuer-agent");
        issuerThread.setDaemon(true);
        issuerThread.start();

        try (Socket socket = new Socket("localhost", 18587)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            // Re-sign-on
            ISOMsg signOn = new ISOMsg();
            signOn.setPackager(PACKAGER);
            signOn.setMTI("0800");
            signOn.set(Field.STAN, "100010");
            signOn.set(Field.TRANSMISSION_DATETIME, "0401120010");
            signOn.set(Field.ACQUIRING_INSTITUTION,  ACQ_CODE);
            signOn.set(Field.NETWORK_MGMT_CODE,      "001");
            sendFrame(out, signOn);
            readFrame(in); // ignore response

            String de48 = "AGT:" + AGENT_ID + "|INT:" + INTENT_HASH + "|SCP:" + MCC_SCOPE;
            ISOMsg authReq = new ISOMsg();
            authReq.setPackager(PACKAGER);
            authReq.setMTI("0100");
            authReq.set(Field.PAN,                   "4500000000000001");
            authReq.set(Field.PROCESSING_CODE,       "000000");
            authReq.set(Field.AMOUNT,                "000000001000");  // $10.00
            authReq.set(Field.TRANSMISSION_DATETIME, "0401120011");
            authReq.set(Field.STAN,                  "100011");
            authReq.set(Field.LOCAL_TIME,            "120011");
            authReq.set(Field.LOCAL_DATE,            "0401");
            authReq.set(Field.MCC,                   "5411");           // in scope
            authReq.set(Field.POS_ENTRY_MODE,        "010");
            authReq.set(Field.POS_CONDITION_CODE,    "00");
            authReq.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
            authReq.set(Field.RETRIEVAL_REF,         "REF000000011");
            authReq.set(Field.TERMINAL_ID,           "TERM0001");
            authReq.set(Field.MERCHANT_ID,           "AGENTMERCHANT01");
            authReq.set(Field.CURRENCY,              "840");
            authReq.set(Field.ADDITIONAL_DATA,       de48);
            sendFrame(out, authReq);

            ISOMsg authResp = readFrame(in);
            assertThat(authResp.getMTI()).isEqualTo("0110");
            assertThat(authResp.getString(Field.RESPONSE_CODE)).isEqualTo("00");
            // DE48 must be echoed back
            assertThat(authResp.getString(Field.ADDITIONAL_DATA)).contains("AGT:" + AGENT_ID);
        }

        issuerThread.join(10_000);
        issuerServer.close();

        // Verify agent fields persisted in transactions
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT agent_id, intent_hash FROM transactions WHERE agent_id = ?", AGENT_ID);
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("agent_id")).isEqualTo(AGENT_ID);
        assertThat(rows.get(0).get("intent_hash")).isEqualTo(INTENT_HASH);
    }

    // ── 3. Per-txn cap exceeded ───────────────────────────────────────────────────

    @Test
    @Order(3)
    void testAgentAuthorization_perTxnCapExceeded_returnsRc61() throws Exception {
        try (Socket socket = new Socket("localhost", 18587)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            // Re-sign-on
            ISOMsg signOn = new ISOMsg();
            signOn.setPackager(PACKAGER);
            signOn.setMTI("0800");
            signOn.set(Field.STAN, "100020");
            signOn.set(Field.TRANSMISSION_DATETIME, "0401120020");
            signOn.set(Field.ACQUIRING_INSTITUTION,  ACQ_CODE);
            signOn.set(Field.NETWORK_MGMT_CODE,      "001");
            sendFrame(out, signOn);
            readFrame(in); // ignore response

            // DE48: per-txn limit = $5.00 (500 minor units), amount = $100.00 (10000) → exceeds
            String de48 = "AGT:" + AGENT_ID + "|LMT:500|SCP:" + MCC_SCOPE;
            ISOMsg authReq = new ISOMsg();
            authReq.setPackager(PACKAGER);
            authReq.setMTI("0100");
            authReq.set(Field.PAN,                   "4500000000000001");
            authReq.set(Field.PROCESSING_CODE,       "000000");
            authReq.set(Field.AMOUNT,                "000000010000");  // $100.00
            authReq.set(Field.TRANSMISSION_DATETIME, "0401120021");
            authReq.set(Field.STAN,                  "100021");
            authReq.set(Field.LOCAL_TIME,            "120021");
            authReq.set(Field.LOCAL_DATE,            "0401");
            authReq.set(Field.MCC,                   "5411");
            authReq.set(Field.POS_ENTRY_MODE,        "010");
            authReq.set(Field.POS_CONDITION_CODE,    "00");
            authReq.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
            authReq.set(Field.RETRIEVAL_REF,         "REF000000021");
            authReq.set(Field.TERMINAL_ID,           "TERM0001");
            authReq.set(Field.MERCHANT_ID,           "AGENTMERCHANT01");
            authReq.set(Field.CURRENCY,              "840");
            authReq.set(Field.ADDITIONAL_DATA,       de48);
            sendFrame(out, authReq);

            ISOMsg authResp = readFrame(in);
            assertThat(authResp.getMTI()).isEqualTo("0110");
            assertThat(authResp.getString(Field.RESPONSE_CODE)).isEqualTo("61");
        }
    }

    // ── 4. MCC scope violation ────────────────────────────────────────────────────

    @Test
    @Order(4)
    void testAgentAuthorization_mccScopeViolation_returnsRc57() throws Exception {
        try (Socket socket = new Socket("localhost", 18587)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            // Re-sign-on
            ISOMsg signOn = new ISOMsg();
            signOn.setPackager(PACKAGER);
            signOn.setMTI("0800");
            signOn.set(Field.STAN, "100030");
            signOn.set(Field.TRANSMISSION_DATETIME, "0401120030");
            signOn.set(Field.ACQUIRING_INSTITUTION,  ACQ_CODE);
            signOn.set(Field.NETWORK_MGMT_CODE,      "001");
            sendFrame(out, signOn);
            readFrame(in); // ignore response

            // DE48: SCP allows only 5411,5812, but MCC is 7995 (gambling) → scope violation
            String de48 = "AGT:" + AGENT_ID + "|SCP:" + MCC_SCOPE;
            ISOMsg authReq = new ISOMsg();
            authReq.setPackager(PACKAGER);
            authReq.setMTI("0100");
            authReq.set(Field.PAN,                   "4500000000000001");
            authReq.set(Field.PROCESSING_CODE,       "000000");
            authReq.set(Field.AMOUNT,                "000000001000");  // $10.00
            authReq.set(Field.TRANSMISSION_DATETIME, "0401120031");
            authReq.set(Field.STAN,                  "100031");
            authReq.set(Field.LOCAL_TIME,            "120031");
            authReq.set(Field.LOCAL_DATE,            "0401");
            authReq.set(Field.MCC,                   "7995");           // NOT in scope
            authReq.set(Field.POS_ENTRY_MODE,        "010");
            authReq.set(Field.POS_CONDITION_CODE,    "00");
            authReq.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
            authReq.set(Field.RETRIEVAL_REF,         "REF000000031");
            authReq.set(Field.TERMINAL_ID,           "TERM0001");
            authReq.set(Field.MERCHANT_ID,           "AGENTMERCHANT01");
            authReq.set(Field.CURRENCY,              "840");
            authReq.set(Field.ADDITIONAL_DATA,       de48);
            sendFrame(out, authReq);

            ISOMsg authResp = readFrame(in);
            assertThat(authResp.getMTI()).isEqualTo("0110");
            assertThat(authResp.getString(Field.RESPONSE_CODE)).isEqualTo("57");
        }
    }

    // ── 5. Agent Sign-Off ─────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void testAgentSignOff_setsAgentInactive() throws Exception {
        try (Socket socket = new Socket("localhost", 18587)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            InputStream  in  = socket.getInputStream();

            // Standard sign-on
            ISOMsg signOn = new ISOMsg();
            signOn.setPackager(PACKAGER);
            signOn.setMTI("0800");
            signOn.set(Field.STAN, "100040");
            signOn.set(Field.TRANSMISSION_DATETIME, "0401120040");
            signOn.set(Field.ACQUIRING_INSTITUTION,  ACQ_CODE);
            signOn.set(Field.NETWORK_MGMT_CODE,      "001");
            sendFrame(out, signOn);
            readFrame(in); // ignore response

            // Agent sign-off (NMC=081)
            String de48 = "AGT:" + AGENT_ID;
            ISOMsg agentSignOff = new ISOMsg();
            agentSignOff.setPackager(PACKAGER);
            agentSignOff.setMTI("0800");
            agentSignOff.set(Field.STAN,                   "100041");
            agentSignOff.set(Field.TRANSMISSION_DATETIME,  "0401120041");
            agentSignOff.set(Field.ACQUIRING_INSTITUTION,  ACQ_CODE);
            agentSignOff.set(Field.NETWORK_MGMT_CODE,      "081");
            agentSignOff.set(Field.ADDITIONAL_DATA,        de48);
            sendFrame(out, agentSignOff);

            ISOMsg signOffResp = readFrame(in);
            assertThat(signOffResp.getMTI()).isEqualTo("0810");
            assertThat(signOffResp.getString(Field.RESPONSE_CODE)).isEqualTo("00");
        }

        // Verify agent status is now INACTIVE
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM agent_registrations WHERE agent_id = ?", AGENT_ID);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("INACTIVE");
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
                AgentCommerceTest.class.getResourceAsStream("/packager/iso87binary.xml")));
        msg.unpack(bytes);
        return msg;
    }
}
