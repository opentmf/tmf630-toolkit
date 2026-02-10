# tmf630-toolkit

`tmf630-toolkit` is a multi-module Java library that bundles TMF630-style query utilities for Spring Boot and pure Spring applications.

## Module layout

| Module | Description |
|--------|-------------|
| **tmf630-toolkit-paging-sorting-core** | Paging, sorting, field selection (no Boot) |
| **tmf630-toolkit-paging-sorting-autoconfigure** | Spring Boot auto-configuration for paging/sorting |
| **tmf630-toolkit-attribute-filtering-core** | Attribute filtering to QueryDSL Predicate (no Boot) |
| **tmf630-toolkit-attribute-filtering-autoconfigure** | Spring Boot auto-configuration for filtering |
| **tmf630-toolkit-all** | Convenience aggregator depending on both autoconfigure modules |

### Module choices

- **Spring Boot users**: Add `tmf630-toolkit-all` (or individual autoconfigure modules). Auto-configuration registers paging and attribute-filtering resolvers.
- **Non-Boot users**: Add `tmf630-toolkit-paging-sorting-core` and/or `tmf630-toolkit-attribute-filtering-core`, then wire resolvers and beans manually.

## Installation

### All-in-one (recommended for Spring Boot)

```xml
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-all</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Individual autoconfigure modules (Spring Boot)

```xml
<!-- Paging, sorting, field selection -->
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-paging-sorting-autoconfigure</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Attribute filtering (QueryDSL Predicate) -->
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-attribute-filtering-autoconfigure</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Core only (non-Boot, manual wiring)

```xml
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-paging-sorting-core</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>

<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-attribute-filtering-core</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Dependency versions are aligned via `spring-boot-dependencies` BOM (3.5.10).

## Configuration

| Prefix | Module |
|--------|--------|
| `opentmf.tmf630.paging` | paging-sorting |
| `opentmf.tmf630.attribute-filtering` | attribute-filtering |

See module READMEs for property details.

## Build

```bash
mvn clean test
```

## License

Use your organization or repository license policy for distribution and reuse.
