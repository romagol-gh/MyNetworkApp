package com.network.iso8583;

/** ISO 8583 DE39 Response Codes */
public final class ResponseCode {

    private ResponseCode() {}

    public static final String APPROVED                   = "00";
    public static final String HONOR_WITH_ID              = "01";
    public static final String REFER_TO_ISSUER            = "03";
    public static final String PICKUP                     = "04";
    public static final String DO_NOT_HONOR               = "05";
    public static final String INVALID_TRANSACTION        = "12";
    public static final String INVALID_AMOUNT             = "13";
    public static final String INVALID_CARD_NUMBER        = "14";
    public static final String NO_SUCH_ISSUER             = "15";
    public static final String INSUFFICIENT_FUNDS         = "51";
    public static final String NO_CHECKING_ACCOUNT        = "52";
    public static final String EXPIRED_CARD               = "54";
    public static final String INCORRECT_PIN              = "55";
    public static final String SUSPECTED_FRAUD            = "59";
    public static final String CARD_ACCEPTOR_CONTACT_ISSUER = "62";
    public static final String SECURITY_VIOLATION         = "63";
    public static final String EXCEEDS_WITHDRAWAL_LIMIT   = "65";
    public static final String ISSUER_UNAVAILABLE         = "91";
    public static final String ROUTING_ERROR              = "92";
    public static final String SYSTEM_MALFUNCTION         = "96";
}
