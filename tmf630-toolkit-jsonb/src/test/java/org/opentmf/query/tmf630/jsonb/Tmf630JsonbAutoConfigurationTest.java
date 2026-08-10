package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    org.springframework.beans.factory.ObjectProvider<EntityManagerFactory> provider =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(emf);
    JsonbEntityRegistry registry = autoconfig.tmf630JsonbEntityRegistry(provider);

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
    org.springframework.beans.factory.ObjectProvider<EntityManagerFactory> provider =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(emf);
    JsonbEntityRegistry registry = autoconfig.tmf630JsonbEntityRegistry(provider);
    assertThat(registry.all()).isEmpty();
  }

  @Test
  @DisplayName("registry stays empty when no EntityManagerFactory is available")
  void emptyRegistryWhenNoEntityManagerFactory() {
    org.springframework.beans.factory.ObjectProvider<EntityManagerFactory> provider =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    Tmf630JsonbAutoConfiguration autoconfig = new Tmf630JsonbAutoConfiguration();
    JsonbEntityRegistry registry = autoconfig.tmf630JsonbEntityRegistry(provider);
    assertThat(registry.all()).isEmpty();
  }

  @Test
  @DisplayName("executor beans fail the boot with an actionable message when no Jackson 3 mapper bean exists")
  void missingObjectMapperFailsWithActionableMessage() {
    ObjectProvider<ObjectMapper> mapperProvider = mock(ObjectProvider.class);
    when(mapperProvider.getIfAvailable()).thenReturn(null);
    Tmf630JsonbAutoConfiguration autoconfig = new Tmf630JsonbAutoConfiguration();
    assertThatThrownBy(
            () ->
                autoconfig.tmf630JsonbWriteExecutor(
                    mock(JdbcClient.class), mapperProvider, new JsonbEntityRegistry()))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("tools.jackson.databind.ObjectMapper")
        .hasMessageContaining("JsonMapper");
  }

  @Test
  @DisplayName("executor beans build when a Jackson 3 mapper bean is present")
  void executorBeansBuildWithMapperPresent() {
    ObjectProvider<ObjectMapper> mapperProvider = mock(ObjectProvider.class);
    when(mapperProvider.getIfAvailable()).thenReturn(new ObjectMapper());
    Tmf630JsonbAutoConfiguration autoconfig = new Tmf630JsonbAutoConfiguration();
    JsonbEntityRegistry registry = new JsonbEntityRegistry();
    assertThat(autoconfig.tmf630JsonbWriteExecutor(mock(JdbcClient.class), mapperProvider, registry))
        .isNotNull();
    assertThat(autoconfig.tmf630JsonbVersionResolver(mock(JdbcClient.class), mapperProvider, registry))
        .isNotNull();
  }

  @Test
  @DisplayName("Tmf630JsonbConfigurationException with-cause constructor propagates cause")
  void exceptionWithCause() {
    RuntimeException cause = new RuntimeException("root");
    Tmf630JsonbConfigurationException ex =
        new Tmf630JsonbConfigurationException("wrapped", cause);
    assertThat(ex).hasCause(cause).hasMessage("wrapped");
  }

  static class AutoConfigTestDomain {}

  @Tmf630JsonbBacked(domainType = AutoConfigTestDomain.class)
  static class AutoConfigTestRow {
    @Id private String id;
    JsonNode payload;
  }

  static class AutoConfigTestPlainRow {
    @Id private String id;
  }
}
