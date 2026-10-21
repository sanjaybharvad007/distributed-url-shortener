package com.urlshortener.api.config;

import com.urlshortener.api.ratelimit.RateLimiter;
import com.urlshortener.api.web.filter.ApiKeyAuthFilter;
import com.urlshortener.api.web.filter.RateLimitFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Servlet filters for the management API and the OpenAPI document. */
@Configuration
public class WebConfig {

  public static final String API_PATTERN = "/api/*";

  @Bean
  public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(AppProperties props) {
    FilterRegistrationBean<ApiKeyAuthFilter> reg =
        new FilterRegistrationBean<>(new ApiKeyAuthFilter(props.apiKeys()));
    reg.addUrlPatterns(API_PATTERN);
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return reg;
  }

  @Bean
  public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
      RateLimiter limiter, AppProperties props) {
    FilterRegistrationBean<RateLimitFilter> reg =
        new FilterRegistrationBean<>(new RateLimitFilter(limiter, props.rateLimit().enabled()));
    reg.addUrlPatterns(API_PATTERN);
    reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    return reg;
  }

  @Bean
  public OpenAPI openApi() {
    String scheme = "ApiKey";
    return new OpenAPI()
        .info(
            new Info()
                .title("URL Shortener API")
                .version("v1")
                .description(
                    "Control-plane API for the distributed URL shortener. Redirects are served by "
                        + "the Go redirector on the base URL; this service also serves them as a "
                        + "fallback."))
        .components(
            new Components()
                .addSecuritySchemes(
                    scheme,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyAuthFilter.HEADER)))
        .addSecurityItem(new SecurityRequirement().addList(scheme));
  }
}
