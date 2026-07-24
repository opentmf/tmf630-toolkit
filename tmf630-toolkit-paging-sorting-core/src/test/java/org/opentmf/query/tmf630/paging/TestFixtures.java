package org.opentmf.query.tmf630.paging;

import java.lang.reflect.Method;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;

final class TestFixtures {

  private TestFixtures() {}

  static MethodParameter pageableParameter() throws Exception {
    Method method = ControllerStub.class.getDeclaredMethod("search", Pageable.class);
    return new MethodParameter(method, 0);
  }

  private static class ControllerStub {
    @SuppressWarnings("unused")
    void search(Pageable pageable) { /* signature-only stub for MethodParameter reflection */ }
  }
}
