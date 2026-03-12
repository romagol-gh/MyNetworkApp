package com.network.transaction;

/** Enumeration of all supported Test Lab scenarios. */
public enum TestScenarioType {
    HAPPY_PATH(
        "Happy Path",
        "One authorized transaction — valid PAN, valid BIN, issuer approves.",
        "success",
        1, 10_000L, "5411"
    ),
    ISSUER_DECLINE(
        "Issuer Decline",
        "Issuer rejects the transaction with RC=05 (Do Not Honor).",
        "danger",
        1, 10_000L, "5411"
    ),
    BIN_NOT_FOUND(
        "BIN Not Found",
        "PAN outside all registered BIN ranges — network returns RC=15.",
        "warning",
        1, 10_000L, "5411"
    ),
    BLACKLISTED_CARD(
        "Blacklisted Card",
        "PAN on fraud blacklist — fraud engine declines RC=59 before contacting issuer.",
        "danger",
        1, 10_000L, "5411"
    ),
    HIGH_RISK_MCC(
        "High-Risk MCC",
        "MCC=7995 (gambling) triggers fraud FLAG — issuer still approves.",
        "warning",
        1, 10_000L, "7995"
    ),
    LARGE_AMOUNT(
        "Large Amount",
        "Amount > $5,000 threshold triggers fraud FLAG — issuer still approves.",
        "warning",
        1, 600_000L, "5411"
    ),
    VELOCITY_BREACH(
        "Velocity Breach",
        "7 rapid transactions on the same card trigger velocity FLAG.",
        "warning",
        7, 10_000L, "5411"
    ),
    DECLINE_VELOCITY(
        "Decline Velocity",
        "4 declines on same card triggers decline-velocity DECLINE (RC=59).",
        "danger",
        4, 10_000L, "5411"
    ),
    ECHO_TEST(
        "Echo Test",
        "0800 NMC=301 echo — network responds 0810 RC=00 immediately.",
        "info",
        1, 0L, "0000"
    ),
    REVERSAL(
        "Reversal",
        "Approve a transaction then reverse it with a 0400 — expects 0420 RC=00.",
        "info",
        2, 10_000L, "5411"
    ),
    VOLUME_LOAD(
        "Volume Load",
        "Configurable batch of sequential approvals — default 200 transactions.",
        "primary",
        200, 10_000L, "5411"
    ),
    MIXED_BATCH(
        "Mixed Batch",
        "8 transactions mixing happy path, BIN miss, fraud block, and large amount.",
        "secondary",
        8, 10_000L, "5411"
    );

    private final String displayName;
    private final String description;
    private final String badgeColour;  // Bootstrap contextual colour
    private final int defaultCount;
    private final long defaultAmountMinorUnits;
    private final String defaultMcc;

    TestScenarioType(String displayName, String description, String badgeColour,
                     int defaultCount, long defaultAmountMinorUnits, String defaultMcc) {
        this.displayName = displayName;
        this.description = description;
        this.badgeColour = badgeColour;
        this.defaultCount = defaultCount;
        this.defaultAmountMinorUnits = defaultAmountMinorUnits;
        this.defaultMcc = defaultMcc;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getBadgeColour() { return badgeColour; }
    public int getDefaultCount() { return defaultCount; }
    public long getDefaultAmountMinorUnits() { return defaultAmountMinorUnits; }
    public String getDefaultMcc() { return defaultMcc; }

    public TestScenarioConfig defaultConfig() {
        return new TestScenarioConfig(defaultCount, defaultAmountMinorUnits, defaultMcc,
                java.util.Map.of());
    }
}
