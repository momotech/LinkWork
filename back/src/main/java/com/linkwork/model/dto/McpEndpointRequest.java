package com.linkwork.model.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpEndpointRequest {

    private String type = "sse";
    private String url;
    private List<String> command;
    private Map<String, String> headers = new LinkedHashMap<>();
    private Map<String, String> env = new LinkedHashMap<>();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<String> getCommand() {
        return command;
    }

    public void setCommand(List<String> command) {
        this.command = command;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
    }
}
