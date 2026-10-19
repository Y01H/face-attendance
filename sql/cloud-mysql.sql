-- 在云端 MySQL 8.0 执行。已有数据库可以只执行 CREATE TABLE 部分。
CREATE DATABASE IF NOT EXISTS face_attendance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE face_attendance;
CREATE TABLE IF NOT EXISTS fa_employee (
 employee_id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
 employee_name VARCHAR(100) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fa_face_template (
 template_id VARCHAR(36) PRIMARY KEY,
 employee_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 model_version VARCHAR(64) NOT NULL,
 feature BLOB NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX idx_face_employee(employee_id),
 CONSTRAINT fk_face_employee FOREIGN KEY(employee_id) REFERENCES fa_employee(employee_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fa_attendance_event (
 event_id VARCHAR(36) PRIMARY KEY,
 employee_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 employee_name VARCHAR(100) NOT NULL,
 device_id VARCHAR(64) NOT NULL,
 occurred_at VARCHAR(40) NOT NULL COMMENT 'ISO-8601 UTC 原始打卡时间，如 2026-10-12T02:00:00Z',
 punch_type VARCHAR(20) NOT NULL,
 similarity DOUBLE NOT NULL,
 received_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX idx_event_employee(employee_id,occurred_at)
) ENGINE=InnoDB;
-- 用单独的低权限账号，云服务控制台配置访问白名单和 TLS。
-- 把终端地址与密码改成实际值后执行，勿直接使用此示例密码。
-- CREATE USER 'face_terminal'@'终端出口IP' IDENTIFIED BY '替换为强密码' REQUIRE SSL;
-- GRANT SELECT,INSERT,UPDATE,DELETE ON face_attendance.fa_employee TO 'face_terminal'@'终端出口IP';
-- GRANT SELECT,INSERT,DELETE ON face_attendance.fa_face_template TO 'face_terminal'@'终端出口IP';
-- GRANT INSERT,UPDATE ON face_attendance.fa_attendance_event TO 'face_terminal'@'终端出口IP';
