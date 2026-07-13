import model.ColumnSchema;
import model.ColumnSchema.COLUMN_TYPES;
import model.SimpleQuery;
import model.TableSchema;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class QueryExecutor {
    private static final int WORKER_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(WORKER_COUNT, new QueryThreadFactory());

    public static int execute(SimpleQuery query) throws ExecutionException, InterruptedException {
        int numRows;

        QueryExecutionPlan executionPlan = buildExecutionPlan(query, null);
        List<Future<Integer>> futures = new ArrayList<>();
        TableSchema tableSchema = query.getTableSchema();

        int shardSuffix = 0;

        while (true) {
            File dataShardFile = new File(String.format(Constants.SHARD_LOC, query.getDatabaseName(), tableSchema.getTableName(), shardSuffix));
            if (!dataShardFile.exists()) {
                break;
            }

            String colIndexShardFilePath = String.format(Constants.INDEX_FILE_LOC, query.getDatabaseName(),
                    query.getTableSchema().getTableName(), shardSuffix, query.getColumnName());

            futures.add(EXECUTOR.submit(new CountTask(executionPlan.queryColumnType, query, new File(colIndexShardFilePath), dataShardFile,
                    executionPlan.queryColumnBytes, executionPlan.bytesPerRow)));
            shardSuffix++;
        }

        numRows = 0;
        for (Future<Integer> future : futures) {
            numRows += future.get();
        }

        return numRows;
    }

    public static List<Object[]> executeSelect(SimpleQuery query, List<String> selectedColumnNames, int limit)
            throws ExecutionException, InterruptedException {
        if (limit == 0) {
            return List.of();
        }

        QueryExecutionPlan executionPlan = buildExecutionPlan(query, selectedColumnNames);
        List<Future<List<Object[]>>> futures = new ArrayList<>();
        TableSchema tableSchema = query.getTableSchema();

        int shardSuffix = 0;
        while (true) {
            File dataShardFile = new File(String.format(Constants.SHARD_LOC, query.getDatabaseName(), tableSchema.getTableName(), shardSuffix));
            if (!dataShardFile.exists()) {
                break;
            }

            String colIndexShardFilePath = String.format(Constants.INDEX_FILE_LOC, query.getDatabaseName(),
                    query.getTableSchema().getTableName(), shardSuffix, query.getColumnName());

            futures.add(EXECUTOR.submit(new SelectTask(executionPlan.queryColumnType, query, new File(colIndexShardFilePath), dataShardFile,
                    executionPlan.queryColumnBytes, executionPlan.bytesPerRow, executionPlan.selectedColumns, limit)));
            shardSuffix++;
        }

        List<Object[]> rows = new ArrayList<>();
        for (Future<List<Object[]>> future : futures) {
            for (Object[] row : future.get()) {
                if (limit >= 0 && rows.size() >= limit) {
                    return rows;
                }
                rows.add(row);
            }
        }

        return rows;
    }

    public static List<String> expandSelectedColumns(TableSchema tableSchema, List<String> selectedColumnNames) {
        return buildExecutionPlan(new SimpleQuery("", tableSchema, tableSchema.getColumns().getFirst().getColumnName(), ""), selectedColumnNames)
                .selectedColumns
                .stream()
                .map(ColumnReadPlan::getColumnName)
                .toList();
    }

    private static QueryExecutionPlan buildExecutionPlan(SimpleQuery query, List<String> selectedColumnNames) {
        TableSchema tableSchema = query.getTableSchema();
        int queryColumnBytes = 0;
        int bytesPerRow = 0;
        COLUMN_TYPES queryColumnType = null;
        Map<String, ColumnReadPlan> allColumns = new LinkedHashMap<>();

        for (ColumnSchema colSchema : tableSchema.getColumns()) {
            if (colSchema.getColumnName().equals(query.getColumnName())) {
                queryColumnType = colSchema.getColumnType();
                queryColumnBytes = colSchema.getNumBytes();
            }

            allColumns.put(colSchema.getColumnName(), new ColumnReadPlan(
                    colSchema.getColumnName(),
                    colSchema.getColumnType(),
                    bytesPerRow,
                    colSchema.getNumBytes()));
            bytesPerRow += colSchema.getNumBytes();
        }

        List<ColumnReadPlan> selectedColumns = new ArrayList<>();
        if (selectedColumnNames == null || selectedColumnNames.size() == 1 && selectedColumnNames.getFirst().equals("*")) {
            selectedColumns.addAll(allColumns.values());
        } else {
            for (String selectedColumnName : selectedColumnNames) {
                selectedColumns.add(allColumns.get(selectedColumnName));
            }
        }

        return new QueryExecutionPlan(queryColumnType, queryColumnBytes, bytesPerRow, selectedColumns);
    }

    private record QueryExecutionPlan(COLUMN_TYPES queryColumnType, int queryColumnBytes, int bytesPerRow,
                                      List<ColumnReadPlan> selectedColumns) {
    }

    private static class QueryThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "query-worker-" + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
