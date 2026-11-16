package org.opentmf.query.tmf630.jsonb;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.Assert;

/**
 * Runtime descriptor for a row entity annotated with {@link Tmf630JsonbBacked}. Captured
 * once per row entity via reflection at bean-registration time; consumed by the
 * predicate/sort translators in later Phase (b) sub-milestones.
 *
 * <p>Immutable value object; created via {@link #of(Class)} which performs the reflection
 * scan and validation. A {@link Tmf630JsonbConfigurationException} is raised if the row
 * entity fails validation (annotation missing, payload field absent, etc.).
 */
public record JsonbEntityMetadata(
    Class<?> rowType,
    Class<?> domainType,
    String payloadField,
    String tableName,
    JsonbAuditColumns auditColumns,
    List<JsonbSplitCollectionMetadata> splitCollections,
    List<JsonbDeclaredColumn> declaredColumns) {

  /** The scalar types a {@link Tmf630JsonbColumn} may copy, besides enums and primitives. */
  private static final Set<Class<?>> SCALARS =
      Set.of(
          String.class,
          Integer.class,
          Long.class,
          Short.class,
          Byte.class,
          Double.class,
          Float.class,
          BigDecimal.class,
          BigInteger.class,
          Boolean.class,
          LocalDate.class,
          LocalTime.class,
          LocalDateTime.class,
          OffsetDateTime.class,
          Instant.class,
          UUID.class);

  /** The metadata without declared columns, as before 3.6.0. */
  public JsonbEntityMetadata(
      Class<?> rowType,
      Class<?> domainType,
      String payloadField,
      String tableName,
      JsonbAuditColumns auditColumns,
      List<JsonbSplitCollectionMetadata> splitCollections) {
    this(rowType, domainType, payloadField, tableName, auditColumns, splitCollections, List.of());
  }

  public JsonbEntityMetadata {
    Assert.notNull(rowType, "rowType must not be null");
    Assert.notNull(domainType, "domainType must not be null");
    Assert.hasText(payloadField, "payloadField must not be blank");
    Assert.hasText(tableName, "tableName must not be blank");
    Assert.notNull(auditColumns, "auditColumns must not be null");
    splitCollections = splitCollections == null ? List.of() : List.copyOf(splitCollections);
    declaredColumns = declaredColumns == null ? List.of() : List.copyOf(declaredColumns);
  }

  /** The column declared for the domain path {@code path}, if any ({@link Tmf630JsonbColumn}). */
  public Optional<JsonbDeclaredColumn> declaredColumn(String path) {
    for (JsonbDeclaredColumn declared : declaredColumns) {
      if (declared.path().equals(path)) {
        return Optional.of(declared);
      }
    }
    return Optional.empty();
  }

  /**
   * Introspects a candidate row entity and produces its metadata, or throws
   * {@link Tmf630JsonbConfigurationException} if it is not a valid JSONB-backed entity.
   *
   * <p>Validations performed:
   *
   * <ul>
   *   <li>The class carries {@code @Tmf630JsonbBacked}.
   *   <li>A field named per {@code @Tmf630JsonbBacked#payloadField} exists (walks
   *       superclasses).
   *   <li>The domain type from the annotation is a concrete class (not an interface,
   *       not annotation-only).
   * </ul>
   */
  public static JsonbEntityMetadata of(Class<?> rowType) {
    Assert.notNull(rowType, "rowType must not be null");
    Tmf630JsonbBacked annotation = rowType.getAnnotation(Tmf630JsonbBacked.class);
    if (annotation == null) {
      throw new Tmf630JsonbConfigurationException(
          "Row entity is not @Tmf630JsonbBacked: " + rowType.getName());
    }
    Class<?> domainType = annotation.domainType();
    if (domainType == null || domainType == Void.class) {
      throw new Tmf630JsonbConfigurationException(
          "@Tmf630JsonbBacked.domainType() must be a concrete class on " + rowType.getName());
    }
    String payloadField = annotation.payloadField();
    if (findField(rowType, payloadField).isEmpty()) {
      throw new Tmf630JsonbConfigurationException(
          "Row entity "
              + rowType.getName()
              + " is missing the payload field '"
              + payloadField
              + "' declared by @Tmf630JsonbBacked.payloadField().");
    }
    return new JsonbEntityMetadata(
        rowType,
        domainType,
        payloadField,
        resolveTableName(rowType),
        JsonbAuditColumns.discover(rowType),
        discoverSplitCollections(domainType),
        discoverDeclaredColumns(rowType, domainType));
  }

  /**
   * The row entity's {@link Tmf630JsonbColumn} fields (walks superclasses), each validated against
   * the domain type: the path names a scalar at a fixed position, the field's type is the domain
   * field's (an enum may also be copied into a {@code String}), and no path is declared twice.
   */
  private static List<JsonbDeclaredColumn> discoverDeclaredColumns(
      Class<?> rowType, Class<?> domainType) {
    List<JsonbDeclaredColumn> out = new ArrayList<>();
    Set<String> paths = new HashSet<>();
    for (Class<?> cursor = rowType;
        cursor != null && cursor != Object.class;
        cursor = cursor.getSuperclass()) {
      for (Field field : cursor.getDeclaredFields()) {
        Tmf630JsonbColumn annotation = field.getAnnotation(Tmf630JsonbColumn.class);
        if (annotation == null) {
          continue;
        }
        String path = annotation.path();
        Class<?> domainLeaf = scalarAt(rowType, domainType, path);
        requireSameType(rowType, field, path, domainLeaf);
        if (!paths.add(path)) {
          throw declaredColumnError(rowType, path, "is declared by two fields");
        }
        out.add(new JsonbDeclaredColumn(path, columnName(field), field.getName(), box(field.getType())));
      }
    }
    return out;
  }

  /** The type of the scalar field at {@code path} of the domain type, or a configuration error. */
  private static Class<?> scalarAt(Class<?> rowType, Class<?> domainType, String path) {
    if (path == null || path.isBlank()) {
      throw declaredColumnError(rowType, String.valueOf(path), "is blank");
    }
    String domainName = domainType.getSimpleName();
    Class<?> current = domainType;
    String[] segments = path.split("\\.", -1);
    for (int i = 0; i < segments.length; i++) {
      Optional<Field> field = findField(current, segments[i]);
      if (field.isEmpty()) {
        throw declaredColumnError(rowType, path, "names no field of " + domainName);
      }
      Class<?> type = field.get().getType();
      if (Collection.class.isAssignableFrom(type) || type.isArray()) {
        throw declaredColumnError(rowType, path, "goes through or ends in a list: a column holds one value");
      }
      if (i == segments.length - 1) {
        if (!isScalar(type)) {
          throw declaredColumnError(rowType, path, "is not a scalar (" + type.getSimpleName() + ")");
        }
        return box(type);
      }
      current = type;
    }
    throw declaredColumnError(rowType, path, "is empty");
  }

  private static void requireSameType(Class<?> rowType, Field field, String path, Class<?> domainLeaf) {
    if (field.getType().isPrimitive()) {
      throw declaredColumnError(
          rowType,
          path,
          "is on the primitive field '" + field.getName() + "': use "
              + box(field.getType()).getSimpleName()
              + ", since a field absent from the payload is a NULL column");
    }
    Class<?> columnType = box(field.getType());
    boolean same = columnType.equals(domainLeaf);
    boolean enumAsText = domainLeaf.isEnum() && columnType == String.class;
    if (!same && !enumAsText) {
      throw declaredColumnError(
          rowType,
          path,
          "is a " + domainLeaf.getSimpleName() + " in the domain type, but field '" + field.getName()
              + "' is a " + columnType.getSimpleName());
    }
  }

  private static boolean isScalar(Class<?> type) {
    return type.isPrimitive() || type.isEnum() || SCALARS.contains(type);
  }

  private static String columnName(Field field) {
    Column column = field.getAnnotation(Column.class);
    if (column != null && !column.name().isBlank()) {
      return column.name();
    }
    return snakeCase(field.getName());
  }

  private static Class<?> box(Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    return MethodType.methodType(type).wrap().returnType();
  }

  private static Tmf630JsonbConfigurationException declaredColumnError(
      Class<?> rowType, String path, String problem) {
    return new Tmf630JsonbConfigurationException(
        "@Tmf630JsonbColumn(path = \"" + path + "\") on " + rowType.getName() + " " + problem + ".");
  }

  /**
   * Scans the domain type (walks superclasses) for {@link Tmf630JsonbSplitCollection}
   * annotations. Each such field produces one {@link JsonbSplitCollectionMetadata} entry.
   * Empty list if none — the entity remains a plain single-table JSONB row.
   */
  private static List<JsonbSplitCollectionMetadata> discoverSplitCollections(Class<?> domainType) {
    List<JsonbSplitCollectionMetadata> out = new ArrayList<>();
    Class<?> cursor = domainType;
    while (cursor != null && cursor != Object.class) {
      for (Field field : cursor.getDeclaredFields()) {
        Tmf630JsonbSplitCollection annotation =
            field.getAnnotation(Tmf630JsonbSplitCollection.class);
        if (annotation != null) {
          out.add(JsonbSplitCollectionMetadata.from(field.getName(), annotation));
        }
      }
      cursor = cursor.getSuperclass();
    }
    return out;
  }

  private static String resolveTableName(Class<?> rowType) {
    Table table = rowType.getAnnotation(Table.class);
    if (table != null && !table.name().isBlank()) {
      return table.name();
    }
    // JPA default: entity class name with camelCase → snake_case per Hibernate's
    // ImplicitNamingStrategy; downstream services that rely on the default may prefer
    // to declare @Table(name=...) explicitly to avoid surprises. Toolkit's fallback
    // here mirrors Hibernate 6+ PhysicalNamingStrategyStandardImpl behavior for the
    // common camelCase-to-snake_case path.
    return snakeCase(rowType.getSimpleName());
  }

  /** camelCase to snake_case, as Hibernate's default naming writes it. */
  private static String snakeCase(String name) {
    StringBuilder snake = new StringBuilder(name.length() + 8);
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (i > 0 && Character.isUpperCase(c)) {
        snake.append('_');
      }
      snake.append(Character.toLowerCase(c));
    }
    return snake.toString();
  }

  static Optional<Field> findField(Class<?> type, String name) {
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      try {
        return Optional.of(cursor.getDeclaredField(name));
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      }
    }
    return Optional.empty();
  }
}
