package com.network.transaction;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Value object representing the structured agent context carried in ISO 8583 DE48.
 *
 * Format: AGT:<agentId>|INT:<intentHash>|CHN:<agentChain>|SCP:<mccs>|LMT:<perTxnCap>|WIN:<timeWindow>|NCE:<nonce>
 *
 * Example:
 *   AGT:AGENT001|INT:abc123def456|SCP:5411,5812|LMT:10000|WIN:09:00-17:00|NCE:xyz
 */
public class AgentContext {

    private final String agentId;
    private final String intentHash;
    private final String agentChain;
    private final List<String> mccScope;
    private final Long perTxnLimit;
    private final String timeWindow;
    private final String nonce;

    private AgentContext(String agentId, String intentHash, String agentChain,
                         List<String> mccScope, Long perTxnLimit, String timeWindow, String nonce) {
        this.agentId     = agentId;
        this.intentHash  = intentHash;
        this.agentChain  = agentChain;
        this.mccScope    = mccScope != null ? mccScope : Collections.emptyList();
        this.perTxnLimit = perTxnLimit;
        this.timeWindow  = timeWindow;
        this.nonce       = nonce;
    }

    public static AgentContext parse(String de48) {
        if (de48 == null || de48.isBlank()) return null;
        Map<String, String> parts = new HashMap<>();
        for (String segment : de48.split("\\|")) {
            int colon = segment.indexOf(':');
            if (colon > 0) {
                parts.put(segment.substring(0, colon), segment.substring(colon + 1));
            }
        }
        String agentId    = parts.get("AGT");
        String intentHash = parts.get("INT");
        String agentChain = parts.get("CHN");
        String scpRaw     = parts.get("SCP");
        String limitRaw   = parts.get("LMT");
        String timeWindow = parts.get("WIN");
        String nonce      = parts.get("NCE");

        List<String> mccScope = scpRaw != null
                ? Arrays.asList(scpRaw.split(","))
                : Collections.emptyList();
        Long perTxnLimit = null;
        if (limitRaw != null) {
            try { perTxnLimit = Long.parseLong(limitRaw); } catch (NumberFormatException ignored) {}
        }
        return new AgentContext(agentId, intentHash, agentChain, mccScope, perTxnLimit, timeWindow, nonce);
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        if (agentId    != null) sb.append("AGT:").append(agentId).append("|");
        if (intentHash != null) sb.append("INT:").append(intentHash).append("|");
        if (agentChain != null) sb.append("CHN:").append(agentChain).append("|");
        if (!mccScope.isEmpty()) sb.append("SCP:").append(String.join(",", mccScope)).append("|");
        if (perTxnLimit != null) sb.append("LMT:").append(perTxnLimit).append("|");
        if (timeWindow != null) sb.append("WIN:").append(timeWindow).append("|");
        if (nonce       != null) sb.append("NCE:").append(nonce).append("|");
        return sb.length() > 0 && sb.charAt(sb.length() - 1) == '|'
                ? sb.substring(0, sb.length() - 1)
                : sb.toString();
    }

    public String getAgentId()     { return agentId; }
    public String getIntentHash()  { return intentHash; }
    public String getAgentChain()  { return agentChain; }
    public List<String> getMccScope() { return mccScope; }
    public Long getPerTxnLimit()   { return perTxnLimit; }
    public String getTimeWindow()  { return timeWindow; }
    public String getNonce()       { return nonce; }
}
