package com.wuye.agent.agent.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 真实 HTTP 子 Agent 配置。
 *
 * <p>mode=mock 时使用 MockAdapter；mode=http 时使用真实 HTTPAdapter。
 * 凭据只从环境变量/配置注入，不写库、不下发前端。</p>
 */
@Component
@ConfigurationProperties(prefix = "wuye.agent")
public class AgentHttpProperties {

    private String mode = "mock";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 20000;
    private Endpoint property = new Endpoint();
    private Endpoint contract = new Endpoint();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public Endpoint getProperty() {
        return property;
    }

    public void setProperty(Endpoint property) {
        this.property = property;
    }

    public Endpoint getContract() {
        return contract;
    }

    public void setContract(Endpoint contract) {
        this.contract = contract;
    }

    public static class Endpoint {
        private String baseUrl = "http://127.0.0.1:8400";
        private String internalToken = "";
        private String audience = "";
        private String actorId = "10001";
        private String roles = "ADMIN";
        private String tenantId = "0";
        private String delegationPath = "/agent/v1/delegation/token";
        private String delegationScopes = "*";
        private boolean useDelegation = true;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getActorId() {
            return actorId;
        }

        public void setActorId(String actorId) {
            this.actorId = actorId;
        }

        public String getRoles() {
            return roles;
        }

        public void setRoles(String roles) {
            this.roles = roles;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getDelegationPath() {
            return delegationPath;
        }

        public void setDelegationPath(String delegationPath) {
            this.delegationPath = delegationPath;
        }

        public String getDelegationScopes() {
            return delegationScopes;
        }

        public void setDelegationScopes(String delegationScopes) {
            this.delegationScopes = delegationScopes;
        }

        public boolean isUseDelegation() {
            return useDelegation;
        }

        public void setUseDelegation(boolean useDelegation) {
            this.useDelegation = useDelegation;
        }
    }
}
