package com.cityseek.controller;

import com.cityseek.dto.Result;
import com.cityseek.entity.OutboxEvent;
import com.cityseek.security.RequireRole;
import com.cityseek.security.UserRole;
import com.cityseek.service.OutboxEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/admin/outbox")
@RequireRole(UserRole.ADMIN)
public class OutboxAdminController {

    private static final Set<String> STATUSES = Set.of(
            OutboxEvent.PENDING, OutboxEvent.PROCESSING, OutboxEvent.SUCCESS, OutboxEvent.FAILED);
    private final OutboxEventService outboxEventService;

    public OutboxAdminController(OutboxEventService outboxEventService) {
        this.outboxEventService = outboxEventService;
    }

    @GetMapping
    public Result list(@RequestParam(value = "status", required = false) String status) {
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("Outbox 状态不合法");
        }
        var query = outboxEventService.query();
        if (status != null) {
            query.eq("status", status);
        }
        return Result.ok(query.orderByDesc("create_time").last("LIMIT 100").list());
    }

    @PostMapping("/{id}/replay")
    public Result replay(@PathVariable Long id) {
        outboxEventService.replay(id);
        return Result.ok();
    }
}
