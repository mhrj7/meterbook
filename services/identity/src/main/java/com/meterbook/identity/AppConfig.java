package com.meterbook.identity;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Application-wide beans. One clock for everything time-based, so tests can control time. */
@Configuration
@EnableScheduling
public class AppConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
