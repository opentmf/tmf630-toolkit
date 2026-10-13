package org.opentmf.query.tmf630.queryparams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.queryparams.Tmf630QueryArgument.Kind;

class Tmf630QueryArgumentTest {

  @Test
  void theFactoriesCarryTheirKind() {
    assertEquals(Kind.NONE, Tmf630QueryArgument.none().kind());
    assertEquals(Kind.UNKNOWN, Tmf630QueryArgument.unknown().kind());
    assertEquals(Kind.FILTER, Tmf630QueryArgument.filter().kind());
    assertEquals(Set.of("a", "b"), Tmf630QueryArgument.named("a", "b").names());
  }

  @Test
  void onlyNamedCarriesNames_andAlwaysAtLeastOne() {
    assertThrows(IllegalArgumentException.class, Tmf630QueryArgument::named);
    Set<String> names = Set.of("a");
    assertThrows(IllegalArgumentException.class, () -> new Tmf630QueryArgument(Kind.NONE, names));
  }
}
