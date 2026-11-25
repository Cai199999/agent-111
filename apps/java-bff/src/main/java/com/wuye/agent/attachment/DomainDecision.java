package com.wuye.agent.attachment;

public record DomainDecision(
        String domain,
        String capabilityId,
        double confidence,
        String reason
) {
    public boolean unknown() {
        return "UNKNOWN".equals(domain);
    }
}
