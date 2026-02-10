package org.opentmf.query.tmf630.config;

import org.opentmf.query.tmf630.advice.Tmf630RangeExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class Tmf630ExceptionHandlingAutoConfiguration {

  @Bean
  public Tmf630RangeExceptionHandler tmf630RangeExceptionHandler() {
    return new Tmf630RangeExceptionHandler();
  }
}
