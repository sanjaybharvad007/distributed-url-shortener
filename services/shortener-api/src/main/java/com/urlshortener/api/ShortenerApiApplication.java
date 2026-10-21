package com.urlshortener.api;

import com.urlshortener.api.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class ShortenerApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(ShortenerApiApplication.class, args);
  }
}
