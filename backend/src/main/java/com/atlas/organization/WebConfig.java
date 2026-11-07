package com.atlas.organization;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {

    private final TenantContextResolver tenantContextResolver;

    WebConfig(TenantContextResolver tenantContextResolver) {
        this.tenantContextResolver = tenantContextResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(tenantContextResolver);
    }
}
