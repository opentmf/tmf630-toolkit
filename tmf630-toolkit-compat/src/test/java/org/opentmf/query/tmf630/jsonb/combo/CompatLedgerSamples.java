package org.opentmf.query.tmf630.jsonb.combo;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The samples of the 2026-11-13 compatibility ledger's probe (the requests its Mongo and JPA
 * columns were measured with), and their seeds, unchanged: the report re-asks them of every
 * version on Mongo ({@link CompatLedgerDoc}), JPA ({@link CompatLedgerRow}, a joined collection)
 * and, for acceptance only, a root with JSON-typed fields ({@link CompatLedgerJson}).
 */
final class CompatLedgerSamples {

  static final List<String[]> REQUESTS =
      List.of(
          new String[] {"s", "42"},
          new String[] {"n", "42"},
          new String[] {"n.gt", "4"},
          new String[] {"ld.gt", "2025-01-01"},
          new String[] {"state", "IN_PROGRESS"},
          new String[] {"state", "inProgress"},
          new String[] {"state.eqi", "in_progress"},
          new String[] {"s.contains", "50%"},
          new String[] {"s.contains", "x\\y"},
          new String[] {"s.startswith", "50%"},
          new String[] {"items.b", "x"},
          new String[] {"items.b.isnull", "true"},
          new String[] {"items.b.isnotnull", "true"},
          new String[] {"items.b.ne", "x"},
          new String[] {"items.b.nin", "x"},
          new String[] {"t.ne", "v"},
          new String[] {"t.nin", "v"},
          new String[] {"filter", "$[?(@.t == null)]"},
          new String[] {"filter", "$[?(@.t != null)]"},
          new String[] {"filter", "$[?(!@.t)]"},
          new String[] {"filter", "$[?(@.t != 'v')]"},
          new String[] {"filter", "$[?(@.items[?(@.b == 'x')])]"},
          new String[] {"filter", "$[?(@.items[?(@.b != 'x')])]"},
          new String[] {"n.contains", "5"},
          new String[] {"o.gt", "5"},
          new String[] {"o.contains", "5"},
          new String[] {"filter", "$[?(@.n =~ /5/)]"},
          new String[] {"filter", "$[?(@.n > null)]"},
          new String[] {"filter", "$[?(exists(@.s))]"},
          new String[] {"filter", "$[?(@.s == 'a') || (@.s == 'b')]"},
          new String[] {"filter", "$[?(@.s in ['a'])]"},
          new String[] {"filter", "$[?(!(@.s == 'a'))]"},
          new String[] {"filter", "$[?(@.s.type() == 'string')]"},
          new String[] {"filter", "$[?(@.n == 'abc')]"},
          new String[] {"n.between", "1,2,3"},
          new String[] {"s.regex", "^(a|b)c+$"},
          new String[] {"m", "x"},
          new String[] {"m.isnull", "true"},
          new String[] {"filter", "$[?(@.m == 'x')]"},
          new String[] {"filter", "$[?(@.characteristic[?(@.name == 'x' && @.value == 'y')])]"},
          new String[] {"filter", "$[?(@.characteristic[?(@.name == 'x')])]"});

  private CompatLedgerSamples() {}

  /** Writes the Mongo documents, values of another JSON type than declared included. */
  static void seedMongo(MongoTemplate mongo) {
    var collection = mongo.getDb().getCollection(CompatLedgerDoc.COLLECTION);
    collection.drop();
    Map<String, Document> docs = new LinkedHashMap<>();
    docs.put("S42", new Document("s", 42));
    docs.put("Salpha", new Document("s", "alpha"));
    docs.put("N42s", new Document("n", "42"));
    docs.put("N42", new Document("n", 42));
    docs.put("N42d", new Document("n", 42.0));
    docs.put("Nabc", new Document("n", "abc"));
    docs.put("N5s", new Document("n", "5"));
    docs.put("N9", new Document("n", 9));
    docs.put("LDx", new Document("ld", "someday"));
    docs.put(
        "LD",
        new Document("ld", Date.from(LocalDate.of(2025, 6, 30).atStartOfDay(ZoneOffset.UTC).toInstant())));
    docs.put("Ewire", new Document("state", "inProgress"));
    docs.put("Ename", new Document("state", "IN_PROGRESS"));
    docs.put("PCT", new Document("s", "50% off"));
    docs.put("NUM", new Document("s", "500 off"));
    docs.put("BSL", new Document("s", "x\\y"));
    docs.put("XY", new Document("s", "xy"));
    docs.put("A_x", new Document("items", List.of(new Document("b", "x"))));
    docs.put("A_xz", new Document("items", List.of(new Document("b", "x"), new Document("b", "z"))));
    docs.put("A_z", new Document("items", List.of(new Document("b", "z"))));
    docs.put("A_nob", new Document("items", List.of(new Document())));
    docs.put("A_bnull", new Document("items", List.of(new Document("b", null))));
    docs.put("A_empty", new Document("items", List.of()));
    docs.put("Tnull", new Document("t", null));
    docs.put("Tval", new Document("t", "v"));
    docs.put("Tw", new Document("t", "w"));
    docs.put("O5", new Document("o", 7).append("m", new Document("k", "x")));
    docs.forEach((id, d) -> collection.insertOne(d.append("_id", id)));
  }

  /** Writes the JPA rows; called inside a transaction. */
  static void seedJpa(EntityManager em) {
    row(em, "PCT", r -> r.s = "50% off");
    row(em, "NUM", r -> r.s = "500 off");
    row(em, "BSL", r -> r.s = "x\\y");
    row(em, "XY", r -> r.s = "xy");
    row(em, "N42", r -> r.n = 42);
    row(em, "N9", r -> r.n = 9);
    row(em, "LD", r -> r.ld = LocalDate.of(2025, 6, 30));
    row(em, "Ename", r -> r.state = ComboStage.IN_PROGRESS);
    row(em, "Tval", r -> r.t = "v");
    row(em, "Tw", r -> r.t = "w");
    row(em, "A_x", r -> items(r, "x"));
    row(em, "A_xz", r -> items(r, "x", "z"));
    row(em, "A_z", r -> items(r, "z"));
    row(em, "A_bnull", r -> items(r, (String) null));
    row(em, "A_empty", r -> {});
  }

  private static void row(EntityManager em, String id, Consumer<CompatLedgerRow> init) {
    CompatLedgerRow r = new CompatLedgerRow();
    r.id = id;
    init.accept(r);
    em.persist(r);
  }

  private static void items(CompatLedgerRow r, String... bs) {
    for (String b : bs) {
      CompatLedgerItem item = new CompatLedgerItem();
      item.b = b;
      item.row = r;
      r.items.add(item);
    }
  }
}
