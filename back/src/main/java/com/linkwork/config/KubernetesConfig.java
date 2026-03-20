package com.linkwork.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Kubernetes 客户端配置
 */
@Configuration
@Slf4j
public class KubernetesConfig {
    
    private final EnvConfig envConfig;
    
    public KubernetesConfig(EnvConfig envConfig) {
        this.envConfig = envConfig;
    }
    
    @Bean
    public KubernetesClient kubernetesClient() {
        String kubeconfigPath = envConfig.getCluster().getKubeconfigPath();
        
        if (kubeconfigPath != null && !kubeconfigPath.isBlank()) {
            log.info("Loading kubeconfig from: {}", kubeconfigPath);
            try {
                String kubeconfigContent = Files.readString(Path.of(kubeconfigPath));
                Config config = Config.fromKubeconfig(kubeconfigContent);
                return new KubernetesClientBuilder().withConfig(config).build();
            } catch (IOException e) {
                log.error("Failed to load kubeconfig from {}: {}", kubeconfigPath, e.getMessage());
                throw new RuntimeException("Failed to load kubeconfig", e);
            }
        }
        
        // 使用默认配置（从环境变量或默认路径）
        log.info("Using default Kubernetes configuration");
        return new KubernetesClientBuilder().build();
    }
}
