import model.ColumnSchema;
import model.ColumnSchema.COLUMN_TYPES;
import model.SimpleQuery;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class SelectTask implements Callable<List<Object[]>> {
    private final COLUMN_TYPES queryColumnType;
    private final SimpleQuery query;
    private final File colIndexShardFile;
    private final File dataShardFile;
    private final int queryColumnBytes;
    private final int bytesPerRow;
    private final List<ColumnReadPlan> selectedColumns;
    private final int limit;
    private final byte[] indexValueBytes;
    private final byte[] queryValueBytes;
    private final int queryValueAsInt;

    public SelectTask(COLUMN_TYPES queryColumnType, SimpleQuery query, File colIndexShardFile, File dataShardFile,
                      int queryColumnBytes, int bytesPerRow, List<ColumnReadPlan> selectedColumns, int limit) {
        this.queryColumnType = queryColumnType;
        this.query = query;
        this.colIndexShardFile = colIndexShardFile;
        this.dataShardFile = dataShardFile;
        this.queryColumnBytes = queryColumnBytes;
        this.bytesPerRow = bytesPerRow;
        this.selectedColumns = selectedColumns;
        this.limit = limit;
        this.indexValueBytes = new byte[queryColumnBytes];

        if (queryColumnType == COLUMN_TYPES.INT_TYPE) {
            this.queryValueAsInt = Integer.parseInt(query.getValue());
            this.queryValueBytes = null;
        } else {
            this.queryValueAsInt = 0;
            this.queryValueBytes = toFixedStringBytes(query.getValue(), queryColumnBytes);
        }
    }

    @Override
    public List<Object[]> call() throws Exception {
        if (limit == 0) {
            return List.of();
        }

        try (FileChannel idxChannel = FileChannel.open(colIndexShardFile.toPath(), StandardOpenOption.READ);
             FileChannel dataChannel = FileChannel.open(dataShardFile.toPath(), StandardOpenOption.READ)) {

            long idxSize = idxChannel.size();
            long dataSize = dataChannel.size();
            if (idxSize == 0 || dataSize == 0) {
                return List.of();
            }

            if (dataSize % bytesPerRow != 0) {
                throw new IOException("Malformed data shard [" + dataShardFile + "]");
            }

            MappedByteBuffer idxBuffer = idxChannel.map(FileChannel.MapMode.READ_ONLY, 0, idxSize);
            MappedByteBuffer dataBuffer = dataChannel.map(FileChannel.MapMode.READ_ONLY, 0, dataSize);

            int position = 0;
            while (position < idxSize) {
                requireRemaining(idxSize, position, queryColumnBytes + 4);
                idxBuffer.position(position);

                boolean valueMatches;
                if (queryColumnType == COLUMN_TYPES.INT_TYPE) {
                    valueMatches = idxBuffer.getInt() == queryValueAsInt;
                } else {
                    idxBuffer.get(indexValueBytes, 0, queryColumnBytes);
                    valueMatches = fixedBytesEqual(indexValueBytes, queryValueBytes);
                }

                position += queryColumnBytes;
                idxBuffer.position(position);

                int numIndexes = idxBuffer.getInt();
                requireRemaining(idxSize, position + 4, numIndexes * 4);

                if (valueMatches) {
                    return readRows(idxBuffer, dataBuffer, dataSize, numIndexes);
                }

                position += 4 + (numIndexes * 4);
            }

            return List.of();
        }
    }

    private List<Object[]> readRows(MappedByteBuffer idxBuffer, MappedByteBuffer dataBuffer, long dataSize, int numIndexes) throws IOException {
        int rowLimit = limit < 0 ? numIndexes : Math.min(limit, numIndexes);
        List<Object[]> rows = new ArrayList<>(rowLimit);

        for (int i = 0; i < numIndexes && rows.size() < rowLimit; i++) {
            int rowIndex = idxBuffer.getInt();
            int rowOffset = rowIndex * bytesPerRow;
            if (rowOffset < 0 || dataSize - rowOffset < bytesPerRow) {
                throw new IOException("Index entry points outside data shard [" + dataShardFile + "]");
            }

            rows.add(readSelectedColumns(dataBuffer, rowOffset));
        }

        return rows;
    }

    private Object[] readSelectedColumns(MappedByteBuffer dataBuffer, int rowOffset) {
        Object[] row = new Object[selectedColumns.size()];
        for (int i = 0; i < selectedColumns.size(); i++) {
            ColumnReadPlan column = selectedColumns.get(i);
            int valueOffset = rowOffset + column.getOffset();

            if (column.getColumnType() == COLUMN_TYPES.INT_TYPE) {
                row[i] = dataBuffer.getInt(valueOffset);
            } else {
                byte[] valueBytes = new byte[column.getNumBytes()];
                for (int j = 0; j < valueBytes.length; j++) {
                    valueBytes[j] = dataBuffer.get(valueOffset + j);
                }

                int length = 0;
                while (length < valueBytes.length && valueBytes[length] != 0) {
                    length++;
                }
                row[i] = new String(valueBytes, 0, length, StandardCharsets.UTF_8);
            }
        }

        return row;
    }

    private void requireRemaining(long fileSize, int position, int bytesNeeded) throws IOException {
        if (bytesNeeded < 0 || fileSize - position < bytesNeeded) {
            throw new IOException("Malformed index file [" + colIndexShardFile + "]");
        }
    }

    private byte[] toFixedStringBytes(String value, int numBytes) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > numBytes) {
            return null;
        }

        byte[] fixedBytes = new byte[numBytes];
        System.arraycopy(encoded, 0, fixedBytes, 0, encoded.length);
        return fixedBytes;
    }

    private boolean fixedBytesEqual(byte[] left, byte[] right) {
        if (right == null) {
            return false;
        }

        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return false;
            }
        }

        return true;
    }
}
