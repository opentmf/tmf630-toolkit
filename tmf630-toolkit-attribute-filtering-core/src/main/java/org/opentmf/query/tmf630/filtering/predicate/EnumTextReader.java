package org.opentmf.query.tmf630.filtering.predicate;

import java.util.Optional;

/**
 * Reads the text of an enum filter value the way the service's payloads spell it (a wire value
 * such as {@code inProgress} for the constant {@code IN_PROGRESS}). {@link ValueConverter} asks
 * it only after the enum's public static String factory methods and its constant names have all
 * failed, so a text that works by name keeps working, and a text that is one constant's name and
 * another constant's wire value resolves by the name.
 */
@FunctionalInterface
public interface EnumTextReader {

  /** Reads nothing: an enum value is accepted only by a factory method or the constant name. */
  EnumTextReader NONE = (enumType, text) -> Optional.empty();

  /**
   * The constant of {@code enumType} that {@code text} spells, or empty when it spells none, in
   * which case the converter answers 400.
   */
  Optional<Enum<?>> read(Class<?> enumType, String text);
}
