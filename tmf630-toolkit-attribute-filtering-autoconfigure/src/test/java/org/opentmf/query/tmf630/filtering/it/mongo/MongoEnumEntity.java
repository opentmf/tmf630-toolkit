package org.opentmf.query.tmf630.filtering.it.mongo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.querydsl.core.annotations.QueryEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Mongo document with the three enum shapes a URL must be able to name by wire value: a
 * {@code @JsonValue} enum without a String factory, a {@code @JsonProperty} enum, and one whose
 * constants' wire values are each other's names. Spring Data stores each by its constant name.
 */
@QueryEntity
@Document("mongo_enum_entity")
public class MongoEnumEntity {

  /** {@code @JsonValue}, no factory. */
  public enum Stage {
    IN_PROGRESS("inProgress"),
    DONE("done");

    private final String wire;

    Stage(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  /** {@code @JsonProperty} on the constants. */
  public enum Phase {
    @JsonProperty("phase-one")
    ONE,
    @JsonProperty("phase-two")
    TWO
  }

  /** Each constant's wire value is the other constant's name. */
  public enum Clash {
    LEFT("RIGHT"),
    RIGHT("LEFT");

    private final String wire;

    Clash(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  @Id private String id;
  private Stage stage;
  private Phase phase;
  private Clash clash;

  public MongoEnumEntity() {}

  public MongoEnumEntity(String id, Stage stage, Phase phase, Clash clash) {
    this.id = id;
    this.stage = stage;
    this.phase = phase;
    this.clash = clash;
  }

  public String getId() {
    return id;
  }

  public Stage getStage() {
    return stage;
  }

  public Phase getPhase() {
    return phase;
  }

  public Clash getClash() {
    return clash;
  }
}
