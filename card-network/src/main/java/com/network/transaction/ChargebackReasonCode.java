package com.network.transaction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Visa and Mastercard chargeback reason code constants and descriptions.
 */
public final class ChargebackReasonCode {

    private ChargebackReasonCode() {}

    // ── Visa Reason Codes ──────────────────────────────────────────────────────

    /** Fraud — EMV Liability Shift Counterfeit Fraud */
    public static final String VISA_10_1 = "10.1";
    /** Fraud — EMV Liability Shift Non-Counterfeit Fraud */
    public static final String VISA_10_2 = "10.2";
    /** Fraud — Other Fraud - Card-Present Environment */
    public static final String VISA_10_3 = "10.3";
    /** Fraud — Other Fraud - Card-Absent Environment */
    public static final String VISA_10_4 = "10.4";
    /** Fraud — Visa Fraud Monitoring Program */
    public static final String VISA_10_5 = "10.5";
    /** Authorization — Card Recovery Bulletin */
    public static final String VISA_11_1 = "11.1";
    /** Authorization — Declined Authorization */
    public static final String VISA_11_2 = "11.2";
    /** Authorization — No Authorization */
    public static final String VISA_11_3 = "11.3";
    /** Processing Error — Late Presentment */
    public static final String VISA_12_1 = "12.1";
    /** Processing Error — Incorrect Transaction Code */
    public static final String VISA_12_2 = "12.2";
    /** Processing Error — Incorrect Currency */
    public static final String VISA_12_3 = "12.3";
    /** Processing Error — Incorrect Account Number */
    public static final String VISA_12_4 = "12.4";
    /** Processing Error — Incorrect Amount */
    public static final String VISA_12_5 = "12.5";
    /** Processing Error — Duplicate Processing / Paid by Other Means */
    public static final String VISA_12_6 = "12.6";
    /** Processing Error — Invalid Data */
    public static final String VISA_12_7 = "12.7";
    /** Consumer Dispute — Merchandise / Services Not Received */
    public static final String VISA_13_1 = "13.1";
    /** Consumer Dispute — Cancelled Recurring Transaction */
    public static final String VISA_13_2 = "13.2";
    /** Consumer Dispute — Not as Described or Defective Merchandise / Services */
    public static final String VISA_13_3 = "13.3";
    /** Consumer Dispute — Counterfeit Merchandise */
    public static final String VISA_13_4 = "13.4";
    /** Consumer Dispute — Misrepresentation */
    public static final String VISA_13_5 = "13.5";
    /** Consumer Dispute — Credit Not Processed */
    public static final String VISA_13_6 = "13.6";
    /** Consumer Dispute — Cancelled Merchandise / Services */
    public static final String VISA_13_7 = "13.7";
    /** Consumer Dispute — Original Credit Transaction Not Accepted */
    public static final String VISA_13_8 = "13.8";
    /** Consumer Dispute — Non-Receipt of Cash or Load Transaction Value */
    public static final String VISA_13_9 = "13.9";

    // ── Mastercard Reason Codes ────────────────────────────────────────────────

    /** Requested / Required Authorization Not Obtained */
    public static final String MC_4808 = "4808";
    /** Transaction Amount Differs */
    public static final String MC_4831 = "4831";
    /** Point-of-Interaction Error */
    public static final String MC_4834 = "4834";
    /** No Cardholder Authorization */
    public static final String MC_4837 = "4837";
    /** Fraudulent Processing of Transactions */
    public static final String MC_4840 = "4840";
    /** Cancelled Recurring or Digital Goods Transactions */
    public static final String MC_4841 = "4841";
    /** Late Presentment */
    public static final String MC_4842 = "4842";
    /** Cardholder Dispute — Defective / Not as Described */
    public static final String MC_4853 = "4853";
    /** Cardholder Does Not Recognize — Potential Fraud */
    public static final String MC_4863 = "4863";
    /** Chip Liability Shift */
    public static final String MC_4870 = "4870";
    /** Chip / PIN Liability Shift */
    public static final String MC_4871 = "4871";
    /** Domestic Chargeback Dispute */
    public static final String MC_4999 = "4999";

    // ── Description map ───────────────────────────────────────────────────────

    public static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    static {
        DESCRIPTIONS.put(VISA_10_1, "EMV Liability Shift — Counterfeit Fraud");
        DESCRIPTIONS.put(VISA_10_2, "EMV Liability Shift — Non-Counterfeit Fraud");
        DESCRIPTIONS.put(VISA_10_3, "Other Fraud — Card-Present Environment");
        DESCRIPTIONS.put(VISA_10_4, "Other Fraud — Card-Absent Environment");
        DESCRIPTIONS.put(VISA_10_5, "Visa Fraud Monitoring Program");
        DESCRIPTIONS.put(VISA_11_1, "Card Recovery Bulletin");
        DESCRIPTIONS.put(VISA_11_2, "Declined Authorization");
        DESCRIPTIONS.put(VISA_11_3, "No Authorization");
        DESCRIPTIONS.put(VISA_12_1, "Late Presentment");
        DESCRIPTIONS.put(VISA_12_2, "Incorrect Transaction Code");
        DESCRIPTIONS.put(VISA_12_3, "Incorrect Currency");
        DESCRIPTIONS.put(VISA_12_4, "Incorrect Account Number");
        DESCRIPTIONS.put(VISA_12_5, "Incorrect Amount");
        DESCRIPTIONS.put(VISA_12_6, "Duplicate Processing / Paid by Other Means");
        DESCRIPTIONS.put(VISA_12_7, "Invalid Data");
        DESCRIPTIONS.put(VISA_13_1, "Merchandise / Services Not Received");
        DESCRIPTIONS.put(VISA_13_2, "Cancelled Recurring Transaction");
        DESCRIPTIONS.put(VISA_13_3, "Not as Described or Defective Merchandise");
        DESCRIPTIONS.put(VISA_13_4, "Counterfeit Merchandise");
        DESCRIPTIONS.put(VISA_13_5, "Misrepresentation");
        DESCRIPTIONS.put(VISA_13_6, "Credit Not Processed");
        DESCRIPTIONS.put(VISA_13_7, "Cancelled Merchandise / Services");
        DESCRIPTIONS.put(VISA_13_8, "Original Credit Transaction Not Accepted");
        DESCRIPTIONS.put(VISA_13_9, "Non-Receipt of Cash or Load Transaction Value");
        DESCRIPTIONS.put(MC_4808, "Requested / Required Authorization Not Obtained");
        DESCRIPTIONS.put(MC_4831, "Transaction Amount Differs");
        DESCRIPTIONS.put(MC_4834, "Point-of-Interaction Error");
        DESCRIPTIONS.put(MC_4837, "No Cardholder Authorization");
        DESCRIPTIONS.put(MC_4840, "Fraudulent Processing of Transactions");
        DESCRIPTIONS.put(MC_4841, "Cancelled Recurring or Digital Goods Transactions");
        DESCRIPTIONS.put(MC_4842, "Late Presentment");
        DESCRIPTIONS.put(MC_4853, "Cardholder Dispute — Defective / Not as Described");
        DESCRIPTIONS.put(MC_4863, "Cardholder Does Not Recognize — Potential Fraud");
        DESCRIPTIONS.put(MC_4870, "Chip Liability Shift");
        DESCRIPTIONS.put(MC_4871, "Chip / PIN Liability Shift");
        DESCRIPTIONS.put(MC_4999, "Domestic Chargeback Dispute");
    }

    public static String describe(String code) {
        return DESCRIPTIONS.getOrDefault(code, "Unknown reason code: " + code);
    }
}
