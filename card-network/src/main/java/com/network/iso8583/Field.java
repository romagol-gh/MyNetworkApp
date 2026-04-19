package com.network.iso8583;

/** ISO 8583 Data Element (DE) index constants */
public final class Field {

    private Field() {}

    public static final int PAN                    = 2;   // Primary Account Number
    public static final int PROCESSING_CODE        = 3;   // Processing Code
    public static final int AMOUNT                 = 4;   // Transaction Amount
    public static final int TRANSMISSION_DATETIME  = 7;   // MMDDhhmmss
    public static final int STAN                   = 11;  // System Trace Audit Number
    public static final int LOCAL_TIME             = 12;  // hhmmss
    public static final int LOCAL_DATE             = 13;  // MMDD
    public static final int EXPIRATION_DATE        = 14;  // YYMM
    public static final int MCC                    = 18;  // Merchant Category Code
    public static final int POS_ENTRY_MODE         = 22;  // Point of Service Entry Mode
    public static final int POS_CONDITION_CODE     = 25;  // Point of Service Condition Code
    public static final int ACQUIRING_INSTITUTION  = 32;  // Acquiring Institution ID
    public static final int FORWARDING_INSTITUTION = 33;  // Forwarding Institution ID
    public static final int RETRIEVAL_REF          = 37;  // Retrieval Reference Number
    public static final int AUTH_ID_RESPONSE       = 38;  // Authorization ID Response
    public static final int RESPONSE_CODE          = 39;  // Response Code
    public static final int TERMINAL_ID            = 41;  // Card Acceptor Terminal ID
    public static final int MERCHANT_ID            = 42;  // Card Acceptor ID Code
    public static final int MERCHANT_NAME          = 43;  // Card Acceptor Name/Location
    public static final int ADDITIONAL_DATA        = 48;  // Additional Data — Private (agent context)
    public static final int CURRENCY               = 49;  // Transaction Currency Code
    public static final int NETWORK_MGMT_CODE      = 70;  // Network Management Information Code
    public static final int REPLACEMENT_AMOUNTS    = 95;  // Replacement Amounts (reversals)
}
