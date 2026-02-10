package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class OffsetLimitPageRequestTest {

  @Test
  void basicPropertiesAreMappedCorrectly() {
    OffsetLimitPageRequest pageable =
        new OffsetLimitPageRequest(20, 10, Sort.by(Sort.Order.desc("createdOn")));

    assertEquals(2, pageable.getPageNumber());
    assertEquals(10, pageable.getPageSize());
    assertEquals(20, pageable.getOffset());
    assertEquals(Sort.Direction.DESC, pageable.getSort().toList().get(0).getDirection());
  }

  @Test
  void navigationWorksWithOffsetModel() {
    OffsetLimitPageRequest pageable = new OffsetLimitPageRequest(20, 10, Sort.unsorted());

    assertTrue(pageable.hasPrevious());
    assertEquals(30, pageable.next().getOffset());
    assertEquals(10, pageable.previousOrFirst().getOffset());
    assertEquals(0, pageable.first().getOffset());
    assertEquals(40, pageable.withPage(4).getOffset());
  }

  @Test
  void firstWindowHasNoPrevious() {
    OffsetLimitPageRequest pageable = new OffsetLimitPageRequest(0, 10, Sort.unsorted());
    assertFalse(pageable.hasPrevious());
    assertEquals(0, pageable.previousOrFirst().getOffset());
  }

  @Test
  void rejectsInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> new OffsetLimitPageRequest(-1, 10, null));
    assertThrows(IllegalArgumentException.class, () -> new OffsetLimitPageRequest(0, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OffsetLimitPageRequest(0, 10, Sort.unsorted()).withPage(-1));
  }
}
