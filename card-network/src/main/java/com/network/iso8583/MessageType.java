package com.network.iso8583;

public enum MessageType {

    // Authorization
    AUTH_REQUEST("0100"),
    AUTH_RESPONSE("0110"),

    // Financial
    FINANCIAL_REQUEST("0200"),
    FINANCIAL_RESPONSE("0210"),

    // Reversal
    REVERSAL_REQUEST("0400"),
    REVERSAL_ADVICE("0420"),

    // Network management (sign-on, sign-off, echo)
    NETWORK_MGMT_REQUEST("0800"),
    NETWORK_MGMT_RESPONSE("0810");

    private final String mti;

    MessageType(String mti) { this.mti = mti; }

    public String getMti() { return mti; }

    public static MessageType fromMti(String mti) {
        for (MessageType t : values()) {
            if (t.mti.equals(mti)) return t;
        }
        throw new IllegalArgumentException("Unknown MTI: " + mti);
    }

    /** Returns the paired response MTI for a given request MTI */
    public static String responseMti(String requestMti) {
        return switch (requestMti) {
            case "0100" -> "0110";
            case "0200" -> "0210";
            case "0400" -> "0420";
            case "0800" -> "0810";
            default -> throw new IllegalArgumentException("No response MTI for: " + requestMti);
        };
    }
}
