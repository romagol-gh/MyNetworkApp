package com.network.gateway;

import com.network.iso8583.Field;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.junit.jupiter.api.BeforeAll;
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
import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Volume stress tests for the Agentic Commerce feature.
 *
 * Tests:
 *   1. concurrentAgents_300Transactions — 5 agent connections × 60 txns with DE48 agent context
 *   2. mccScopeEnforcement_100Mixed     — 50 in-scope (RC=00) + 50 out-of-scope (RC=57), no issuer contact for declined
 *   3. agentSignOnOff_50Cycles          — 50 sequential sign-on → authorize → sign-off lifecycle cycles
 *
 * Gateway port: 18592 (unique to this class)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AgentVolumeTest {

    private static final String ACQ_CODE     = "730000001";
    private static final String ISS_CODE     = "830000001";
    private static final int    GATEWAY_PORT = 18592;

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
        registry.add("gateway.port",               () -> String.valueOf(GATEWAY_PORT));
        registry.add("gateway.response-timeout",   () -> "15000");
    }

    @Autowired JdbcTemplate    jdbc;
    @Autowired SessionRegistry sessionRegistry;

    private final AtomicLong stanCounter = new AtomicLong(730_000L);

    private static final GenericPackager PACKAGER;
    static {
        try {
            PACKAGER = new GenericPackager(
                    AgentVolumeTest.class.getResourceAsStream("/packager/iso87binary.xml"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeAll
    static void startup() throws Exception {
        Thread.sleep(3000); // allow Netty gateway to bind
    }

    // ── Infrastructure ────────────────────────────────────────────────────────

    private void seedInfrastructure(int issuerPort) {
        jdbc.update("""
                INSERT INTO participants (id, name, code, type, status, created_at)
                VALUES (?, 'AgentVol Acquirer', ?, 'ACQUIRER', 'ACTIVE', NOW())
                ON CONFLICT (code) DO NOTHING
                """, UUID.randomUUID(), ACQ_CODE);

        jdbc.update("""
                INSERT INTO participants (id, name, code, type, host, port, status, created_at)
                VALUES (?, 'AgentVol Issuer', ?, 'ISSUER', 'localhost', ?, 'ACTIVE', NOW())
                ON CONFLICT (code) DO UPDATE SET port = EXCLUDED.port, host = EXCLUDED.host
                """, UUID.randomUUID(), ISS_CODE, issuerPort);

        jdbc.update("""
                INSERT INTO bin_ranges (id, low, high, issuer_id, created_at)
                SELECT gen_random_uuid(), '730000', '739999',
                       (SELECT id FROM participants WHERE code = ?), NOW()
                WHERE NOT EXISTS (
                    SELECT 1 FROM bin_ranges WHERE low = '730000' AND high = '739999'
                )
                """, ISS_CODE);
    }

    // ── Fake Issuer ───────────────────────────────────────────────────────────

    /**
     * Accepts one gateway connection and handles exactly {@code messageCount} auth requests,
     * all approved. Keeps the socket alive 2 s after the last message so the gateway can drain.
     */
    private Thread startFakeIssuer(ServerSocket server, int messageCount) {
        Thread t = new Thread(() -> {
            try (Socket conn = server.accept()) {
                conn.setSoTimeout(60_000);
                for (int i = 0; i < messageCount; i++) {
                    try {
                        ISOMsg req  = readFrame(conn.getInputStream());
                        ISOMsg resp = buildApproval(req);
                        synchronized (conn.getOutputStream()) {
                            sendFrame(conn.getOutputStream(), resp);
                        }
                    } catch (SocketTimeoutException e) {
                        System.err.printf("[agent-issuer] timeout after %d/%d%n", i, messageCount);
                        break;
                    }
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                System.err.println("[agent-issuer] " + e.getMessage());
            }
        }, "agent-fake-issuer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ── Protocol Helpers ──────────────────────────────────────────────────────

    private void participantSignOn(OutputStream out, InputStream in) throws Exception {
        ISOMsg msg = new ISOMsg();
        msg.setPackager(PACKAGER);
        msg.setMTI("0800");
        msg.set(Field.STAN,                  nextStan());
        msg.set(Field.TRANSMISSION_DATETIME, "0421120000");
        msg.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
        msg.set(Field.NETWORK_MGMT_CODE,     "001");
        sendFrame(out, msg);
        ISOMsg resp = readFrame(in);
        if (!"0810".equals(resp.getMTI()) || !"00".equals(resp.getString(Field.RESPONSE_CODE)))
            throw new IllegalStateException("Participant sign-on failed: RC=" + resp.getString(Field.RESPONSE_CODE));
    }

    private void agentSignOn(OutputStream out, InputStream in, String agentId, String mccScope) throws Exception {
        String de48 = "AGT:" + agentId + "|SCP:" + mccScope + "|LMT:100000";
        ISOMsg msg = new ISOMsg();
        msg.setPackager(PACKAGER);
        msg.setMTI("0800");
        msg.set(Field.STAN,                  nextStan());
        msg.set(Field.TRANSMISSION_DATETIME, "0421120001");
        msg.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
        msg.set(Field.NETWORK_MGMT_CODE,     "080");
        msg.set(Field.ADDITIONAL_DATA,       de48);
        sendFrame(out, msg);
        ISOMsg resp = readFrame(in);
        if (!"0810".equals(resp.getMTI()) || !"00".equals(resp.getString(Field.RESPONSE_CODE)))
            throw new IllegalStateException("Agent sign-on failed for " + agentId);
    }

    private void agentSignOff(OutputStream out, InputStream in, String agentId) throws Exception {
        ISOMsg msg = new ISOMsg();
        msg.setPackager(PACKAGER);
        msg.setMTI("0800");
        msg.set(Field.STAN,                  nextStan());
        msg.set(Field.TRANSMISSION_DATETIME, "0421120002");
        msg.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
        msg.set(Field.NETWORK_MGMT_CODE,     "081");
        msg.set(Field.ADDITIONAL_DATA,       "AGT:" + agentId);
        sendFrame(out, msg);
        ISOMsg resp = readFrame(in);
        if (!"0810".equals(resp.getMTI()) || !"00".equals(resp.getString(Field.RESPONSE_CODE)))
            throw new IllegalStateException("Agent sign-off failed for " + agentId);
    }

    private String sendAgentAuth(OutputStream out, InputStream in,
                                  String pan, long amount, String mcc,
                                  String agentId, String intentHash, String mccScope) throws Exception {
        String stan = nextStan();
        String de48 = "AGT:" + agentId + "|INT:" + intentHash + "|SCP:" + mccScope;
        ISOMsg req = new ISOMsg();
        req.setPackager(PACKAGER);
        req.setMTI("0100");
        req.set(Field.PAN,                   pan);
        req.set(Field.PROCESSING_CODE,       "000000");
        req.set(Field.AMOUNT,                String.format("%012d", amount));
        req.set(Field.TRANSMISSION_DATETIME, "0421120005");
        req.set(Field.STAN,                  stan);
        req.set(Field.LOCAL_TIME,            "120005");
        req.set(Field.LOCAL_DATE,            "0421");
        req.set(Field.MCC,                   mcc);
        req.set(Field.POS_ENTRY_MODE,        "051");
        req.set(Field.POS_CONDITION_CODE,    "00");
        req.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
        req.set(Field.RETRIEVAL_REF,         String.format("AGVOL%07d", Long.parseLong(stan)));  // 12 chars
        req.set(Field.TERMINAL_ID,           "AGVOL001");        // 8 chars
        req.set(Field.MERCHANT_ID,           "AGVOLMERCHANT01"); // 15 chars
        req.set(Field.CURRENCY,              "840");
        req.set(Field.ADDITIONAL_DATA,       de48);
        sendFrame(out, req);
        ISOMsg resp = readFrame(in);
        return resp.getString(Field.RESPONSE_CODE);
    }

    private ISOMsg buildApproval(ISOMsg req) throws Exception {
        ISOMsg r = new ISOMsg();
        r.setPackager(PACKAGER);
        r.setMTI("0110");
        if (req.hasField(Field.PROCESSING_CODE))        r.set(Field.PROCESSING_CODE,       req.getString(Field.PROCESSING_CODE));
        if (req.hasField(Field.AMOUNT))                 r.set(Field.AMOUNT,                req.getString(Field.AMOUNT));
        if (req.hasField(Field.TRANSMISSION_DATETIME))  r.set(Field.TRANSMISSION_DATETIME, req.getString(Field.TRANSMISSION_DATETIME));
        r.set(Field.STAN, req.getString(Field.STAN));
        if (req.hasField(Field.LOCAL_TIME))             r.set(Field.LOCAL_TIME,            req.getString(Field.LOCAL_TIME));
        if (req.hasField(Field.LOCAL_DATE))             r.set(Field.LOCAL_DATE,            req.getString(Field.LOCAL_DATE));
        r.set(Field.ACQUIRING_INSTITUTION, ISS_CODE);
        if (req.hasField(Field.FORWARDING_INSTITUTION)) r.set(Field.FORWARDING_INSTITUTION, req.getString(Field.FORWARDING_INSTITUTION));
        if (req.hasField(Field.RETRIEVAL_REF))          r.set(Field.RETRIEVAL_REF,         req.getString(Field.RETRIEVAL_REF));
        r.set(Field.AUTH_ID_RESPONSE, "AGAPR1");
        r.set(Field.RESPONSE_CODE, "00");
        if (req.hasField(Field.CURRENCY))               r.set(Field.CURRENCY,              req.getString(Field.CURRENCY));
        return r;
    }

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
                AgentVolumeTest.class.getResourceAsStream("/packager/iso87binary.xml")));
        msg.unpack(bytes);
        return msg;
    }

    private String nextStan() {
        return String.format("%06d", stanCounter.incrementAndGet() % 1_000_000L);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * 5 concurrent agent connections each sending 60 transactions with DE48 context = 300 total.
     * All must be approved. DB is verified to confirm each agent has exactly 60 approved rows.
     */
    @Test
    void concurrentAgents_300Transactions() throws Exception {
        final int    NUM_AGENTS    = 5;
        final int    TXN_PER_AGENT = 60;
        final int    TOTAL         = NUM_AGENTS * TXN_PER_AGENT;
        final String MCC_SCOPE     = "5411,5812";

        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(120_000);
        seedInfrastructure(issuerServer.getLocalPort());

        Thread issuerThread = startFakeIssuer(issuerServer, TOTAL);

        AtomicInteger approved   = new AtomicInteger(0);
        AtomicInteger failed     = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(NUM_AGENTS);

        ExecutorService pool = Executors.newFixedThreadPool(NUM_AGENTS);

        for (int a = 0; a < NUM_AGENTS; a++) {
            final int    agentIndex = a;
            final String agentId    = String.format("AGVOL%03d", agentIndex + 1); // AGVOL001..005
            pool.submit(() -> {
                try (Socket sock = new Socket("localhost", GATEWAY_PORT)) {
                    sock.setSoTimeout(30_000);
                    OutputStream out = sock.getOutputStream();
                    InputStream  in  = sock.getInputStream();

                    participantSignOn(out, in);
                    agentSignOn(out, in, agentId, MCC_SCOPE);
                    startLatch.await(); // release all agents simultaneously

                    for (int t = 0; t < TXN_PER_AGENT; t++) {
                        String pan        = String.format("73000%05d%04d", agentIndex, t + 1);
                        String intentHash = String.format("h%s%04d", agentId, t);
                        String rc = sendAgentAuth(out, in, pan, 5_000L, "5411",
                                agentId, intentHash, MCC_SCOPE);
                        if ("00".equals(rc)) approved.incrementAndGet();
                        else                 failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("[agent-" + agentId + "] error: " + e.getMessage());
                    failed.addAndGet(TXN_PER_AGENT);
                } finally {
                    doneLatch.countDown();
                }
                return null;
            });
        }

        startLatch.countDown(); // fire all agents
        doneLatch.await();

        pool.shutdown();
        issuerThread.join(30_000);
        issuerServer.close();
        sessionRegistry.deregisterByCode(ISS_CODE);

        System.out.printf("[AgentVolumeTest] concurrent: %d approved, %d failed%n",
                approved.get(), failed.get());

        assertThat(approved.get()).isEqualTo(TOTAL);
        assertThat(failed.get()).isZero();

        // Per-agent DB assertion: each agent must have exactly TXN_PER_AGENT approved rows
        for (int a = 0; a < NUM_AGENTS; a++) {
            String agentId = String.format("AGVOL%03d", a + 1);
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM transactions WHERE agent_id = ? AND response_code = '00'",
                    Long.class, agentId);
            assertThat(count).as("Approved txns for agent " + agentId).isEqualTo((long) TXN_PER_AGENT);
        }
    }

    /**
     * 100 sequential transactions for a single agent with MCC scope = "5411,5812".
     * Even-indexed use MCC 5411 (in scope → RC=00); odd-indexed use MCC 7995 (out of scope → RC=57).
     * Spend controls reject scope violations before the issuer is contacted,
     * so the fake issuer only sees 50 messages.
     */
    @Test
    void mccScopeEnforcement_100Mixed() throws Exception {
        final String AGENT_ID  = "AGSCOPE1";
        final String MCC_SCOPE = "5411,5812";
        final String MCC_IN    = "5411"; // allowed
        final String MCC_OUT   = "7995"; // gambling — not in scope
        final int    TOTAL     = 100;
        final int    EXPECTED_APPROVED = 50;

        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(60_000);
        seedInfrastructure(issuerServer.getLocalPort());

        // Issuer only handles in-scope transactions; scope violations are short-circuited by the gateway
        Thread issuerThread = startFakeIssuer(issuerServer, EXPECTED_APPROVED);

        int rcApproved = 0;
        int rcDeclined = 0;

        try (Socket sock = new Socket("localhost", GATEWAY_PORT)) {
            sock.setSoTimeout(15_000);
            OutputStream out = sock.getOutputStream();
            InputStream  in  = sock.getInputStream();

            participantSignOn(out, in);
            agentSignOn(out, in, AGENT_ID, MCC_SCOPE);

            for (int i = 0; i < TOTAL; i++) {
                String pan        = String.format("73100000%08d", i + 1);
                String mcc        = (i % 2 == 0) ? MCC_IN : MCC_OUT;
                String intentHash = String.format("scope%04d", i);
                String rc = sendAgentAuth(out, in, pan, 1_000L, mcc,
                        AGENT_ID, intentHash, MCC_SCOPE);
                if ("00".equals(rc))      rcApproved++;
                else if ("57".equals(rc)) rcDeclined++;
            }
        }

        issuerThread.join(15_000);
        issuerServer.close();
        sessionRegistry.deregisterByCode(ISS_CODE);

        System.out.printf("[AgentVolumeTest] mccScope: %d approved (RC=00), %d declined (RC=57)%n",
                rcApproved, rcDeclined);

        assertThat(rcApproved).isEqualTo(EXPECTED_APPROVED);
        assertThat(rcDeclined).isEqualTo(EXPECTED_APPROVED); // 50 scope violations
    }

    /**
     * 50 sequential agent lifecycle cycles on a single connection:
     * sign-on (NMC=080) → authorize → sign-off (NMC=081).
     * Each cycle uses a unique agent ID. After all cycles:
     *   - all 50 transactions must be approved
     *   - all 50 agent registrations must be INACTIVE in the database
     */
    @Test
    void agentSignOnOff_50Cycles() throws Exception {
        final int    CYCLES    = 50;
        final String MCC_SCOPE = "5411";

        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(120_000);
        seedInfrastructure(issuerServer.getLocalPort());

        Thread issuerThread = startFakeIssuer(issuerServer, CYCLES);

        int approved = 0;
        int failed   = 0;

        try (Socket sock = new Socket("localhost", GATEWAY_PORT)) {
            sock.setSoTimeout(15_000);
            OutputStream out = sock.getOutputStream();
            InputStream  in  = sock.getInputStream();

            participantSignOn(out, in);

            for (int i = 0; i < CYCLES; i++) {
                String agentId = String.format("AGCYC%03d", i + 1); // AGCYC001..050

                agentSignOn(out, in, agentId, MCC_SCOPE);

                String pan = String.format("73200000%08d", i + 1);
                String rc  = sendAgentAuth(out, in, pan, 5_000L, "5411",
                        agentId, String.format("cycle%04d", i), MCC_SCOPE);
                if ("00".equals(rc)) approved++;
                else                 failed++;

                agentSignOff(out, in, agentId);
            }
        }

        issuerThread.join(30_000);
        issuerServer.close();
        sessionRegistry.deregisterByCode(ISS_CODE);

        System.out.printf("[AgentVolumeTest] cycles: %d approved, %d failed%n", approved, failed);

        assertThat(approved).isEqualTo(CYCLES);
        assertThat(failed).isZero();

        // All cycle agents must be INACTIVE after sign-off
        Long inactiveCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_registrations WHERE agent_id LIKE 'AGCYC%' AND status = 'INACTIVE'",
                Long.class);
        assertThat(inactiveCount).as("All cycle agents must be INACTIVE").isEqualTo((long) CYCLES);

        // All 50 cycle transactions must be persisted with the correct agent_id
        Long agentTxnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE agent_id LIKE 'AGCYC%' AND response_code = '00'",
                Long.class);
        assertThat(agentTxnCount).as("All 50 cycle transactions must be approved and persisted").isEqualTo((long) CYCLES);
    }
}
