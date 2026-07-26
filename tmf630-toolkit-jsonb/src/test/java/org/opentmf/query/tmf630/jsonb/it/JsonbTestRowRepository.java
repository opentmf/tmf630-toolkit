package org.opentmf.query.tmf630.jsonb.it;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Standard Spring Data JPA repository used to write test fixtures (INSERT) and clean up
 * (DELETE). Read paths in the b.5 IT go through {@link org.opentmf.query.tmf630.jsonb.Tmf630JsonbFilterExecutor}
 * rather than this repository — the point is to exercise the JSONB executor's own SQL
 * emission, not Hibernate's.
 */
public interface JsonbTestRowRepository extends JpaRepository<JsonbTestRow, String> {}
