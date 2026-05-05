package org.opentmf.query.tmf630.mongo;

import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.opentmf.query.tmf630.paging.TmfRichPageable;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HttpItController {

  private final ProductRepository products;
  private final OrderRepository orders;
  private final RawOrderRepository rawOrders;
  private final Tmf630MongoCorrelatedSortExecutor correlatedExecutor;

  HttpItController(
      ProductRepository products,
      OrderRepository orders,
      RawOrderRepository rawOrders,
      Tmf630MongoCorrelatedSortExecutor correlatedExecutor) {
    this.products = products;
    this.orders = orders;
    this.rawOrders = rawOrders;
    this.correlatedExecutor = correlatedExecutor;
  }

  @GetMapping("/products")
  @Tmf630Response
  Page<Product> listProducts(TmfSort sort, Pageable pageable) {
    if (sort.requiresAggregation()) {
      return correlatedExecutor.findAll(Product.class, null, sort, pageable);
    }
    return products.findAll(withSort(pageable, sort));
  }

  @GetMapping("/orders")
  @Tmf630Response
  Page<Order> listOrders(TmfSort sort, Pageable pageable) {
    if (sort.requiresAggregation()) {
      return correlatedExecutor.findAll(Order.class, null, sort, pageable);
    }
    return orders.findAll(withSort(pageable, sort));
  }

  @GetMapping("/raw-orders")
  @Tmf630Response
  Page<RawOrder> listRawOrders(TmfSort sort, Pageable pageable) {
    if (sort.requiresAggregation()) {
      return correlatedExecutor.findAll(RawOrder.class, null, sort, pageable);
    }
    return rawOrders.findAll(withSort(pageable, sort));
  }

  /**
   * A second products endpoint that binds Spring Data {@link Sort} as the parameter
   * type — proves that legacy controllers continue to 400 on correlated sort terms
   * (the existing `Sort` resolver still uses {@code TmfSortParser.parse(...)}).
   */
  @GetMapping("/products-plain")
  @Tmf630Response
  Page<Product> listProductsPlain(Sort sort, Pageable pageable) {
    return products.findAll(PageRequest.of(
        pageable.getPageNumber(),
        pageable.getPageSize(),
        sort.isUnsorted() ? pageable.getSort() : sort));
  }

  /**
   * Recommended two-parameter shape using {@link TmfRichPageable} (extends Pageable, carries
   * the rich sort). Same end-state semantics as {@link #listProducts}: plain sort goes
   * through the find() path, correlated sort goes through the aggregation executor.
   */
  @GetMapping("/products-rich")
  @Tmf630Response
  Page<Product> listProductsRich(TmfRichPageable pageable) {
    if (pageable.tmfSort().requiresAggregation()) {
      return correlatedExecutor.findAll(Product.class, null, pageable.tmfSort(), pageable);
    }
    return products.findAll(pageable);
  }

  private static Pageable withSort(Pageable pageable, TmfSort tmfSort) {
    Sort plain = tmfSort.toPlainSort();
    return plain.isUnsorted() ? pageable : pageable.getSortOr(plain).equals(plain)
        ? pageable
        : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), plain);
  }
}
