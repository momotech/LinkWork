package com.linkwork.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleEvent {
    private String taskNo;
    private String type;
    private Object payload;
    private long timestamp;
}
