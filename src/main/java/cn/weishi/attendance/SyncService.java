package cn.weishi.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.weishi.attendance.Records.*;

@Component
public class SyncService {
    /** 本地事件存储，持久化考勤记录并维护待同步/待上报队列 */
    private final LocalStore local;
    /** 云端存储客户端，负责将考勤事件上传至远程云端服务 */
    private final CloudStore cloud;
    /** 应用配置访问器，读取同步开关、第三方接口地址、字段映射等参数 */
    private final AppConfig config;
    /** Jackson 对象映射器，用于构建和解析第三方接口的 JSON 请求/响应 */
    private final ObjectMapper mapper;
    /** 带超时配置的 HTTP 客户端，专用于向第三方接口发送 POST 请求 */
    private final RestTemplate http;
    /** 云端同步的当前状态描述，供前端轮询展示；volatile 保证定时线程与 Web 线程间的可见性 */
    private volatile String cloudState = "等待同步";
    /** 第三方接口上报的当前状态描述，供前端轮询展示；volatile 保证定时线程与 Web 线程间的可见性 */
    private volatile String remoteState = "第三方接口未启用";

    /**
     * 构造函数，由 Spring 容器自动注入所需依赖并初始化 HTTP 客户端。
     *
     * @param local  本地事件存储实例
     * @param cloud  云端存储客户端实例
     * @param config 应用配置访问器实例
     * @param mapper Jackson ObjectMapper 实例
     */
    public SyncService(LocalStore local, CloudStore cloud, AppConfig config, ObjectMapper mapper) {
        this.local = local;
        this.cloud = cloud;
        this.config = config;
        this.mapper = mapper;
        // 配置 HTTP 连接超时 4 秒、读取超时 6 秒，防止第三方接口响应缓慢导致同步线程长时间阻塞
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(4000);
        factory.setReadTimeout(6000);
        http = new RestTemplate(factory);
    }

    /**
     * 云端同步定时任务：每 5 秒轮询一次本地待同步队列，将未上传的考勤事件推送至云端存储。
     * 首次执行延迟 2 秒，确保应用启动完成后再开始同步。
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public void cloudTick() {
        // 仅识别模式下不产生云端同步需求，直接跳过本轮任务
        if (config.recognitionOnly()) {
            cloudState = "仅识别模式，不同步打卡记录";
            return;
        }
        // 云端地址或凭据尚未配置，无法执行上传
        if (!cloud.configured()) {
            cloudState = "云端连接尚未配置";
            return;
        }
        try {
            // 从本地存储读取所有尚未同步至云端的事件，逐条补传
            for (Event e : local.cloudPending()) {
                try {
                    cloud.upload(e);
                    // 上传成功，标记该事件已同步，避免下次重复发送
                    local.cloudSent(e.eventId);
                    cloudState = "云端事件同步正常";
                } catch (Exception ex) {
                    // 单条上传失败不影响其余事件，记录状态后继续处理下一条
                    cloudState = "云端事件补传失败，记录已保留";
                }
            }
        } catch (Exception ex) {
            // 读取本地待同步队列本身异常（如文件损坏、权限问题），本轮终止
            cloudState = "本地待同步事件读取失败";
        }
    }

    /**
     * 第三方接口同步定时任务：每 5 秒轮询一次本地上报队列，将未上报的考勤事件推送至第三方系统。
     * 首次执行延迟 2.5 秒，与云端同步任务错开，避免启动瞬间并发争用资源。
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 2500)
    public void remoteTick() {
        // 仅识别模式下不产生上报需求，直接跳过本轮任务
        if (config.recognitionOnly()) {
            remoteState = "仅识别模式，不上报打卡记录";
            return;
        }
        // 第三方接口未启用，无需执行上报
        if (!config.flag("third-party.enabled", false)) return;
        try {
            // 从本地存储读取所有尚未上报的事件，逐条推送至第三方
            for (Event e : local.remotePending()) {
                try {
                    send(e);
                    // 上报成功，标记该事件已同步，避免下次重复发送
                    local.remoteSent(e.eventId);
                    remoteState = "第三方已确认接收";
                } catch (HttpStatusCodeException ex) {
                    // 区分永久性错误与临时性错误：4xx（排除 408 超时、429 限流）视为永久失败，不再重试
                    int code = ex.getRawStatusCode();
                    boolean permanent = code >= 400 && code < 500 && code != 408 && code != 429;
                    local.remoteFailed(e, "HTTP " + code, permanent);
                    remoteState = "上报失败，记录已保留";
                } catch (Exception ex) {
                    // 非 HTTP 异常（网络超时、DNS 解析失败等），记录为可重试的临时失败
                    local.remoteFailed(e, "接口未确认成功或网络异常，请核查接口配置", false);
                    remoteState = "上报未确认，稍后重试";
                }
            }
        } catch (Exception ex) {
            // 读取本地上报队列本身异常（如文件损坏、权限问题），本轮终止
            remoteState = "本地上报队列处理失败，下一轮重新读取";
        }
    }

    /**
     * 将单条考勤事件以 JSON 形式 POST 至第三方接口，并校验业务层是否确认接收成功。
     * 字段名由配置项动态指定，以适配不同第三方系统的数据契约。
     *
     * @param e 待上报的考勤事件
     * @throws Exception 网络异常、HTTP 错误、业务校验未通过或配置缺失时抛出
     */
    void send(Event e) throws Exception {
        // 获取第三方接口地址；生产环境强制 HTTPS，仅允许本地回环地址使用 HTTP 以便调试
        String url = config.required("third-party.url");
        if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1:") && !url.startsWith("http://localhost:"))
            throw new IllegalArgumentException("远程第三方接口必须使用 HTTPS");

        // 构建请求体，字段名称可通过配置自定义以匹配第三方接口规范
        ObjectNode body = mapper.createObjectNode();
        body.put(config.text("third-party.event-id-field", "eventId"), e.eventId);
        body.put(config.text("third-party.employee-id-field", "employeeId"), e.employeeId);
        body.put(config.text("third-party.device-id-field", "deviceId"), e.deviceId);
        body.put(config.text("third-party.time-field", "occurredAt"), e.occurredAt);
        body.put(config.text("third-party.type-field", "punchType"), e.punchType);

        // 设置请求头：Content-Type、幂等键（防止重复上报）、可选的 Bearer 鉴权令牌
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", e.eventId);
        String token = config.text("third-party.token", "");
        if (!token.isEmpty()) headers.setBearerAuth(token);

        // 发送 POST 请求并获取响应
        ResponseEntity<String> response = http.postForEntity(url, new HttpEntity<>(body.toString(), headers), String.class);

        // 解析响应体，校验业务层成功标识；支持 JSON Pointer（以 "/" 开头）和普通字段名两种定位方式
        String field = config.text("third-party.success-field", "success");
        if (field.isEmpty()) throw new IllegalArgumentException("必须配置业务成功字段");
        JsonNode root = mapper.readTree(response.getBody());
        JsonNode success = field.startsWith("/") ? root.at(field) : root.path(field);

        // HTTP 状态码非 2xx、成功字段缺失或值不匹配，均视为上报失败
        if (!response.getStatusCode().is2xxSuccessful() || success.isMissingNode() || !config.text("third-party.success-value", "true").equals(success.asText()))
            throw new IllegalStateException("第三方业务未确认成功");
    }

    /**
     * 汇总当前同步服务的运行状态，供 Web 层或健康检查接口调用。
     *
     * @return 包含云端同步状态、第三方开关与状态、本地队列计数的有序映射
     */
    public Map<String, Object> state() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cloud", cloudState);
        result.put("thirdPartyEnabled", config.flag("third-party.enabled", false));
        result.put("thirdParty", remoteState);
        result.put("counts", local.counts());
        return result;
    }
}
