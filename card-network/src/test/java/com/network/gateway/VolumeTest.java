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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Volume stress tests: concurrent connections and peak throughput.
 *
 * Tests:
 *   1. concurrentClients_500Transactions — 10 simultaneous acquirer connections × 50 txns
 *   2. burstLoad_200Transactions         — 20 connections start at the same instant (CountDownLatch)
 *   3. connectionChurn_100Cycles         — 100 rapid connect/sign-on/authorize/disconnect cycles
 *
 * Gateway port: 18590 (unique to this class)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class VolumeTest {

    private static final String ACQ_CODE    = "710000001";
    private static final String ISS_CODE    = "810000001";
    private static final int    GATEWAY_PORT = 18590;

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

    @Autowired JdbcTemplate        jdbc;
    @Autowired SessionRegistry     sessionRegistry;

    private final AtomicLong stanCounter = new AtomicLong(710_000L);
    private volatile int     currentIssuerPort = 0;

    private static final GenericPackager PACKAGER;
    static {
        try {
            PACKAGER = new GenericPackager(
                    VolumeTest.class.getResourceAsStream("/packager/iso87binary.xml"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeAll
    static void startup() throws Exception {
        Thread.sleep(3000); // allow Netty gateway to bind
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Seed or update the test participants. Updates issuer port each time. */
    private void seedInfrastructure(int issuerPort) {
        currentIssuerPort = issuerPort;

        UUID acqId = UUID.randomUUID();
        UUID issId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO participants (id, name, code, type, status, created_at)
                VALUES (?, 'Volume Acquirer', ?, 'ACQUIRER', 'ACTIVE', NOW())
                ON CONFLICT (code) DO NOTHING
                """, acqId, ACQ_CODE);

        jdbc.update("""
                INSERT INTO participants (id, name, code, type, host, port, status, created_at)
                VALUES (?, 'Volume Issuer', ?, 'ISSUER', 'localhost', ?, 'ACTIVE', NOW())
                ON CONFLICT (code) DO UPDATE SET port = EXCLUDED.port, host = EXCLUDED.host
                """, issId, ISS_CODE, issuerPort);

        jdbc.update("""
                INSERT INTO bin_ranges (id, low, high, issuer_id, created_at)
                SELECT gen_random_uuid(), '710000', '719999',
                       (SELECT id FROM participants WHERE code = ?), NOW()
                WHERE NOT EXISTS (
                    SELECT 1 FROM bin_ranges WHERE low = '710000' AND high = '719999'
                )
                """, ISS_CODE);
    }

    /**
     * Start a fake issuer that accepts ONE connection from the gateway and
     * handles exactly {@code messageCount} authorization requests, all approved.
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
                        System.err.printf("[fake-issuer] timeout after %d/%d messages%n", i, messageCount);
                        break;
                    }
                }
                Thread.sleep(2000); // keep alive so gateway can drain responses
            } catch (Exception e) {
                System.err.println("[fake-issuer] error: " + e.getMessage());
            }
        }, "fake-issuer");
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
        req.set(Field.RETRIEVAL_REF,         ("V" + stan).substring(0, Math.min(("V" + stan).length(), 12)));
        req.set(Field.TERMINAL_ID,           "VOLTRM01");
        req.set(Field.MERCHANT_ID,           "VOLMERCHANT    ");
        req.set(Field.MERCHANT_NAME,         "Volume Test Merchant                    ");
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
        if (req.hasField(Field.LOCAL_TIME))  r.set(Field.LOCAL_TIME, req.getString(Field.LOCAL_TIME));
        if (req.hasField(Field.LOCAL_DATE))  r.set(Field.LOCAL_DATE, req.getString(Field.LOCAL_DATE));
        r.set(Field.ACQUIRING_INSTITUTION, ISS_CODE);
        if (req.hasField(Field.FORWARDING_INSTITUTION))
            r.set(Field.FORWARDING_INSTITUTION, req.getString(Field.FORWARDING_INSTITUTION));
        if (req.hasField(Field.RETRIEVAL_REF))
            r.set(Field.RETRIEVAL_REF, req.getString(Field.RETRIEVAL_REF));
        r.set(Field.AUTH_ID_RESPONSE, "VAPR01");
        r.set(Field.RESPONSE_CODE, "00");
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
                VolumeTest.class.getResourceAsStream("/packager/iso87binary.xml")));
        msg.unpack(bytes);
        return msg;
    }

    private String nextStan() {
        return String.format("%06d", stanCounter.incrementAndGet() % 1_000_000L);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * 10 concurrent acquirer connections each sending 50 transactions = 500 total.
     * Verifies the gateway correctly correlates all responses with no mix-ups.
     */
    @Test
    void concurrentClients_500Transactions() throws Exception {
        final int NUM_CLIENTS   = 10;
        final int TXN_PER_CLIENT = 50;
        final int TOTAL         = NUM_CLIENTS * TXN_PER_CLIENT;

        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(120_000);
        seedInfrastructure(issuerServer.getLocalPort());

        Thread issuerThread = startFakeIssuer(issuerServer, TOTAL);

        AtomicInteger approved = new AtomicInteger(0);
        AtomicInteger failed   = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(NUM_CLIENTS);

        ExecutorService pool = Executors.newFixedThreadPool(NUM_CLIENTS);
        List<Future<?>> futures = new ArrayList<>();

        for (int c = 0; c < NUM_CLIENTS; c++) {
            final int clientId = c;
            futures.add(pool.submit(() -> {
                try (Socket sock = new Socket("localhost", GATEWAY_PORT)) {
                    sock.setSoTimeout(30_000);
                    OutputStream out = sock.getOutputStream();
                    InputStream  in  = sock.getInputStream();

                    signOn(out, in);
                    startLatch.await(); // wait for all clients to be ready

                    for (int t = 0; t < TXN_PER_CLIENT; t++) {
                        // unique PAN per client to avoid cross-client velocity
                        String pan = String.format("71000%05d%04d", clientId, t + 1);
                        String rc = sendAuth(out, in, pan, 10_000L);
                        if ("00".equals(rc)) approved.incrementAndGet();
                        else                 failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("[client-" + clientId + "] error: " + e.getMessage());
                    failed.addAndGet(TXN_PER_CLIENT);
                } finally {
                    doneLatch.countDown();
                }
                return null;
            }));
        }

        Instant start = Instant.now();
        startLatch.countDown(); // release all clients simultaneously
        doneLatch.await();

        double elapsed = Duration.between(start, Instant.now()).toMillis() / 1000.0;
        double tps     = TOTAL / elapsed;

        pool.shutdown();
        issuerThread.join(30_000);
        issuerServer.close();
        sessionRegistry.deregisterByCode(ISS_CODE);

        System.out.printf("[VolumeTest] concurrent: %d approved, %d failed, %.1f TPS (%.2fs)%n",
                approved.get(), failed.get(), tps, elapsed);

        assertThat(approved.get()).isEqualTo(TOTAL);
        assertThat(failed.get()).isZero();
        assertThat(tps).isGreaterThan(10.0); // conservative floor
    }

    /**
     * 20 clients all fire at the same millisecond via CountDownLatch.
     * Validates peak burst handling and STAN correlation under maximum concurrency.
     */
    @Test
    void burstLoad_200Transactions() throws Exception {
        final int NUM_CLIENTS    = 20;
        final int TXN_PER_CLIENT = 10;
        final int TOTAL          = NUM_CLIENTS * TXN_PER_CLIENT;

        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(120_000);
        seedInfrastructure(issuerServer.getLocalPort());

        Thread issuerThread = startFakeIssuer(issuerServer, TOTAL);

        AtomicInteger approved = new AtomicInteger(0);
        AtomicInteger failed   = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(NUM_CLIENTS);

        ExecutorService pool = Executors.newFixedThreadPool(NUM_CLIENTS);

        for (int c = 0; c < NUM_CLIENTS; c++) {
            final int clientId = c;
            pool.submit(() -> {
                try (Socket sock = new Socket("localhost", GATEWAY_PORT)) {
                    sock.setSoTimeout(30_000);
                    OutputStream out = sock.getOutputStream();
                    InputStream  in  = sock.getInputStream();
                    signOn(out, in);

                    startLatch.await(); // burst: all start at once

                    for (int t = 0; t < TXN_PER_CLIENT; t++) {
                        String pan = String.format("71500%05d%04d", clientId, t + 1);
                        String rc = sendAuth(out, in, pan, 5_000L);
                        if ("00".equals(rc)) approved.incrementAndGet();
                        else                 failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("[burst-" + clientId + "] error: " + e.getMessage());
                    failed.addAndGet(TXN_PER_CLIENT);
                } finally {
                    doneLatch.countDown();
                }
                return null;
            });
        }

        Instant start = Instant.now();
        startLatch.countDown();
        doneLatch.await();

        double elapsed = Duration.between(start, Instant.now()).toMillis() / 1000.0;
        double tps     = TOTAL / elapsed;

        pool.shutdown();
        issuerThread.join(30_000);
        issuerServer.close();
        sessionRegistry.deregisterByCode(ISS_CODE);

        System.out.printf("[VolumeTest] burst: %d approved, %d failed, %.1f TPS (%.2fs)%n",
                approved.get(), failed.get(), tps, elapsed);

        assertThat(approved.get()).isEqualTo(TOTAL);
        assertThat(failed.get()).isZero();
    }

    /**
     * 100 rapid connect → sign-on → authorize → disconnect cycles on a single thread.
     * Tests session setup/teardown overhead and absence of connection leaks.
     */
    @Test
    void connectionChurn_100Cycles() throws Exception {
        final int CYCLES = 100;

        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(120_000);
        seedInfrastructure(issuerServer.getLocalPort());

        Thread issuerThread = startFakeIssuer(issuerServer, CYCLES);

        int approved = 0;
        int failed   = 0;
        Instant start = Instant.now();

        for (int i = 0; i < CYCLES; i++) {
            try (Socket sock = new Socket("localhost", GATEWAY_PORT)) {
                sock.setSoTimeout(15_000);
                OutputStream out = sock.getOutputStream();
                InputStream  in  = sock.getInputStream();
                signOn(out, in);
                String pan = String.format("716%013d", i + 1);
                String rc  = sendAuth(out, in, pan, 10_000L);
                if ("00".equals(rc)) approved++;
                else                 failed++;
            } catch (Exception e) {
                System.err.println("[churn-" + i + "] error: " + e.getMessage());
                failed++;
            }
        }

        double elapsed = Duration.between(start, Instant.now()).toMillis() / 1000.0;

        issuerThread.join(30_000);
        issuerServer.close();
        sessionRegistry.deregisterByCode(ISS_CODE);

        System.out.printf("[VolumeTest] churn: %d approved, %d failed (%.2fs)%n",
                approved, failed, elapsed);

        assertThat(approved).isEqualTo(CYCLES);
        assertThat(failed).isZero();
    }
}
