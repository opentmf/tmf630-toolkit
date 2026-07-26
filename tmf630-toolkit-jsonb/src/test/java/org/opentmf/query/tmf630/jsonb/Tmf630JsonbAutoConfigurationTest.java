package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Tmf630JsonbAutoConfigurationTest {

  @Test
  @DisplayName("registers @Tmf630JsonbBacked entities discovered via the JPA metamodel")
  void registersAnnotatedEntities() {
    Metamodel metamodel = mock(Metamodel.class);
    EntityType<AutoConfigTestRow> annotatedEntity = mock(EntityType.class);
    when(annotatedEntity.getJavaType()).thenReturn(AutoConfigTestRow.class);
    EntityType<AutoConfigTestPlainRow> plainEntity = mock(EntityType.class);
    when(plainEntity.getJavaType()).thenReturn(AutoConfigTestPlainRow.class);
    when(metamodel.getEntities()).thenReturn(Set.of(annotatedEntity, plainEntity));

    EntityManagerFactory emf = mock(EntityManagerFactory.class);
    when(emf.getMetamodel()).thenReturn(metamodel);

    Tmf630JsonbAutoConfiguration autoconfig = new Tmf630JsonbAutoConfiguration();
    JsonbEntityRegistry registry = autoconfig.tmf630JsonbEntityRegistry(emf);

    assertThat(registry.all()).hasSize(1);
    assertThat(registry.forRowType(AutoConfigTestRow.class)).isPresent();
    assertThat(registry.forRowType(AutoConfigTestPlainRow.class)).isEmpty();
  }

  @Test
  @DisplayName("empty registry when no @Tmf630JsonbBacked entities present")
  void emptyRegistryWhenNoneAnnotated() {
    Metamodel metamodel = mock(Metamodel.class);
    when(metamodel.getEntities()).thenReturn(Set.of());
    EntityManagerFactory emf = mock(EntityManagerFactory.class);
    when(emf.getMetamodel()).thenReturn(metamodel);

    Tmf630JsonbAutoConfiguration autoconfig = new Tmf630JsonbAutoConfiguration();
    JsonbEntityRegistry registry = autoconfig.tmf630JsonbEntityRegistry(emf);
    assertThat(registry.all()).isEmpty();
  }

  @Test
  @DisplayName("Tmf630JsonbConfigurationException with-cause constructor propagates cause")
  void exceptionWithCause() {
    RuntimeException cause = new RuntimeException("root");
    Tmf630JsonbConfigurationException ex =
        new Tmf630JsonbConfigurationException("wrapped", cause);
    assertThat(ex).hasCause(cause);
    assertThat(ex).hasMessage("wrapped");
  }

  static class AutoConfigTestDomain {}

  @Entity
  @Tmf630JsonbBacked(domainType = AutoConfigTestDomain.class)
  static class AutoConfigTestRow {
    @Id private String id;
    private JsonNode payload;
  }

  @Entity
  static class AutoConfigTestPlainRow {
    @Id private String id;
  }
}
