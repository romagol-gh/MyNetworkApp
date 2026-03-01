package com.network.iso8583;

import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;

/**
 * Thin wrapper around jPOS ISOMsg that provides typed field access
 * and shields the rest of the application from jPOS specifics.
 */
public class IsoMessage {

    private final ISOMsg inner;

    public IsoMessage(ISOMsg inner) {
        this.inner = inner;
    }

    public ISOMsg inner() { return inner; }

    public String getMti() {
        try {
            return inner.getMTI();
        } catch (ISOException e) {
            throw new IllegalStateException("Cannot read MTI", e);
        }
    }

    public String get(int fieldNo) {
        return inner.getString(fieldNo);
    }

    public boolean has(int fieldNo) {
        return inner.hasField(fieldNo);
    }

    public void set(int fieldNo, String value) {
        try {
            inner.set(fieldNo, value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot set field " + fieldNo, e);
        }
    }

    public void setMti(String mti) {
        try {
            inner.setMTI(mti);
        } catch (ISOException e) {
            throw new IllegalStateException("Cannot set MTI", e);
        }
    }

    public String getStan()          { return get(Field.STAN); }
    public String getPan()           { return get(Field.PAN); }
    public String getAmount()        { return get(Field.AMOUNT); }
    public String getCurrency()      { return get(Field.CURRENCY); }
    public String getProcessingCode(){ return get(Field.PROCESSING_CODE); }
    public String getRetrievalRef()  { return get(Field.RETRIEVAL_REF); }
    public String getResponseCode()  { return get(Field.RESPONSE_CODE); }
    public String getAcquirerCode()  { return get(Field.ACQUIRING_INSTITUTION); }
    public String getMcc()           { return get(Field.MCC); }
}
