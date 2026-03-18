package com.linkwork;

import com.linkwork.config.ImageBuildProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.linkwork.mapper")
@EnableScheduling
@EnableConfigurationProperties(ImageBuildProperties.class)
public class LinkWorkApplication {
    public static void main(String[] args) {
        SpringApplication.run(LinkWorkApplication.class, args);
    }
}
