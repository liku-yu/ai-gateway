package com.gateway.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 审计事件（gw_audit_event）：只追加，不可修改；detail 中禁止出现明文凭据。 */
@Data
@TableName("gw_audit_event")
public class AuditEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventType;
    private String actor;
    private String targetType;
    private String targetId;
    private String detail;
    private String traceId;
    private LocalDateTime createTime;

    public static AuditEvent of(String eventType, String actor, String targetType,
                                String targetId, String detailJson, String traceId) {
        AuditEvent e = new AuditEvent();
        e.setEventType(eventType);
        e.setActor(actor);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setDetail(detailJson);
        e.setTraceId(traceId);
        e.setCreateTime(LocalDateTime.now());
        return e;
    }
}
