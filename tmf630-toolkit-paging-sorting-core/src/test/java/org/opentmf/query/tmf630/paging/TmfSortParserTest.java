package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class TmfSortParserTest {

  @Test
  void parseSignedAndUnsignedTokens() {
    TmfSortParser parser = new TmfSortParser(List.of(), false);

    Sort sort = parser.parse(List.of("-createdOn,+id,name"));

    List<Sort.Order> orders = sort.toList();
    assertEquals(3, orders.size());
    assertEquals(Sort.Direction.DESC, orders.get(0).getDirection());
    assertEquals("createdOn", orders.get(0).getProperty());
    assertEquals(Sort.Direction.ASC, orders.get(1).getDirection());
    assertEquals("id", orders.get(1).getProperty());
    assertEquals(Sort.Direction.ASC, orders.get(2).getDirection());
    assertEquals("name", orders.get(2).getProperty());
  }

  @Test
  void parseReturnsUnsortedOnEmptyInput() {
    TmfSortParser parser = new TmfSortParser(List.of(), false);
    assertEquals(Sort.unsorted(), parser.parse(List.of("", "   ")));
  }

  @Test
  void parseRejectsNonAllowlistedField() {
    TmfSortParser parser = new TmfSortParser(List.of("id"), false);
    assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of("-createdOn")));
  }

  @Test
  void parseSupportsMultipleSortParametersAndSkipsInvalidTokens() {
    TmfSortParser parser = new TmfSortParser(List.of(), false);
    Sort sort = parser.parse(List.of(",-name,+", "+id"));
    assertEquals(2, sort.toList().size());
    assertEquals("name", sort.toList().get(0).getProperty());
    assertEquals("id", sort.toList().get(1).getProperty());
  }

  @Test
  void parseRejectsNestedPropertyWhenDisabled() {
    TmfSortParser parser = new TmfSortParser(List.of(), false);
    assertThrows(IllegalArgumentException.class, () -> parser.parse(List.of("customer.name")));
  }

  @Test
  void parseReturnsUnsortedForNullInput() {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    assertEquals(Sort.unsorted(), parser.parse(null));
  }
}
