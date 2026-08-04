package com.cityseek.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("tb_outbox_event")
public class OutboxEvent {

    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String aggregateType;
    private Long aggregateId;
    private String eventType;
    private String payload;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String errorMessage;
    private String claimedBy;
    private String claimToken;
    private LocalDateTime processingDeadline;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
