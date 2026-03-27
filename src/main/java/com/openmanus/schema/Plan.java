package com.openmanus.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务计划数据结构，对应 OpenManus 的 planning_flow.py
 *
 * 流程：PlannerAgent 生成结构化计划 → ExecutorAgent 逐步执行 → 最终汇总结果
 */
public record Plan(
        /** 计划 ID */
        @JsonProperty("plan_id")
        String planId,

        /** 用户原始请求 */
        String request,

        /** 计划标题 */
        String title,

        /** 计划状态：PLANNING / READY / EXECUTING / COMPLETED / FAILED */
        String status,

        /** 步骤列表 */
        List<PlanStep> steps,

        /** 最终汇总结果 */
        String result,

        /** 创建时间 */
        @JsonProperty("created_at")
        LocalDateTime createdAt,

        /** 完成时间 */
        @JsonProperty("completed_at")
        LocalDateTime completedAt
) {

    /**
     * 单个计划步骤
     */
    public record PlanStep(
            /** 步骤序号，从 0 开始 */
            int index,

            /** 步骤描述 */
            String description,

            /** 步骤状态：PENDING / IN_PROGRESS / COMPLETED / FAILED / SKIPPED */
            String status,

            /** 执行结果（完成后填充） */
            String result,

            /** 关联的工具名称（可选） */
            @JsonProperty("tool_name")
            String toolName
    ) {}

    /**
     * 计划状态常量
     */
    public static final String STATUS_PLANNING = "PLANNING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    /**
     * 步骤状态常量
     */
    public static final String STEP_PENDING = "PENDING";
    public static final String STEP_IN_PROGRESS = "IN_PROGRESS";
    public static final String STEP_COMPLETED = "COMPLETED";
    public static final String STEP_FAILED = "FAILED";
    public static final String STEP_SKIPPED = "SKIPPED";

    /**
     * 创建新计划
     */
    public static Plan create(String planId, String request) {
        return new Plan(
                planId,
                request,
                "",
                STATUS_PLANNING,
                new ArrayList<>(),
                "",
                LocalDateTime.now(),
                null
        );
    }

    /**
     * 更新步骤状态
     */
    public Plan withStepStatus(int stepIndex, String status, String result) {
        List<PlanStep> newSteps = new ArrayList<>(this.steps());
        if (stepIndex >= 0 && stepIndex < newSteps.size()) {
            PlanStep old = newSteps.get(stepIndex);
            newSteps.set(stepIndex, new PlanStep(
                    old.index(),
                    old.description(),
                    status,
                    result != null ? result : old.result(),
                    old.toolName()
            ));
        }
        return new Plan(
                this.planId, this.request, this.title, this.status,
                newSteps, this.result, this.createdAt, this.completedAt
        );
    }

    /**
     * 更新计划状态
     */
    public Plan withStatus(String status) {
        return new Plan(
                this.planId, this.request, this.title, status,
                this.steps, this.result, this.createdAt, this.completedAt
        );
    }

    /**
     * 设置步骤列表
     */
    public Plan withSteps(List<PlanStep> steps) {
        return new Plan(
                this.planId, this.request, this.title, this.status,
                steps, this.result, this.createdAt, this.completedAt
        );
    }

    /**
     * 设置最终结果
     */
    public Plan withResult(String result) {
        return new Plan(
                this.planId, this.request, this.title, this.status,
                this.steps, result, this.createdAt, LocalDateTime.now()
        );
    }

    /**
     * 设置计划标题
     */
    public Plan withTitle(String title) {
        return new Plan(
                this.planId, this.request, title, this.status,
                this.steps, this.result, this.createdAt, this.completedAt
        );
    }

    /**
     * 获取已完成步骤数
     */
    public int completedStepCount() {
        return (int) steps.stream()
                .filter(s -> STEP_COMPLETED.equals(s.status()) || STEP_SKIPPED.equals(s.status()))
                .count();
    }
}
