package org.opentmf.query.tmf630.advice;

import java.util.List;
import org.opentmf.query.commons.fieldselection.FieldSelectionUtil;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.opentmf.query.tmf630.util.Tmf630Util;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * A {@link ResponseBodyAdvice} that adds transparent TMF630 response handling for controller
 * methods annotated with {@link Tmf630Response @Tmf630Response}.
 *
 * <ul>
 *   <li>If the body is a {@link Page} and the return type is <em>not</em> wrapped in
 *       {@link ResponseEntity}: resolves status (200/206/416), adds TMF630 range headers,
 *       extracts page content, and applies {@code fields} selection.</li>
 *   <li>If the body is a {@link Page} wrapped in {@link ResponseEntity}: extracts content
 *       and applies {@code fields} only (status/headers are already set by the developer).</li>
 *   <li>For any other body type: applies {@code fields} selection if the query parameter
 *       is present.</li>
 * </ul>
 *
 * <p>The depth used for field selection is resolved in this order:
 * <ol>
 *   <li>Method-level {@code @Tmf630Response(depth = N)} if {@code N >= 0}.</li>
 *   <li>Class-level {@code @Tmf630Response(depth = N)} if {@code N >= 0}.</li>
 *   <li>The global {@code defaultDepth} configured via
 *       {@code opentmf.tmf630.field-selection.default-depth}.</li>
 * </ol>
 */
@ControllerAdvice
public class Tmf630ResponseBodyAdvice implements ResponseBodyAdvice<Object> {

  static final int UNSET_DEPTH = -1;
  private static final int FALLBACK_DEPTH = 1;

  private final int defaultDepth;

  public Tmf630ResponseBodyAdvice() {
    this(FALLBACK_DEPTH);
  }

  public Tmf630ResponseBodyAdvice(int defaultDepth) {
    this.defaultDepth = defaultDepth;
  }

  @Override
  public boolean supports(
      @NonNull MethodParameter returnType,
      @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
    return AnnotatedElementUtils.hasAnnotation(
            returnType.getContainingClass(), Tmf630Response.class)
        || returnType.hasMethodAnnotation(Tmf630Response.class);
  }

  @Override
  @Nullable
  public Object beforeBodyWrite(
      @Nullable Object body,
      @NonNull MethodParameter returnType,
      @NonNull MediaType selectedContentType,
      @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
      @NonNull ServerHttpRequest request,
      @NonNull ServerHttpResponse response) {

    String fields = extractParam(request, "fields");
    int depth = resolveDepth(returnType);
    boolean wrappedInResponseEntity =
        ResponseEntity.class.isAssignableFrom(returnType.getParameterType());

    if (body instanceof Page<?> page) {
      return handlePage(page, fields, depth, response, wrappedInResponseEntity);
    }

    return applyFieldSelection(body, fields, depth);
  }

  /**
   * Resolves the effective depth for the given handler method.
   * Method-level annotation wins over class-level; class-level wins over global default.
   */
  int resolveDepth(MethodParameter returnType) {
    Tmf630Response method = returnType.getMethodAnnotation(Tmf630Response.class);
    if (method != null && method.depth() != UNSET_DEPTH) {
      return method.depth();
    }
    Tmf630Response clazz =
        AnnotatedElementUtils.findMergedAnnotation(
            returnType.getContainingClass(), Tmf630Response.class);
    if (clazz != null && clazz.depth() != UNSET_DEPTH) {
      return clazz.depth();
    }
    return defaultDepth;
  }

  private Object handlePage(
      Page<?> page, String fields, int depth, ServerHttpResponse response, boolean statusAlreadySet) {
    long total = page.getTotalElements();
    long offset = page.getPageable().getOffset();
    long returned = page.getNumberOfElements();

    if (!statusAlreadySet) {
      if (total > 0 && offset >= total) {
        response.setStatusCode(HttpStatusCode.valueOf(416));
        Tmf630Util.applyRangeHeaders(response.getHeaders(), total, offset, 0, false);
        return new ErrorMessage(
            "416",
            "Requested Range Not Satisfiable",
            "Requested offset is outside the available range.",
            "Requested offset "
                + offset
                + " does not overlap with existing items. Valid offsets are between 0 and "
                + (total - 1)
                + ".");
      }

      boolean fullPage = total == 0 || (offset == 0 && returned == total);
      response.setStatusCode(HttpStatusCode.valueOf(fullPage ? 200 : 206));
      Tmf630Util.applyRangeHeaders(response.getHeaders(), total, offset, returned, true);
    }

    List<?> content = page.getContent();
    return applyFieldSelection(content, fields, depth);
  }

  @Nullable
  private Object applyFieldSelection(@Nullable Object body, @Nullable String fields, int depth) {
    if (fields == null || fields.isEmpty() || body == null) {
      return body;
    }
    if (body instanceof List<?> list) {
      if (list.isEmpty()) {
        return list;
      }
      return FieldSelectionUtil.fieldsToMapList(list, fields, depth);
    }
    return FieldSelectionUtil.fieldsToMap(body, fields, depth);
  }

  private static String extractParam(ServerHttpRequest request, String name) {
    if (request instanceof ServletServerHttpRequest servletRequest) {
      return servletRequest.getServletRequest().getParameter(name);
    }
    List<String> values =
        org.springframework.web.util.UriComponentsBuilder.fromUri(request.getURI())
            .build()
            .getQueryParams()
            .get(name);
    return (values != null && !values.isEmpty()) ? values.get(0) : null;
  }
}
