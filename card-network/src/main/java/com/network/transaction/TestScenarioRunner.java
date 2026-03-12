package com.network.transaction;

import com.network.domain.BinRange;
import com.network.domain.BlacklistedCard;
import com.network.domain.Participant;
import com.network.fraud.rule.BlacklistRule;
import com.network.gateway.SessionRegistry;
import com.network.iso8583.Field;
import com.network.repository.BinRangeRepository;
import com.network.repository.BlacklistedCardRepository;
import com.network.repository.ParticipantRepository;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executes Test Lab scenarios against the running gateway via TCP.
 *
 * Each scenario:
 *   - Creates a fake issuer ServerSocket (for scenarios that need one)
 *   - Updates the test issuer participant's port via ParticipantRepository
 *   - Opens an acquirer Socket to the gateway and performs a sign-on
 *   - Sends ISO 8583 requests and collects TestTransactionResult records
 *   - Cleans up the fake issuer channel from SessionRegistry when done
 *
 * The gateway port is injected via @Value so the runner works in both the
 * live application (port 8583) and @SpringBootTest tests (port 18586).
 */
@Component
public class TestScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(TestScenarioRunner.class);

    static final String ACQ_CODE = "100000001";
    static final String ISS_CODE = "200000001";

    // Scenario-specific PANs — each scenario uses a unique PAN to avoid cross-test interference
    private static final String PAN_HAPPY          = "5100000000001001";
    private static final String PAN_ISSUER_DECLINE  = "5100000000002001";
    private static final String PAN_BLACKLISTED    = "5100000000004001";
    private static final String PAN_HIGH_RISK_MCC  = "5100000000005001";
    private static final String PAN_LARGE_AMOUNT   = "5100000000006001";
    private static final String PAN_VELOCITY       = "5100000000007001";
    private static final String PAN_DECLINE_VEL    = "5100000000008001";
    private static final String PAN_REVERSAL       = "5100000000010001";

    @Value("${gateway.port:8583}")
    private int gatewayPort;

    private final ParticipantRepository       participantRepository;
    private final BinRangeRepository          binRangeRepository;
    private final BlacklistedCardRepository   blacklistedCardRepository;
    private final SessionRegistry             sessionRegistry;

    private final AtomicLong stanCounter = new AtomicLong(300_000L);

    private static final GenericPackager PACKAGER;
    static {
        try {
            PACKAGER = new GenericPackager(
                    TestScenarioRunner.class.getResourceAsStream("/packager/iso87binary.xml"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public TestScenarioRunner(ParticipantRepository participantRepository,
                              BinRangeRepository binRangeRepository,
                              BlacklistedCardRepository blacklistedCardRepository,
                              SessionRegistry sessionRegistry) {
        this.participantRepository     = participantRepository;
        this.binRangeRepository        = binRangeRepository;
        this.blacklistedCardRepository = blacklistedCardRepository;
        this.sessionRegistry           = sessionRegistry;
    }

    // ── Public dispatch ──────────────────────────────────────────────────────

    public List<TestTransactionResult> run(TestScenarioType scenario, TestScenarioConfig config) throws Exception {
        return switch (scenario) {
            case HAPPY_PATH       -> runHappyPath(config);
            case ISSUER_DECLINE   -> runIssuerDecline(config);
            case BIN_NOT_FOUND    -> runBinNotFound(config);
            case BLACKLISTED_CARD -> runBlacklistedCard(config);
            case HIGH_RISK_MCC    -> runHighRiskMcc(config);
            case LARGE_AMOUNT     -> runLargeAmount(config);
            case VELOCITY_BREACH  -> runVelocityBreach(config);
            case DECLINE_VELOCITY -> runDeclineVelocity(config);
            case ECHO_TEST        -> runEchoTest(config);
            case REVERSAL         -> runReversal(config);
            case VOLUME_LOAD      -> runVolumeLoad(config);
            case MIXED_BATCH      -> runMixedBatch(config);
        };
    }

    // ── Scenario implementations ─────────────────────────────────────────────

    private List<TestTransactionResult> runHappyPath(TestScenarioConfig config) throws Exception {
        return withFakeIssuer(1, "00", (out, in, results) -> {
            signOn(out, in);
            results.add(sendAuth(out, in, PAN_HAPPY, config.amountMinorUnits(), config.mcc(), "00"));
        });
    }

    private List<TestTransactionResult> runIssuerDecline(TestScenarioConfig config) throws Exception {
        return withFakeIssuer(1, "05", (out, in, results) -> {
            signOn(out, in);
            results.add(sendAuth(out, in, PAN_ISSUER_DECLINE, config.amountMinorUnits(), config.mcc(), "05"));
        });
    }

    private List<TestTransactionResult> runBinNotFound(TestScenarioConfig config) throws Exception {
        // PAN 9000... is outside all registered BIN ranges → RC=15, no fake issuer needed
        ensureTestInfrastructure(0);
        List<TestTransactionResult> results = new ArrayList<>();
        try (Socket sock = new Socket("localhost", gatewayPort)) {
            sock.setSoTimeout(15_000);
            OutputStream out = sock.getOutputStream();
            InputStream  in  = sock.getInputStream();
            signOn(out, in);
            results.add(sendAuth(out, in, "9000000000003001", config.amountMinorUnits(), config.mcc(), "15"));
        }
        return results;
    }

    private List<TestTransactionResult> runBlacklistedCard(TestScenarioConfig config) throws Exception {
        String panHash = BlacklistRule.sha256Hex(PAN_BLACKLISTED);
        BlacklistedCard card = new BlacklistedCard();
        card.setPanHash(panHash);
        card.setReason("Test: BLACKLISTED_CARD scenario");
        blacklistedCardRepository.save(card);
        try {
            ensureTestInfrastructure(0);
            List<TestTransactionResult> results = new ArrayList<>();
            try (Socket sock = new Socket("localhost", gatewayPort)) {
                sock.setSoTimeout(15_000);
                OutputStream out = sock.getOutputStream();
                InputStream  in  = sock.getInputStream();
                signOn(out, in);
                results.add(sendAuth(out, in, PAN_BLACKLISTED, config.amountMinorUnits(), config.mcc(), "59"));
            }
            return results;
        } finally {
            blacklistedCardRepository.findByPanHash(panHash).ifPresent(blacklistedCardRepository::delete);
        }
    }

    private List<TestTransactionResult> runHighRiskMcc(TestScenarioConfig config) throws Exception {
        // MCC=7995 triggers fraud FLAG (score=50), but issuer still approves
        String mcc = "7995";
        return withFakeIssuer(1, "00", (out, in, results) -> {
            signOn(out, in);
            results.add(sendAuth(out, in, PAN_HIGH_RISK_MCC, config.amountMinorUnits(), mcc, "00"));
        });
    }

    private List<TestTransactionResult> runLargeAmount(TestScenarioConfig config) throws Exception {
        // Amount > $5000 triggers fraud FLAG (score=50), but issuer still approves
        long amount = Math.max(config.amountMinorUnits(), 600_000L);
        return withFakeIssuer(1, "00", (out, in, results) -> {
            signOn(out, in);
            results.add(sendAuth(out, in, PAN_LARGE_AMOUNT, amount, config.mcc(), "00"));
        });
    }

    private List<TestTransactionResult> runVelocityBreach(TestScenarioConfig config) throws Exception {
        int count = Math.max(config.transactionCount(), 7);
        // All 7+ txns use same PAN; after txn #5 the velocity rule FLAGs (score=40)
        // but never DECLINES — issuer approves all
        String[] rcs = new String[count];
        Arrays.fill(rcs, "00");
        return withFakeIssuer(count, rcs, (out, in, results) -> {
            signOn(out, in);
            for (String rc : rcs) {
                results.add(sendAuth(out, in, PAN_VELOCITY, config.amountMinorUnits(), config.mcc(), rc));
            }
        });
    }

    private List<TestTransactionResult> runDeclineVelocity(TestScenarioConfig config) throws Exception {
        // First 3 txns → issuer declines RC=05; 4th → fraud engine blocks RC=59
        return withFakeIssuer(3, "05", (out, in, results) -> {
            signOn(out, in);
            for (int i = 0; i < 3; i++) {
                results.add(sendAuth(out, in, PAN_DECLINE_VEL, config.amountMinorUnits(), config.mcc(), "05"));
            }
            // 4th is blocked by DeclineVelocityRule — does NOT reach the fake issuer
            results.add(sendAuth(out, in, PAN_DECLINE_VEL, config.amountMinorUnits(), config.mcc(), "59"));
        });
    }

    private List<TestTransactionResult> runEchoTest(TestScenarioConfig config) throws Exception {
        ensureTestInfrastructure(0);
        List<TestTransactionResult> results = new ArrayList<>();
        try (Socket sock = new Socket("localhost", gatewayPort)) {
            sock.setSoTimeout(15_000);
            OutputStream out = sock.getOutputStream();
            InputStream  in  = sock.getInputStream();

            String stan = nextStan();
            ISOMsg echo = new ISOMsg();
            echo.setPackager(PACKAGER);
            echo.setMTI("0800");
            echo.set(Field.STAN, stan);
            echo.set(Field.TRANSMISSION_DATETIME, "0301120000");
            echo.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
            echo.set(Field.NETWORK_MGMT_CODE, "301");
            sendFrame(out, echo);

            ISOMsg resp = readFrame(in);
            String rc = resp.getString(Field.RESPONSE_CODE);
            boolean passed = "0810".equals(resp.getMTI()) && "00".equals(rc);
            results.add(new TestTransactionResult(null, stan, 0, resp.getMTI(), rc, passed,
                    passed ? null : "Expected 0810 RC=00, got " + resp.getMTI() + " RC=" + rc));
        }
        return results;
    }

    private List<TestTransactionResult> runReversal(TestScenarioConfig config) throws Exception {
        // 2 issuer messages: 0110 RC=00 (auth), 0420 RC=00 (reversal)
        return withFakeIssuer(2, "00", (out, in, results) -> {
            signOn(out, in);

            // 1. Authorize
            String stan       = nextStan();
            String retrievalRef = "REVTEST" + stan;
            ISOMsg authReq = buildAuthRequest(PAN_REVERSAL, stan, config.amountMinorUnits(), config.mcc(), retrievalRef);
            sendFrame(out, authReq);
            ISOMsg authResp = readFrame(in);
            String authRc = authResp.getString(Field.RESPONSE_CODE);
            boolean authPassed = "00".equals(authRc);
            results.add(new TestTransactionResult(
                    PAN_REVERSAL, stan, config.amountMinorUnits(),
                    authResp.getMTI(), authRc, authPassed,
                    authPassed ? null : "Auth expected RC=00 got RC=" + authRc));

            if (!authPassed) return; // can't reverse a failed auth

            // 2. Reverse
            Thread.sleep(200); // allow gateway to complete the auth transaction

            String revStan = nextStan();
            ISOMsg revReq = new ISOMsg();
            revReq.setPackager(PACKAGER);
            revReq.setMTI("0400");
            revReq.set(Field.PROCESSING_CODE,       "000000");
            revReq.set(Field.AMOUNT,                formatAmount(config.amountMinorUnits()));
            revReq.set(Field.TRANSMISSION_DATETIME, "0301120010");
            revReq.set(Field.STAN,                  revStan);
            revReq.set(Field.LOCAL_TIME,            "120010");
            revReq.set(Field.LOCAL_DATE,            "0301");
            revReq.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
            revReq.set(Field.RETRIEVAL_REF,         retrievalRef.substring(0, Math.min(retrievalRef.length(), 12)));
            sendFrame(out, revReq);
            ISOMsg revResp = readFrame(in);
            String revRc = revResp.getString(Field.RESPONSE_CODE);
            boolean revPassed = "00".equals(revRc);
            results.add(new TestTransactionResult(
                    PAN_REVERSAL, revStan, config.amountMinorUnits(),
                    revResp.getMTI(), revRc, revPassed,
                    revPassed ? null : "Reversal expected RC=00 got RC=" + revRc));
        });
    }

    private List<TestTransactionResult> runVolumeLoad(TestScenarioConfig config) throws Exception {
        int count = Math.max(config.transactionCount(), 1);
        String[] rcs = new String[count];
        Arrays.fill(rcs, "00");
        return withFakeIssuer(count, rcs, (out, in, results) -> {
            signOn(out, in);
            for (int i = 0; i < count; i++) {
                // Use distinct PANs to avoid velocity accumulation
                String pan = String.format("510000%010d", i + 1);
                results.add(sendAuth(out, in, pan, config.amountMinorUnits(), config.mcc(), "00"));
            }
        });
    }

    private List<TestTransactionResult> runMixedBatch(TestScenarioConfig config) throws Exception {
        // Txn 4 is blacklisted — add to blacklist, clean up after
        String blacklistedPan = "5100000000012004";
        String panHash = BlacklistRule.sha256Hex(blacklistedPan);
        BlacklistedCard card = new BlacklistedCard();
        card.setPanHash(panHash);
        card.setReason("Test: MIXED_BATCH scenario (txn 4)");
        blacklistedCardRepository.save(card);

        try {
            // Issuer handles txns: 1, 3, 5, 6, 7, 8 (not 2=BIN miss, not 4=fraud block)
            return withFakeIssuer(6, "00", (out, in, results) -> {
                signOn(out, in);

                long largeAmount = Math.max(config.amountMinorUnits(), 600_000L);

                // Txn 1: happy path
                results.add(sendAuth(out, in, "5100000000012001", config.amountMinorUnits(), config.mcc(), "00"));
                // Txn 2: BIN not found (9000... outside range)
                results.add(sendAuth(out, in, "9000000000012002", config.amountMinorUnits(), config.mcc(), "15"));
                // Txn 3: large amount → fraud FLAG, issuer approves
                results.add(sendAuth(out, in, "5100000000012003", largeAmount, config.mcc(), "00"));
                // Txn 4: blacklisted → fraud DECLINE RC=59, no issuer contact
                results.add(sendAuth(out, in, blacklistedPan, config.amountMinorUnits(), config.mcc(), "59"));
                // Txns 5-7: happy path
                results.add(sendAuth(out, in, "5100000000012005", config.amountMinorUnits(), config.mcc(), "00"));
                results.add(sendAuth(out, in, "5100000000012006", config.amountMinorUnits(), config.mcc(), "00"));
                results.add(sendAuth(out, in, "5100000000012007", config.amountMinorUnits(), config.mcc(), "00"));
                // Txn 8: high-risk MCC → fraud FLAG, issuer approves
                results.add(sendAuth(out, in, "5100000000012008", config.amountMinorUnits(), "7995", "00"));
            });
        } finally {
            blacklistedCardRepository.findByPanHash(panHash).ifPresent(blacklistedCardRepository::delete);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Run a scenario that needs a fake issuer.
     * The issuer responds with the same RC for all messages.
     */
    private List<TestTransactionResult> withFakeIssuer(int issuerMsgCount, String rc, AcquirerAction action)
            throws Exception {
        String[] rcs = new String[issuerMsgCount];
        Arrays.fill(rcs, rc);
        return withFakeIssuer(issuerMsgCount, rcs, action);
    }

    /**
     * Run a scenario that needs a fake issuer.
     * The issuer responds with the given RCs (one per message, in order).
     */
    private List<TestTransactionResult> withFakeIssuer(int issuerMsgCount, String[] issuerRcs, AcquirerAction action)
            throws Exception {
        ServerSocket issuerServer = new ServerSocket(0);
        issuerServer.setSoTimeout(30_000);
        int issuerPort = issuerServer.getLocalPort();

        ensureTestInfrastructure(issuerPort);

        Thread issuerThread = startFakeIssuer(issuerServer, issuerRcs);

        List<TestTransactionResult> results = new ArrayList<>();
        try {
            try (Socket sock = new Socket("localhost", gatewayPort)) {
                sock.setSoTimeout(60_000);
                action.run(sock.getOutputStream(), sock.getInputStream(), results);
            }
        } finally {
            issuerThread.join(15_000);
            issuerServer.close();
            sessionRegistry.deregisterByCode(ISS_CODE); // clear stale channel
        }
        return results;
    }

    /** Start a fake issuer thread that responds to exactly rcs.length messages. */
    private Thread startFakeIssuer(ServerSocket server, String[] rcs) {
        Thread t = new Thread(() -> {
            try (Socket conn = server.accept()) {
                conn.setSoTimeout(30_000);
                for (String rc : rcs) {
                    try {
                        ISOMsg req = readFrame(conn.getInputStream());
                        ISOMsg resp = buildIssuerResponse(req, rc);
                        sendFrame(conn.getOutputStream(), resp);
                    } catch (java.net.SocketTimeoutException e) {
                        log.warn("Fake issuer: SocketTimeout waiting for message");
                        break;
                    }
                }
                Thread.sleep(1_000); // keep alive until gateway reads the last response
            } catch (Exception e) {
                if (!(e instanceof java.net.SocketTimeoutException)) {
                    log.error("Fake issuer error: {}", e.getMessage(), e);
                }
            }
        }, "fake-issuer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Send an auth request and return a result comparing actual RC to expectedRc. */
    private TestTransactionResult sendAuth(OutputStream out, InputStream in,
                                           String pan, long amount, String mcc, String expectedRc)
            throws Exception {
        String stan = nextStan();
        ISOMsg req = buildAuthRequest(pan, stan, amount, mcc, "REF" + stan);
        sendFrame(out, req);
        ISOMsg resp = readFrame(in);
        String actualRc = resp.getString(Field.RESPONSE_CODE);
        boolean passed = expectedRc.equals(actualRc);
        return new TestTransactionResult(pan, stan, amount, resp.getMTI(), actualRc, passed,
                passed ? null : "Expected RC=" + expectedRc + " got RC=" + actualRc);
    }

    /** Send 0800 NMC=001 sign-on and assert 0810 RC=00. */
    private void signOn(OutputStream out, InputStream in) throws Exception {
        ISOMsg signOn = new ISOMsg();
        signOn.setPackager(PACKAGER);
        signOn.setMTI("0800");
        signOn.set(Field.STAN,                  nextStan());
        signOn.set(Field.TRANSMISSION_DATETIME, "0301120000");
        signOn.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
        signOn.set(Field.NETWORK_MGMT_CODE,     "001");
        sendFrame(out, signOn);

        ISOMsg resp = readFrame(in);
        if (!"0810".equals(resp.getMTI()) || !"00".equals(resp.getString(Field.RESPONSE_CODE))) {
            throw new IllegalStateException("Sign-on failed: MTI=" + resp.getMTI() +
                    " RC=" + resp.getString(Field.RESPONSE_CODE));
        }
    }

    /** Upsert test participants and BIN range. Updates issuer port if > 0. */
    void ensureTestInfrastructure(int issuerPort) {
        if (!participantRepository.existsByCode(ACQ_CODE)) {
            Participant acq = new Participant();
            acq.setName("Test Acquiring Bank");
            acq.setCode(ACQ_CODE);
            acq.setType(Participant.Type.ACQUIRER);
            acq.setStatus(Participant.Status.ACTIVE);
            participantRepository.save(acq);
        }

        Participant iss = participantRepository.findByCode(ISS_CODE).orElseGet(() -> {
            Participant p = new Participant();
            p.setName("Test Issuer Bank");
            p.setCode(ISS_CODE);
            p.setType(Participant.Type.ISSUER);
            p.setStatus(Participant.Status.ACTIVE);
            return participantRepository.save(p);
        });

        if (issuerPort > 0) {
            iss.setHost("localhost");
            iss.setPort(issuerPort);
            participantRepository.save(iss);
        }

        if (!binRangeRepository.existsByLowAndHigh("510000", "519999")) {
            BinRange range = new BinRange();
            range.setLow("510000");
            range.setHigh("519999");
            range.setIssuer(iss);
            binRangeRepository.save(range);
        }
    }

    // ── ISO 8583 helpers ─────────────────────────────────────────────────────

    private ISOMsg buildAuthRequest(String pan, String stan, long amount, String mcc, String retrievalRef)
            throws Exception {
        ISOMsg m = new ISOMsg();
        m.setPackager(PACKAGER);
        m.setMTI("0100");
        m.set(Field.PAN,                   pan);
        m.set(Field.PROCESSING_CODE,       "000000");
        m.set(Field.AMOUNT,                formatAmount(amount));
        m.set(Field.TRANSMISSION_DATETIME, "0301120005");
        m.set(Field.STAN,                  stan);
        m.set(Field.LOCAL_TIME,            "120005");
        m.set(Field.LOCAL_DATE,            "0301");
        m.set(Field.MCC,                   mcc);
        m.set(Field.POS_ENTRY_MODE,        "051");
        m.set(Field.POS_CONDITION_CODE,    "00");
        m.set(Field.ACQUIRING_INSTITUTION, ACQ_CODE);
        m.set(Field.RETRIEVAL_REF,         retrievalRef.substring(0, Math.min(retrievalRef.length(), 12)));
        m.set(Field.TERMINAL_ID,           "TERM0001");
        m.set(Field.MERCHANT_ID,           "TESTMERCHANT  ");
        m.set(Field.MERCHANT_NAME,         "Test Merchant                           ");
        m.set(Field.CURRENCY,              "840");
        return m;
    }

    private ISOMsg buildIssuerResponse(ISOMsg req, String rc) throws Exception {
        String reqMti  = req.getMTI();
        String respMti = switch (reqMti) {
            case "0100" -> "0110";
            case "0200" -> "0210";
            case "0400" -> "0420";
            default     -> "0110";
        };

        ISOMsg r = new ISOMsg();
        r.setPackager(PACKAGER);
        r.setMTI(respMti);
        if (req.hasField(Field.PROCESSING_CODE))
            r.set(Field.PROCESSING_CODE, req.getString(Field.PROCESSING_CODE));
        if (req.hasField(Field.AMOUNT))
            r.set(Field.AMOUNT, req.getString(Field.AMOUNT));
        if (req.hasField(Field.TRANSMISSION_DATETIME))
            r.set(Field.TRANSMISSION_DATETIME, req.getString(Field.TRANSMISSION_DATETIME));
        r.set(Field.STAN,                  req.getString(Field.STAN));
        if (req.hasField(Field.LOCAL_TIME))
            r.set(Field.LOCAL_TIME, req.getString(Field.LOCAL_TIME));
        if (req.hasField(Field.LOCAL_DATE))
            r.set(Field.LOCAL_DATE, req.getString(Field.LOCAL_DATE));
        r.set(Field.ACQUIRING_INSTITUTION, ISS_CODE);
        if (req.hasField(Field.FORWARDING_INSTITUTION))
            r.set(Field.FORWARDING_INSTITUTION, req.getString(Field.FORWARDING_INSTITUTION));
        if (req.hasField(Field.RETRIEVAL_REF))
            r.set(Field.RETRIEVAL_REF, req.getString(Field.RETRIEVAL_REF));
        if ("00".equals(rc) && "0110".equals(respMti))
            r.set(Field.AUTH_ID_RESPONSE, "TEST01");
        r.set(Field.RESPONSE_CODE, rc);
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
                TestScenarioRunner.class.getResourceAsStream("/packager/iso87binary.xml")));
        msg.unpack(bytes);
        return msg;
    }

    private String nextStan() {
        return String.format("%06d", stanCounter.incrementAndGet() % 1_000_000L);
    }

    private static String formatAmount(long minorUnits) {
        return String.format("%012d", minorUnits);
    }

    @FunctionalInterface
    private interface AcquirerAction {
        void run(OutputStream out, InputStream in, List<TestTransactionResult> results) throws Exception;
    }
}
