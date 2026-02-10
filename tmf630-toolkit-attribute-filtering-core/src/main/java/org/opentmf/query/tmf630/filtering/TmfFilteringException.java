package org.opentmf.query.tmf630.filtering;

public class TmfFilteringException extends RuntimeException {

  public TmfFilteringException(String message) {
    super(message);
  }

  public TmfFilteringException(String message, Throwable cause) {
    super(message, cause);
  }
}
