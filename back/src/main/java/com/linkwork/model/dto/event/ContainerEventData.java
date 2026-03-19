package com.linkwork.model.dto.event;

import com.linkwork.model.enums.ContainerEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerEventData {
    private ContainerEventType eventType;
    private String message;
    private Long timestamp;
    private Object detail;
}
