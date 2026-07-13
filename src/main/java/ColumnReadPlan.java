import model.ColumnSchema;

class ColumnReadPlan {
    private final String columnName;
    private final ColumnSchema.COLUMN_TYPES columnType;
    private final int offset;
    private final int numBytes;

    ColumnReadPlan(String columnName, ColumnSchema.COLUMN_TYPES columnType, int offset, int numBytes) {
        this.columnName = columnName;
        this.columnType = columnType;
        this.offset = offset;
        this.numBytes = numBytes;
    }

    String getColumnName() {
        return columnName;
    }

    ColumnSchema.COLUMN_TYPES getColumnType() {
        return columnType;
    }

    int getOffset() {
        return offset;
    }

    int getNumBytes() {
        return numBytes;
    }
}
