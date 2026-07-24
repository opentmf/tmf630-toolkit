package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
  void equalsAndHashCodeWorkCorrectly() {
    Sort sort = Sort.by("id");
    OffsetLimitPageRequest a = new OffsetLimitPageRequest(10, 5, sort);
    OffsetLimitPageRequest b = new OffsetLimitPageRequest(10, 5, sort);
    OffsetLimitPageRequest differentOffset = new OffsetLimitPageRequest(20, 5, sort);
    OffsetLimitPageRequest differentLimit = new OffsetLimitPageRequest(10, 3, sort);
    OffsetLimitPageRequest differentSort =
        new OffsetLimitPageRequest(10, 5, Sort.by(Sort.Direction.DESC, "id"));

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, differentOffset);
    assertNotEquals(a, differentLimit);
    assertNotEquals(a, differentSort);
    assertNotEquals(null, a);
    assertNotEquals("string", a);
  }

  @Test
  void toStringContainsAllFields() {
    OffsetLimitPageRequest pageable = new OffsetLimitPageRequest(5, 10, Sort.by("name"));
    String str = pageable.toString();
    assertTrue(str.contains("offset=5"));
    assertTrue(str.contains("limit=10"));
    assertTrue(str.contains("name"));
  }

  @Test
  void rejectsInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> new OffsetLimitPageRequest(-1, 10, null));
    assertThrows(IllegalArgumentException.class, () -> new OffsetLimitPageRequest(0, 0, null));
    OffsetLimitPageRequest zeroOffset = new OffsetLimitPageRequest(0, 10, Sort.unsorted());
    assertThrows(IllegalArgumentException.class, () -> zeroOffset.withPage(-1));
  }
}
