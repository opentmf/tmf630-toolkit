package org.opentmf.query.tmf630.exception;

public class RequestedRangeNotSatisfiableException extends RuntimeException {

  private final long requestedOffset;
  private final long totalElements;

  public RequestedRangeNotSatisfiableException(long requestedOffset, long totalElements) {
    super(
        "Requested offset "
            + requestedOffset
            + " is outside valid range 0.."
            + (totalElements == 0 ? 0 : totalElements - 1));
    this.requestedOffset = requestedOffset;
    this.totalElements = totalElements;
  }

  public long getRequestedOffset() {
    return requestedOffset;
  }

  public long getTotalElements() {
    return totalElements;
  }
}
