package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ClusterPodInfo {
    private String name;
    private String namespace;
    private String phase;
    private String nodeName;
    private String podGroupName;
    private String podGroupPhase;
    private String serviceId;
    private String userId;
    private List<ContainerStatusInfo> containers;
    private int restartCount;
    private String startTime;
    private String age;
    private List<String> images;
    private ResourceUsageInfo resourceUsage;
}
