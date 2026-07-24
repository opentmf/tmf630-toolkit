package org.opentmf.query.tmf630.config;

import org.opentmf.query.tmf630.advice.Tmf630FieldSelectionExceptionHandler;
import org.opentmf.query.tmf630.advice.Tmf630PagingExceptionHandler;
import org.opentmf.query.tmf630.advice.Tmf630RangeExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class Tmf630ExceptionHandlingAutoConfiguration {

  @Bean
  public Tmf630RangeExceptionHandler tmf630RangeExceptionHandler() {
    return new Tmf630RangeExceptionHandler();
  }

  @Bean
  public Tmf630PagingExceptionHandler tmf630PagingExceptionHandler() {
    return new Tmf630PagingExceptionHandler();
  }

  @Bean
  public Tmf630FieldSelectionExceptionHandler tmf630FieldSelectionExceptionHandler() {
    return new Tmf630FieldSelectionExceptionHandler();
  }
}
