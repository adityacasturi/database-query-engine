import model.QueryResult;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DatabaseApiController {
    @GetMapping("/health")
    public ApiMessage health() {
        return new ApiMessage("ok");
    }

    @GetMapping("/databases")
    public List<String> databases() {
        return DatabaseExplorer.getDatabases();
    }

    @GetMapping("/databases/{databaseName}/tables")
    public List<String> tables(@PathVariable("databaseName") String databaseName) {
        return DatabaseExplorer.getTables(databaseName);
    }

    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest request) throws Exception {
        if (request.countOnly()) {
            QueryResult result = QueryHandler.handle(
                    request.databaseName(),
                    request.tableName(),
                    request.whereColumn(),
                    request.whereValue());
            return QueryResponse.from(result);
        }

        List<String> selectedColumns = request.selectedColumns();
        if (selectedColumns == null || selectedColumns.isEmpty()) {
            selectedColumns = List.of("*");
        }

        QueryResult result = QueryHandler.handleSelect(
                request.databaseName(),
                request.tableName(),
                selectedColumns,
                request.whereColumn(),
                request.whereValue(),
                request.limit() == null ? -1 : request.limit());
        return QueryResponse.from(result);
    }

    @PostMapping("/sample-data")
    public ApiMessage generateSampleData(@RequestParam(name = "databaseName", defaultValue = "city_data") String databaseName,
                                         @RequestParam(name = "tableName", defaultValue = "cities") String tableName,
                                         @RequestParam(name = "shards", defaultValue = "10") int shards,
                                         @RequestParam(name = "rowsPerShard", defaultValue = "1000") int rowsPerShard,
                                         @RequestParam(name = "seed", defaultValue = "42") long seed) throws Exception {
        DataGenerator.generateCityData(databaseName, tableName, shards, rowsPerShard, seed);
        long rows = (long) shards * rowsPerShard;
        return new ApiMessage("generated " + rows + " rows in " + databaseName + "." + tableName);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiMessage> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiMessage(e.getMessage()));
    }

    public record QueryRequest(String databaseName, String tableName, List<String> selectedColumns,
                               String whereColumn, String whereValue, Integer limit, boolean countOnly) {
    }

    public record QueryResponse(int rowCount, List<String> columns, List<Object[]> rows) {
        static QueryResponse from(QueryResult result) {
            return new QueryResponse(result.getNumRows(), result.getColumnNames(), result.getRows());
        }
    }

    public record ApiMessage(String message) {
    }
}
