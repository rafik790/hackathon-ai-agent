package com.hackathon.agent.config;

import com.hackathon.agent.kubernetes.KubernetesConfigFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the Fabric8 client. The API server is only contacted by collection cycles, so an
 * unreachable cluster never prevents application startup.
 */
@Configuration(proxyBeanMethods = false)
public class KubernetesClientConfig {

    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient(KubernetesProperties properties) {
        return new KubernetesClientBuilder()
                .withConfig(new KubernetesConfigFactory(properties).create())
                .build();
    }
}
