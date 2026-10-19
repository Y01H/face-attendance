package cn.weishi.attendance;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 视频流相关 Web MVC 配置类
 * 用于配置异步请求处理及视频流专用线程池
 */
@Configuration
public class VideoWebConfig implements WebMvcConfigurer {

    /**
     * 创建视频流处理专用的线程池执行器
     * 核心线程数和最大线程数均为4，不使用队列（直接提交），适用于固定并发的视频流场景
     *
     * @return 配置好的 ThreadPoolTaskExecutor 实例
     */
    @Bean
    public ThreadPoolTaskExecutor videoStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 设置核心线程数为4
        executor.setCorePoolSize(4);
        // 设置最大线程数为4（与核心线程数相同，固定线程池）
        executor.setMaxPoolSize(4);
        // 设置队列容量为0，不缓冲任务，超出线程数时直接拒绝
        executor.setQueueCapacity(0);
        // 设置线程名称前缀，便于日志追踪
        executor.setThreadNamePrefix("video-stream-");
        return executor;
    }

    /**
     * 配置 Spring MVC 异步请求支持
     * 使用视频流专用线程池处理异步请求，超时时间设置为60秒
     *
     * @param configurer 异步支持配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 绑定视频流专用线程池作为异步请求执行器
        configurer.setTaskExecutor(videoStreamExecutor());
        // 设置异步请求默认超时时间为60秒
        configurer.setDefaultTimeout(60000);
    }
}
