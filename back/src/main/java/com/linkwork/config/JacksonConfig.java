package com.linkwork.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jackson.JacksonProperties;
import org.springframework.boot.jackson.JsonComponentModule;
import org.springframework.boot.jackson.JsonMixinModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
        return builder -> builder
                .modulesToInstall(JavaTimeModule.class)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * MCP starter 可能在自动配置阶段提前注册一个裸 ObjectMapper，导致 Web 层缺少 JavaTime 支持。
     * 这里显式提供主 ObjectMapper，确保 MVC 与业务注入都使用支持 LocalDateTime 的配置。
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder, JacksonProperties properties) {
        builder.modules(new JsonComponentModule(), new JsonMixinModule(), new JavaTimeModule());
        ObjectMapper objectMapper = builder.build();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        if (properties.getTimeZone() != null) {
            objectMapper.setTimeZone(properties.getTimeZone());
        }
        return objectMapper;
    }
}
