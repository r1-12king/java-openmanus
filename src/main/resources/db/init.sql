-- 创建数据库（首次使用前执行一次）
CREATE DATABASE IF NOT EXISTS openmanus
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE openmanus;

-- agent_runs 表（Spring JPA ddl-auto: update 会自动创建，此脚本仅作参考）
CREATE TABLE IF NOT EXISTS agent_runs (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id   VARCHAR(36)  NOT NULL,
    request      TEXT         NOT NULL,
    result       LONGTEXT,
    steps_taken  INT          NOT NULL DEFAULT 0,
    status       VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    created_at   DATETIME(6)  NOT NULL,
    completed_at DATETIME(6),
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
