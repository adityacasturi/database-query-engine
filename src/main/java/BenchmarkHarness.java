import model.ColumnSchema;
import model.QueryResult;
import model.SimpleQuery;
import model.TableSchema;

import java.io.File;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;

public class BenchmarkHarness {
    private static final String STORAGE_PROPERTY = "db.storage.dir";

    public static void main(String[] args) throws Exception {
        BenchmarkOptions options = BenchmarkOptions.parse(args);
        configureStorage(options);

        if (options.cleanBeforeRun) {
            deleteRecursively(Path.of(Constants.STORAGE_LOC, options.databaseName));
        }

        long generationStart = System.nanoTime();
        TableSchema tableSchema = DataGenerator.generateCityData(
                options.databaseName,
                options.tableName,
                options.shards,
                options.rowsPerShard,
                options.seed);
        long generationNanos = System.nanoTime() - generationStart;

        SimpleQuery query = new SimpleQuery(options.databaseName, tableSchema, options.columnName, options.columnValue);
        List<String> selectedColumns = parseSelectedColumns(options.selectColumns);
        for (int i = 0; i < options.warmupRuns; i++) {
            QueryExecutor.execute(query);
            fullScanCount(query);
            heapLoadedFullScanCount(query);
            QueryHandler.handleSelect(options.databaseName, options.tableName, selectedColumns, options.columnName, options.columnValue, options.selectLimit);
            fullScanSelect(query, selectedColumns, options.selectLimit);
        }

        List<Long> indexedTimes = new ArrayList<>();
        List<Long> fullScanTimes = new ArrayList<>();
        List<Long> indexedSelectTimes = new ArrayList<>();
        List<Long> fullScanSelectTimes = new ArrayList<>();
        int indexedCount = 0;
        int fullScanResultCount = 0;
        int indexedSelectRows = 0;
        int fullScanSelectRows = 0;
        ScanResult heapScanResult = null;

        for (int i = 0; i < options.measuredRuns; i++) {
            long indexedStart = System.nanoTime();
            QueryResult indexedResult = QueryHandler.handle(options.databaseName, options.tableName, options.columnName, options.columnValue);
            indexedTimes.add(System.nanoTime() - indexedStart);
            indexedCount = indexedResult.getNumRows();

            long scanStart = System.nanoTime();
            fullScanResultCount = fullScanCount(query);
            fullScanTimes.add(System.nanoTime() - scanStart);

            if (heapScanResult == null) {
                heapScanResult = heapLoadedFullScanCount(query);
            }

            long indexedSelectStart = System.nanoTime();
            QueryResult indexedSelectResult = QueryHandler.handleSelect(options.databaseName, options.tableName,
                    selectedColumns, options.columnName, options.columnValue, options.selectLimit);
            indexedSelectTimes.add(System.nanoTime() - indexedSelectStart);
            indexedSelectRows = indexedSelectResult.getNumRows();

            long fullScanSelectStart = System.nanoTime();
            fullScanSelectRows = fullScanSelect(query, selectedColumns, options.selectLimit);
            fullScanSelectTimes.add(System.nanoTime() - fullScanSelectStart);
        }

        if (indexedCount != fullScanResultCount) {
            throw new IllegalStateException("Indexed count [" + indexedCount + "] did not match full scan count [" + fullScanResultCount + "]");
        }
        if (heapScanResult == null || indexedCount != heapScanResult.count) {
            throw new IllegalStateException("Indexed count [" + indexedCount + "] did not match heap scan count [" + (heapScanResult == null ? 0 : heapScanResult.count) + "]");
        }
        if (indexedSelectRows != fullScanSelectRows) {
            throw new IllegalStateException("Indexed select returned [" + indexedSelectRows + "] rows but full scan returned [" + fullScanSelectRows + "]");
        }

        printResults(options, generationNanos, indexedTimes, fullScanTimes, indexedSelectTimes, fullScanSelectTimes,
                indexedCount, indexedSelectRows, heapScanResult.heapBytesLoaded);
    }

    private static void configureStorage(BenchmarkOptions options) {
        if (options.storageDir == null || options.storageDir.isBlank()) {
            options.storageDir = Path.of(System.getProperty("java.io.tmpdir"), "database-engine-benchmark").toString();
        }

        System.setProperty(STORAGE_PROPERTY, options.storageDir);
    }

    private static int fullScanCount(SimpleQuery query) throws Exception {
        TableSchema tableSchema = query.getTableSchema();
        ColumnLocation columnLocation = getColumnLocation(tableSchema, query.getColumnName());

        int matches = 0;
        int shardSuffix = 0;
        while (true) {
            File dataShardFile = new File(String.format(Constants.SHARD_LOC, query.getDatabaseName(), tableSchema.getTableName(), shardSuffix));
            if (!dataShardFile.exists()) {
                break;
            }

            try (FileChannel dataChannel = FileChannel.open(dataShardFile.toPath(), StandardOpenOption.READ)) {
                long dataSize = dataChannel.size();
                if (dataSize % columnLocation.bytesPerRow != 0) {
                    throw new IllegalStateException("Shard [" + dataShardFile + "] size is not aligned to row size [" + columnLocation.bytesPerRow + "]");
                }

                MappedByteBuffer dataBuffer = dataChannel.map(FileChannel.MapMode.READ_ONLY, 0, dataSize);
                int rows = Math.toIntExact(dataSize / columnLocation.bytesPerRow);
                for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
                    int rowOffset = rowIndex * columnLocation.bytesPerRow;
                    if (matchesQueryValue(dataBuffer, rowOffset + columnLocation.columnOffset, columnLocation.columnSchema, query.getValue())) {
                        matches++;
                    }
                }
            }

            shardSuffix++;
        }

        return matches;
    }

    private static ScanResult heapLoadedFullScanCount(SimpleQuery query) throws Exception {
        TableSchema tableSchema = query.getTableSchema();
        ColumnLocation columnLocation = getColumnLocation(tableSchema, query.getColumnName());

        int matches = 0;
        long heapBytesLoaded = 0;
        int shardSuffix = 0;
        while (true) {
            File dataShardFile = new File(String.format(Constants.SHARD_LOC, query.getDatabaseName(), tableSchema.getTableName(), shardSuffix));
            if (!dataShardFile.exists()) {
                break;
            }

            byte[] shardBytes = Files.readAllBytes(dataShardFile.toPath());
            heapBytesLoaded += shardBytes.length;
            if (shardBytes.length % columnLocation.bytesPerRow != 0) {
                throw new IllegalStateException("Shard [" + dataShardFile + "] size is not aligned to row size [" + columnLocation.bytesPerRow + "]");
            }

            int rows = shardBytes.length / columnLocation.bytesPerRow;
            for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
                int rowOffset = rowIndex * columnLocation.bytesPerRow;
                if (matchesQueryValue(shardBytes, rowOffset + columnLocation.columnOffset, columnLocation.columnSchema, query.getValue())) {
                    matches++;
                }
            }

            shardSuffix++;
        }

        return new ScanResult(matches, heapBytesLoaded);
    }

    private static int fullScanSelect(SimpleQuery query, List<String> selectedColumnNames, int limit) throws Exception {
        if (limit == 0) {
            return 0;
        }

        TableSchema tableSchema = query.getTableSchema();
        ColumnLocation whereColumn = getColumnLocation(tableSchema, query.getColumnName());
        List<ColumnReadPlan> selectedColumns = buildColumnReadPlans(tableSchema, selectedColumnNames);

        int matches = 0;
        int shardSuffix = 0;
        while (true) {
            File dataShardFile = new File(String.format(Constants.SHARD_LOC, query.getDatabaseName(), tableSchema.getTableName(), shardSuffix));
            if (!dataShardFile.exists()) {
                break;
            }

            try (FileChannel dataChannel = FileChannel.open(dataShardFile.toPath(), StandardOpenOption.READ)) {
                long dataSize = dataChannel.size();
                if (dataSize % whereColumn.bytesPerRow != 0) {
                    throw new IllegalStateException("Shard [" + dataShardFile + "] size is not aligned to row size [" + whereColumn.bytesPerRow + "]");
                }

                MappedByteBuffer dataBuffer = dataChannel.map(FileChannel.MapMode.READ_ONLY, 0, dataSize);
                int rows = Math.toIntExact(dataSize / whereColumn.bytesPerRow);
                for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
                    int rowOffset = rowIndex * whereColumn.bytesPerRow;
                    if (matchesQueryValue(dataBuffer, rowOffset + whereColumn.columnOffset, whereColumn.columnSchema, query.getValue())) {
                        readSelectedColumns(dataBuffer, rowOffset, selectedColumns);
                        matches++;
                        if (limit > 0 && matches >= limit) {
                            return matches;
                        }
                    }
                }
            }

            shardSuffix++;
        }

        return matches;
    }

    private static ColumnLocation getColumnLocation(TableSchema tableSchema, String columnName) {
        int bytesPerRow = 0;
        int columnOffset = 0;
        ColumnSchema matchedColumn = null;

        for (ColumnSchema columnSchema : tableSchema.getColumns()) {
            if (columnSchema.getColumnName().equals(columnName)) {
                matchedColumn = columnSchema;
                columnOffset = bytesPerRow;
            }

            bytesPerRow += columnSchema.getNumBytes();
        }

        if (matchedColumn == null) {
            throw new IllegalArgumentException("Column [" + columnName + "] does not exist in table [" + tableSchema.getTableName() + "]");
        }

        return new ColumnLocation(matchedColumn, columnOffset, bytesPerRow);
    }

    private static boolean matchesQueryValue(MappedByteBuffer dataBuffer, int offset, ColumnSchema columnSchema, String queryValue) {
        if (columnSchema.getColumnType() == ColumnSchema.COLUMN_TYPES.INT_TYPE) {
            return dataBuffer.getInt(offset) == Integer.parseInt(queryValue);
        }

        byte[] valueBytes = new byte[columnSchema.getNumBytes()];
        for (int i = 0; i < valueBytes.length; i++) {
            valueBytes[i] = dataBuffer.get(offset + i);
        }

        int length = 0;
        while (length < valueBytes.length && valueBytes[length] != 0) {
            length++;
        }

        return new String(valueBytes, 0, length, StandardCharsets.UTF_8).equals(queryValue);
    }

    private static Object[] readSelectedColumns(MappedByteBuffer dataBuffer, int rowOffset, List<ColumnReadPlan> selectedColumns) {
        Object[] row = new Object[selectedColumns.size()];
        for (int i = 0; i < selectedColumns.size(); i++) {
            ColumnReadPlan column = selectedColumns.get(i);
            int valueOffset = rowOffset + column.getOffset();

            if (column.getColumnType() == ColumnSchema.COLUMN_TYPES.INT_TYPE) {
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

    private static boolean matchesQueryValue(byte[] shardBytes, int offset, ColumnSchema columnSchema, String queryValue) {
        if (columnSchema.getColumnType() == ColumnSchema.COLUMN_TYPES.INT_TYPE) {
            int value = ((shardBytes[offset] & 0xff) << 24)
                    | ((shardBytes[offset + 1] & 0xff) << 16)
                    | ((shardBytes[offset + 2] & 0xff) << 8)
                    | (shardBytes[offset + 3] & 0xff);
            return value == Integer.parseInt(queryValue);
        }

        int length = 0;
        while (length < columnSchema.getNumBytes() && shardBytes[offset + length] != 0) {
            length++;
        }

        return new String(shardBytes, offset, length, StandardCharsets.UTF_8).equals(queryValue);
    }

    private static List<String> parseSelectedColumns(String rawColumns) {
        if (rawColumns.trim().equals("*")) {
            return List.of("*");
        }

        List<String> columns = new ArrayList<>();
        for (String column : rawColumns.split(",")) {
            columns.add(column.trim());
        }
        return columns;
    }

    private static List<ColumnReadPlan> buildColumnReadPlans(TableSchema tableSchema, List<String> selectedColumnNames) {
        List<ColumnReadPlan> allColumns = new ArrayList<>();
        int offset = 0;
        for (ColumnSchema columnSchema : tableSchema.getColumns()) {
            allColumns.add(new ColumnReadPlan(columnSchema.getColumnName(), columnSchema.getColumnType(), offset, columnSchema.getNumBytes()));
            offset += columnSchema.getNumBytes();
        }

        if (selectedColumnNames.size() == 1 && selectedColumnNames.getFirst().equals("*")) {
            return allColumns;
        }

        List<ColumnReadPlan> selectedColumns = new ArrayList<>();
        for (String selectedColumnName : selectedColumnNames) {
            for (ColumnReadPlan columnReadPlan : allColumns) {
                if (columnReadPlan.getColumnName().equals(selectedColumnName)) {
                    selectedColumns.add(columnReadPlan);
                    break;
                }
            }
        }
        return selectedColumns;
    }

    private static void printResults(BenchmarkOptions options, long generationNanos, List<Long> indexedTimes, List<Long> fullScanTimes,
                                     List<Long> indexedSelectTimes, List<Long> fullScanSelectTimes, int count, int selectRows,
                                     long heapBytesLoadedByScan) {
        long rows = (long) options.shards * options.rowsPerShard;
        long indexedMedian = median(indexedTimes);
        long fullScanMedian = median(fullScanTimes);
        long indexedSelectMedian = median(indexedSelectTimes);
        long fullScanSelectMedian = median(fullScanSelectTimes);
        double speedup = (double) fullScanMedian / indexedMedian;
        double selectSpeedup = (double) fullScanSelectMedian / indexedSelectMedian;
        double scaleFactor = (double) options.targetRows / rows;
        long extrapolatedHeapBytesAvoided = (long) (heapBytesLoadedByScan * scaleFactor);

        System.out.println("Benchmark dataset");
        System.out.println("  storage: " + Constants.STORAGE_LOC);
        System.out.println("  table: " + options.databaseName + "." + options.tableName);
        System.out.println("  measured rows: " + rows + " across " + options.shards + " shards");
        System.out.println("  target rows for extrapolation: " + options.targetRows);
        System.out.println("  query: select count(*) from " + options.tableName + " where " + options.columnName + " = " + options.columnValue);
        System.out.println("  matched rows: " + count);
        System.out.println();
        System.out.println("Measured median over " + options.measuredRuns + " runs");
        System.out.println("  indexed count lookup: " + formatMillis(indexedMedian));
        System.out.println("  count full scan:      " + formatMillis(fullScanMedian));
        System.out.println("  count speedup:        " + String.format(Locale.US, "%.2fx", speedup));
        System.out.println();
        System.out.println("Measured select query");
        System.out.println("  query: select " + options.selectColumns + " from " + options.tableName + " where " + options.columnName + " = " + options.columnValue + " limit " + options.selectLimit);
        System.out.println("  returned rows: " + selectRows);
        System.out.println("  indexed select: " + formatMillis(indexedSelectMedian));
        System.out.println("  select full scan: " + formatMillis(fullScanSelectMedian));
        System.out.println("  select speedup: " + String.format(Locale.US, "%.2fx", selectSpeedup));
        System.out.println();
        System.out.println("On-heap data-file bytes avoided by indexed mmap count");
        System.out.println("  measured dataset: " + formatBytes(heapBytesLoadedByScan));
        System.out.println("  extrapolated:     " + formatBytes(extrapolatedHeapBytesAvoided));
        System.out.println();
        System.out.println("Linear extrapolation to " + options.targetRows + " rows");
        System.out.println("  indexed lookup: " + formatMillis((long) (indexedMedian * scaleFactor)));
        System.out.println("  full scan:      " + formatMillis((long) (fullScanMedian * scaleFactor)));
        System.out.println();
        System.out.println("Generation time, not included in query timings: " + formatMillis(generationNanos));
        System.out.println("Extrapolation assumes similar shard count ratio, value distribution, filesystem cache behavior, and hardware.");
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.US, "%.3f ms", nanos / 1_000_000.0);
    }

    private static String formatBytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }

        return String.format(Locale.US, "%.2f %s", value, units[unit]);
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    private record ColumnLocation(ColumnSchema columnSchema, int columnOffset, int bytesPerRow) {
    }

    private record ScanResult(int count, long heapBytesLoaded) {
    }

    private static class BenchmarkOptions {
        private String storageDir;
        private String databaseName = "benchmark_db";
        private String tableName = "city_benchmark";
        private String columnName = "state";
        private String columnValue = "CA";
        private int shards = 10;
        private int rowsPerShard = 10_000;
        private int warmupRuns = 2;
        private int measuredRuns = 5;
        private long targetRows = 10_000_000;
        private String selectColumns = "city,state";
        private int selectLimit = 100;
        private long seed = 42L;
        private boolean cleanBeforeRun = true;

        private static BenchmarkOptions parse(String[] args) {
            BenchmarkOptions options = new BenchmarkOptions();
            Map<String, String> parsedArgs = new HashMap<>();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }

                String key = arg.substring(2);
                if ("keep-existing".equals(key)) {
                    options.cleanBeforeRun = false;
                    continue;
                }

                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for argument: " + arg);
                }

                parsedArgs.put(key, args[++i]);
            }

            options.storageDir = parsedArgs.getOrDefault("storage", options.storageDir);
            options.databaseName = parsedArgs.getOrDefault("database", options.databaseName);
            options.tableName = parsedArgs.getOrDefault("table", options.tableName);
            options.columnName = parsedArgs.getOrDefault("column", options.columnName);
            options.columnValue = parsedArgs.getOrDefault("value", options.columnValue);
            options.shards = parseInt(parsedArgs, "shards", options.shards);
            options.rowsPerShard = parseInt(parsedArgs, "rows-per-shard", options.rowsPerShard);
            options.warmupRuns = parseInt(parsedArgs, "warmups", options.warmupRuns);
            options.measuredRuns = parseInt(parsedArgs, "runs", options.measuredRuns);
            options.targetRows = parseLong(parsedArgs, "target-rows", options.targetRows);
            options.selectColumns = parsedArgs.getOrDefault("select-columns", options.selectColumns);
            options.selectLimit = parseInt(parsedArgs, "select-limit", options.selectLimit);
            options.seed = parseLong(parsedArgs, "seed", options.seed);
            options.validate();
            return options;
        }

        private static int parseInt(Map<String, String> args, String key, int fallback) {
            return args.containsKey(key) ? Integer.parseInt(args.get(key)) : fallback;
        }

        private static long parseLong(Map<String, String> args, String key, long fallback) {
            return args.containsKey(key) ? Long.parseLong(args.get(key)) : fallback;
        }

        private void validate() {
            if (shards <= 0 || rowsPerShard <= 0 || warmupRuns < 0 || measuredRuns <= 0 || targetRows <= 0 || selectLimit < 0) {
                throw new IllegalArgumentException("shards, rows-per-shard, runs, and target-rows must be positive; warmups and select-limit cannot be negative");
            }
        }
    }
}
