package model;

import java.util.Collections;
import java.util.List;

public class QueryResult {
    private final int numRows;
    private final List<String> columnNames;
    private final List<Object[]> rows;

    public QueryResult(int numRows) {
        this.numRows = numRows;
        this.columnNames = Collections.emptyList();
        this.rows = Collections.emptyList();
    }

    public QueryResult(List<String> columnNames, List<Object[]> rows) {
        this.numRows = rows.size();
        this.columnNames = List.copyOf(columnNames);
        this.rows = List.copyOf(rows);
    }

    public int getNumRows() {
        return numRows;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public List<Object[]> getRows() {
        return rows;
    }

    public boolean hasRows() {
        return !columnNames.isEmpty();
    }
}
