package io.github.jnesew.plainpoddroid.core.opml;

import java.io.IOException;
import java.util.List;

public interface OpmlMigrationStore {
    /** Read-only preview; a later commit recalculates against the current database. */
    OpmlImportPlan preview(OpmlDocument document) throws IOException;

    /** Atomic all-or-nothing import, without fetching any candidate feed. */
    OpmlImportPlan importConfirmed(OpmlDocument document) throws IOException;

    /** Returns only explicitly stored subscriptions for the user-selected export. */
    List<OpmlDocument.Feed> listForExport() throws IOException;
}
