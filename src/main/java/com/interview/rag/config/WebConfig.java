package com.interview.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.servlet.server.CookieSameSiteSupplier;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开发期跨域放开(页面与服务同源部署,生产环境应收紧)
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean
    CookieSameSiteSupplier browserSessionSameSite() {
        return CookieSameSiteSupplier.ofStrict().whenHasName("JSESSIONID");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*");
    }
}
