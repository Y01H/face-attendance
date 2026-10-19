package cn.weishi.attendance;

import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

/**
 * 全局异常处理器
 * 统一捕获 Controller 层抛出的异常，转换为标准的 JSON 错误响应返回给前端
 */
@RestControllerAdvice
public class ApiErrors {

    /**
     * 处理非法参数异常（如入参校验不通过）
     * 返回 HTTP 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Collections.singletonMap("message", ex.getMessage()));
    }

    /**
     * 处理非法状态异常（如人脸服务未就绪、设备不可用等）
     * 返回 HTTP 503 Service Unavailable
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.singletonMap("message", ex.getMessage()));
    }

    /**
     * 处理数据库访问异常（如连接失败、SQL 语法错误、权限不足等）
     * 返回 HTTP 503 Service Unavailable，不暴露底层异常细节
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> database(DataAccessException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.singletonMap("message", "数据库操作失败，请检查云端连接、SQL 建表和账号权限"));
    }

    /**
     * 兜底异常处理（捕获所有未被上述处理器匹配的异常）
     * 返回 HTTP 500 Internal Server Error，避免泄露堆栈信息
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.singletonMap("message", "操作失败，请检查输入或服务配置"));
    }
}
