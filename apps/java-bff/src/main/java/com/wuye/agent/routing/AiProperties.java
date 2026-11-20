package com.wuye.agent.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 路由配置。默认关闭，规则路由完全可用。
 */
@Component
@ConfigurationProperties(prefix = "wuye.ai")
public class AiProperties {

    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:8700";
    private String internalToken = "";
    private int timeoutMs = 9000;
    private double confidenceThreshold = 0.55;
    private double ruleConfidenceThreshold = 0.85;
    private double inputPricePer1k = 0.0;
    private double outputPricePer1k = 0.0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public double getRuleConfidenceThreshold() {
        return ruleConfidenceThreshold;
    }

    public void setRuleConfidenceThreshold(double ruleConfidenceThreshold) {
        this.ruleConfidenceThreshold = ruleConfidenceThreshold;
    }

    public double getInputPricePer1k() {
        return inputPricePer1k;
    }

    public void setInputPricePer1k(double inputPricePer1k) {
        this.inputPricePer1k = inputPricePer1k;
    }

    public double getOutputPricePer1k() {
        return outputPricePer1k;
    }

    public void setOutputPricePer1k(double outputPricePer1k) {
        this.outputPricePer1k = outputPricePer1k;
    }
}
