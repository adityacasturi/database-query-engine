import model.ColumnSchema;
import model.QueryResult;
import model.SimpleQuery;
import model.TableSchema;

import java.util.ArrayList;
import java.util.List;

public class QueryHandler {
    public static QueryResult handle(String dbName, String tableName, String columnName, String columnValue) throws Exception {
        TableSchema tableSchema = loadAndValidateQuery(dbName, tableName, columnName, columnValue);

        SimpleQuery sq = new SimpleQuery(dbName, tableSchema, columnName, columnValue);
        int rows = QueryExecutor.execute(sq);

        return new QueryResult(rows);
    }

    public static QueryResult handleSelect(String dbName, String tableName, List<String> selectedColumns,
                                           String columnName, String columnValue, int limit) throws Exception {
        TableSchema tableSchema = loadAndValidateQuery(dbName, tableName, columnName, columnValue);
        List<String> normalizedSelectedColumns = normalizeSelectedColumns(tableSchema, selectedColumns);

        SimpleQuery sq = new SimpleQuery(dbName, tableSchema, columnName, columnValue);
        List<Object[]> rows = QueryExecutor.executeSelect(sq, normalizedSelectedColumns, limit);
        List<String> resultColumnNames = QueryExecutor.expandSelectedColumns(tableSchema, normalizedSelectedColumns);

        return new QueryResult(resultColumnNames, rows);
    }

    private static TableSchema loadAndValidateQuery(String dbName, String tableName, String columnName, String columnValue) throws Exception {
        TableSchema tableSchema;
        try {
            tableSchema = DatabaseExplorer.getTableSchema(dbName, tableName);
        } catch (Exception e) {
            throw new IllegalArgumentException("Table [" + tableName + "] does not exist");
        }

        ColumnSchema matchedColumn = findColumn(tableSchema, columnName);
        if (matchedColumn == null) {
            throw new IllegalArgumentException("Column [" + columnName + "] does not exist in table [" + tableName + "]");
        }

        if (!matchedColumn.isIndexed()) {
            throw new IllegalArgumentException("Column [" + columnName + "] is not indexed in table [" + tableName + "]");
        }

        if (matchedColumn.getColumnType() == ColumnSchema.COLUMN_TYPES.INT_TYPE) {
            try {
                Integer.parseInt(columnValue);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Value [" + columnValue + "] is not a valid integer for column [" + columnName + "]");
            }
        }

        return tableSchema;
    }

    private static List<String> normalizeSelectedColumns(TableSchema tableSchema, List<String> selectedColumns) {
        if (selectedColumns.size() == 1 && selectedColumns.getFirst().equals("*")) {
            return List.of("*");
        }

        List<String> normalizedColumns = new ArrayList<>();
        for (String selectedColumn : selectedColumns) {
            String normalizedColumn = selectedColumn.trim();
            if (normalizedColumn.isEmpty()) {
                throw new IllegalArgumentException("Selected column cannot be empty");
            }
            if (findColumn(tableSchema, normalizedColumn) == null) {
                throw new IllegalArgumentException("Selected column [" + normalizedColumn + "] does not exist in table [" + tableSchema.getTableName() + "]");
            }
            normalizedColumns.add(normalizedColumn);
        }

        return normalizedColumns;
    }

    private static ColumnSchema findColumn(TableSchema tableSchema, String columnName) {
        for (ColumnSchema colSchema : tableSchema.getColumns()) {
            if (colSchema.getColumnName().equals(columnName)) {
                return colSchema;
            }
        }

        return null;
    }
}
