package com.agentplatform.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 平台唯一可部署进程：装配 Harness（构建层）与 Runtime（执行层），对外暴露 API。
 */
@SpringBootApplication
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}
