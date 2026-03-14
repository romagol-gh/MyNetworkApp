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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Duration / sustained-load tests: verifies the gateway remains stable under
 * continuous traffic and shows no throughput degradation over time.
 *
 * Tests:
 *   1. sustainedLoad_30Seconds      — single connection, ≥30s continuous flow,
 *                                     per-5s window TPS must not drop >50% vs peak
 *   2. multiClient_20SecondSustained — 5 simultaneous connections for 20s,
 *                                      overall approval rate must be ≥95%
 *
 * Gateway port: 18591 (unique to this class)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DurationTest {

    private static final String ACQ_CODE     = "720000001";
    private static final String ISS_CODE     = "820000001";
    private static final int    GATEWAY_PORT = 18591;

    // Window length for throughput stability checks
    private static final long WINDOW_MS = 5_000L;

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

    private final AtomicLong stanCounter = new AtomicLong(720_000L);

    private static final GenericPackager PACKAGER;
    static {
        try {
            PACKAGER = new GenericPackager(
                    DurationTest.class.getResourceAsStream("/packager/iso87binary.xml"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeAll
    static void startup() throws Exception {
        Thread.sleep(3000); // allow Netty gateway to bind
    }


    private void seedInfrastructure(int issuerPort) {
        UUID acqId = UUID.randomUUID();
        UUID issId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO participants (id, name, code, type, status, created_at)
                VALUES (?, 'Duration Acquirer', ?, 'ACQUIRER', 'ACTIVE', NOW())
                ON CONFLICT (code) DO NOTHING
                """, acqId, ACQ_CODE);

        jdbc.update("""
                INSERT INTO participants (id, name, code, type, host, port, status, created_at)
                VALUES (?, 'Duration Issuer', ?, 'ISSUER', 'localhost', ?, 'ACTIVE', NOW())
                ON CONFLICT (code) DO UPDATE SET port = EXCLUDED.port, host = EXCLUDED.host
                """, issId, ISS_CODE, issuerPort);

        jdbc.update("""
                INSERT INTO bin_ranges (id, low, high, issuer_id, created_at)
                SELECT gen_random_uuid(), '720000', '729999',
                       (SELECT id FROM participants WHERE code = ?), NOW()
                WHERE NOT EXISTS (
                    SELECT 1 FROM bin_ranges WHERE low = '720000' AND high = '729999'
                )
                """, ISS_CODE);
    }

    /**
     * Fake issuer that keeps handling messages until {@code stop} is set to true.
     * The issuer approves every request with RC=00.
     */
    private Thread startFakeIssuerUnbounded(ServerSocket server, AtomicBoolean stop) {
        Thread t = new Thread(() -> {
            try (Socket conn = server.accept()) {
                conn.setSoTimeout(2_000);
                while (!stop.get()) {
                    try {
                        ISOMsg req  = readFrame(conn.getInputStream());
                        ISOMsg resp = buildApproval(req);
                        synchronized (conn.getOutputStream()) {
                            sendFrame(conn.getOutputStream(), resp);
                        }
                    } catch (SocketTimeoutException ignored) {
                        // poll stop flag
                    }
                }
            } catch (Exception e) {
                if (!stop.get()) {
                    System.err.println("[duration-issuer] error: " + e.getMessage());
                }
            }
        }, "duration-issuer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void signOn(OutputStream out, InputStream in) throws Exception {
        ISOMsg signon = new ISOMsg();
        signon.setPackager(PACKAGER);
        signon.setMTI("0800");
        signon.set(Field.STAN,                  nextStan());
        signon.set(Field.TRANSMISSION_DATETIME, "0313120000");
        signon.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
        signon.set(Field.NETWORK_MGMT_CODE,     "001");
        sendFrame(out, signon);
        ISOMsg resp = readFrame(in);
        if (!"0810".equals(resp.getMTI()) || !"00".equals(resp.getString(Field.RESPONSE_CODE)))
            throw new IllegalStateException("Sign-on failed");
    }

    private String sendAuth(OutputStream out, InputStream in, String pan, long amount) throws Exception {
        String stan = nextStan();
        ISOMsg req = new ISOMsg();
        req.setPackager(PACKAGER);
        req.setMTI("0100");
        req.set(Field.PAN,                   pan);
        req.set(Field.PROCESSING_CODE,       "000000");
        req.set(Field.AMOUNT,                String.format("%012d", amount));
        req.set(Field.TRANSMISSION_DATETIME, "0313120005");
        req.set(Field.STAN,                  stan);
        req.set(Field.LOCAL_TIME,            "120005");
        req.set(Field.LOCAL_DATE,            "0313");
        req.set(Field.MCC,                   "5411");
        req.set(Field.POS_ENTRY_MODE,        "051");
        req.set(Field.POS_CONDITION_CODE,    "00");
        req.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
        req.set(Field.RETRIEVAL_REF,         ("D" + stan).substring(0, Math.min(("D" + stan).length(), 12)));
        req.set(Field.TERMINAL_ID,           "DURTRM01");
        req.set(Field.MERCHANT_ID,           "DURMERCHANT    ");
        req.set(Field.MERCHANT_NAME,         "Duration Test Merchant                  ");
        req.set(Field.CURRENCY,              "840");
        sendFrame(out, req);
        ISOMsg resp = readFrame(in);
        return resp.getString(Field.RESPONSE_CODE);
    }

    private ISOMsg buildApproval(ISOMsg req) throws Exception {
        ISOMsg r = new ISOMsg();
        r.setPackager(PACKAGER);
        r.setMTI("0110");
        if (req.hasField(Field.PROCESSING_CODE))
            r.set(Field.PROCESSING_CODE, req.getString(Field.PROCESSING_CODE));
        if (req.hasField(Field.AMOUNT))
            r.set(Field.AMOUNT, req.getString(Field.AMOUNT));
        if (req.hasField(Field.TRANSMISSION_DATETIME))
            r.set(Field.TRANSMISSION_DATETIME, req.getString(Field.TRANSMISSION_DATETIME));
        r.set(Field.STAN, req.getString(Field.STAN));
        if (req.hasField(Field.LOCAL_TIME)) r.set(Field.LOCAL_TIME, req.getString(Field.LOCAL_TIME));
        if (req.hasField(Field.LOCAL_DATE)) r.set(Field.LOCAL_DATE, req.getString(Field.LOCAL_DATE));
        r.set(Field.ACQUIRING_INSTITUTION, ISS_CODE);
        if (req.hasField(Field.FORWARDING_INSTITUTION))
            r.set(Field.FORWARDING_INSTITUTION, req.getString(Field.FORWARDING_INSTITUTION));
        if (req.hasField(Field.RETRIEVAL_REF))
            r.set(Field.RETRIEVAL_REF, req.getString(Field.RETRIEVAL_REF));
        r.set(Field.AUTH_ID_RESPONSE, "DAPR01");
        r.set(Field.RESPONSE_CODE,    "00");
        if (req.hasField(Field.CURRENCY))
            r.set(Field.CURRENCY, req.getString(Field.CURRENCY));
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
                DurationTest.class.getResourceAsStream("/packager/iso87binary.xml")));
        msg.unpack(bytes);
        return msg;
    }

    private String nextStan() {
        return String.format("%06d", stanCounter.incrementAndGet() % 1_000_000L);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Single acquirer connection sends transactions continuously for 30 seconds.
     *
     * Measures throughput in 5-second windows and asserts:
     * - All responses are approved (RC=00)
     * - No window drops below 50% of the peak window TPS (no degradation over time)
     * - At least 6 complete windows were observed (≥30s of sustained flow)
     */
    @Test
    void sustainedLoad_30Seconds() throws Exception {
        final long TEST_DURATION_MS = 30_000L;

        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(60_000);
        seedInfrastructure(issuerServer.getLocalPort());

        AtomicBoolean stop = new AtomicBoolean(false);
        Thread issuerThread = startFakeIssuerUnbounded(issuerServer, stop);

        int approved = 0;
        int failed   = 0;
        List<Integer> windowCounts = new ArrayList<>();

        try (Socket sock = new Socket("localhost", GATEWAY_PORT)) {
            sock.setSoTimeout(20_000); // > gateway's 15 s response timeout
            OutputStream out = sock.getOutputStream();
            InputStream  in  = sock.getInputStream();

            signOn(out, in);

            Instant testEnd     = Instant.now().plusMillis(TEST_DURATION_MS);
            Instant windowStart = Instant.now();
            int     windowCount = 0;
            int     txnSeq      = 0;

            while (Instant.now().isBefore(testEnd)) {
                // unique PAN per transaction
                String pan = String.format("72000%09d", ++txnSeq);
                String rc  = sendAuth(out, in, pan, 10_000L);

                if ("00".equals(rc)) { approved++; windowCount++; }
                else                 { failed++; }

                // close current window every WINDOW_MS
                if (Duration.between(windowStart, Instant.now()).toMillis() >= WINDOW_MS) {
                    windowCounts.add(windowCount);
                    windowCount  = 0;
                    windowStart  = Instant.now();
                }
            }
            // flush partial final window if meaningful
            if (windowCount > 0) windowCounts.add(windowCount);
        }

        stop.set(true);
        issuerThread.join(10_000);
        issuerServer.close();
        sessionRegistry.deregisterByCode(ISS_CODE);

        int total = approved + failed;
        int peak  = windowCounts.stream().mapToInt(Integer::intValue).max().orElse(0);
        int low   = windowCounts.stream().mapToInt(Integer::intValue).min().orElse(0);

        System.out.printf(
                "[DurationTest] sustained: total=%d approved=%d failed=%d windows=%d " +
                "peak=%d/window low=%d/window%n",
                total, approved, failed, windowCounts.size(), peak, low);
        windowCounts.forEach(w ->
                System.out.printf("  window: %d txns (%.1f TPS)%n", w, w / (WINDOW_MS / 1000.0)));

        assertThat(failed).isZero();
        assertThat(windowCounts.size()).as("should have ≥6 complete windows").isGreaterThanOrEqualTo(6);
        // no window should drop below 50% of peak (no degradation)
        assertThat(low).as("min window TPS should be ≥50%% of peak").isGreaterThanOrEqualTo(peak / 2);
    }

    /**
     * 5 concurrent acquirer connections each send transactions for 20 seconds.
     *
     * Asserts:
     * - Overall approval rate ≥ 95%  (minor noise tolerated)
     * - At least 100 transactions processed across all clients
     * - Aggregate throughput logged per 5-second window
     */
    @Test
    void multiClient_20SecondSustained() throws Exception {
        final int    NUM_CLIENTS      = 5;
        final long   TEST_DURATION_MS = 20_000L;

        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(60_000);
        seedInfrastructure(issuerServer.getLocalPort());

        AtomicBoolean stop     = new AtomicBoolean(false);
        Thread issuerThread    = startFakeIssuerUnbounded(issuerServer, stop);

        AtomicInteger approved  = new AtomicInteger(0);
        AtomicInteger failed    = new AtomicInteger(0);
        // Shared window counter (approximate — updated without lock for performance)
        AtomicInteger[] windows = new AtomicInteger[4]; // 4 × 5s = 20s
        for (int i = 0; i < windows.length; i++) windows[i] = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(NUM_CLIENTS);
        Instant testStart         = Instant.now();
        ExecutorService pool      = Executors.newFixedThreadPool(NUM_CLIENTS);

        for (int c = 0; c < NUM_CLIENTS; c++) {
            final int clientId = c;
            pool.submit(() -> {
                try (Socket sock = new Socket("localhost", GATEWAY_PORT)) {
                    sock.setSoTimeout(20_000); // > gateway's 15 s response timeout
                    OutputStream out = sock.getOutputStream();
                    InputStream  in  = sock.getInputStream();

                    signOn(out, in);
                    startLatch.await(); // synchronised start

                    Instant end = Instant.now().plusMillis(TEST_DURATION_MS);
                    int txnSeq  = 0;
                    while (Instant.now().isBefore(end)) {
                        String pan = String.format("72500%03d%06d", clientId, ++txnSeq);
                        String rc  = sendAuth(out, in, pan, 8_000L);
                        if ("00".equals(rc)) {
                            approved.incrementAndGet();
                            // record into the appropriate window bucket
                            long elapsed = Duration.between(testStart, Instant.now()).toMillis();
                            int  bucket  = Math.min((int) (elapsed / WINDOW_MS), windows.length - 1);
                            windows[bucket].incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[duration-client-" + clientId + "] error: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
                return null;
            });
        }

        startLatch.countDown(); // release all clients
        doneLatch.await();

        stop.set(true);
        issuerThread.join(10_000);
        issuerServer.close();
        pool.shutdown();
        sessionRegistry.deregisterByCode(ISS_CODE);

        int total        = approved.get() + failed.get();
        double approvalRate = total > 0 ? (approved.get() * 100.0 / total) : 0.0;

        System.out.printf(
                "[DurationTest] multi-client: total=%d approved=%d failed=%d approval=%.1f%%%n",
                total, approved.get(), failed.get(), approvalRate);
        for (int i = 0; i < windows.length; i++) {
            System.out.printf("  window[%d]: %d txns (%.1f TPS across %d clients)%n",
                    i, windows[i].get(), windows[i].get() / (WINDOW_MS / 1000.0), NUM_CLIENTS);
        }

        assertThat(total).as("should process at least 100 transactions").isGreaterThanOrEqualTo(100);
        assertThat(approvalRate).as("approval rate should be ≥95%%").isGreaterThanOrEqualTo(95.0);
    }
}
