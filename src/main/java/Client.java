import model.QueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;

public class Client {
    private static String selectedDatabaseName = null;

    public static void start() {
        System.out.println(
                """
                           _____ _                 __        _____ ____    __       ______            _         \s
                          / ___/(_)___ ___  ____  / /__     / ___// __ \\  / /      / ____/___  ____ _(_)___  ___\s
                          \\__ \\/ / __ `__ \\/ __ \\/ / _ \\    \\__ \\/ / / / / /      / __/ / __ \\/ __ `/ / __ \\/ _ \\
                         ___/ / / / / / / / /_/ / /  __/   ___/ / /_/ / / /___   / /___/ / / / /_/ / / / / /  __/
                        /____/_/_/ /_/ /_/ .___/_/\\___/   /____/\\___\\_\\/_____/  /_____/_/ /_/\\__, /_/_/ /_/\\___/\s
                                        /_/                                                 /____/              \s""");

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("help")) {
                printHelp();
                continue;
            } else if (input.equalsIgnoreCase("exit")) {
                return;
            }

            Matcher showDatabasesMatcher = Constants.SHOW_DATABASES_PATTERN.matcher(input);
            if (showDatabasesMatcher.matches()) {
                printDatabases();
                continue;
            }

            if (handleUseDatabaseCommand(input)) {
                continue;
            }

            if (selectedDatabaseName == null) {
                System.out.println("No database selected.");
                continue;
            }

            if (handleShowTablesCommand(input)) {
                continue;
            }

            if (handleQueryCommand(input)) {
                continue;
            }

            System.out.println("Command not supported.");
        }
    }

    private static boolean handleUseDatabaseCommand(String input) {
        Matcher useDatabaseMatcher = Constants.USE_DATABASE_COMMAND_PATTERN.matcher(input);
        if (!useDatabaseMatcher.matches()) {
            return false;
        }

        String inputDatabaseName = useDatabaseMatcher.group(1);
        if (!DatabaseExplorer.getDatabases().contains(inputDatabaseName)) {
            System.out.println("[" + inputDatabaseName + "] does not exist.");
            selectedDatabaseName = null;
            return false;
        }

        System.out.println("Using database [" + inputDatabaseName + "]");
        selectedDatabaseName = inputDatabaseName;
        return true;
    }

    private static boolean handleShowTablesCommand(String input) {
        Matcher showTablesMatcher = Constants.SHOW_TABLES_PATTERN.matcher(input);
        if (!showTablesMatcher.matches()) {
            return false;
        }

        printTables(selectedDatabaseName);
        return true;
    }

    private static boolean handleQueryCommand(String input) {
        Matcher countQueryMatcher = Constants.COUNT_QUERY_PATTERN.matcher(input);
        if (countQueryMatcher.matches()) {
            String tableName = countQueryMatcher.group(1);
            String columnName = countQueryMatcher.group(2);
            String columnValue = firstNonNull(countQueryMatcher.group(3), countQueryMatcher.group(4), countQueryMatcher.group(5));

            try {
                long startTime = System.nanoTime();
                QueryResult queryResult = QueryHandler.handle(selectedDatabaseName, tableName, columnName, columnValue);
                long endTime = System.nanoTime();

                long durationInMillis = (endTime - startTime) / 1_000_000;

                System.out.println("[" + queryResult.getNumRows() + "] rows found.");
                System.out.println("Query took [" + durationInMillis + "] ms.");

                return true;
            } catch (IllegalArgumentException e) {
                System.out.println("Invalid query: " + e.getMessage());
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }

            return false;
        }

        Matcher selectQueryMatcher = Constants.SELECT_QUERY_PATTERN.matcher(input);
        if (!selectQueryMatcher.matches()) {
            return false;
        }

        List<String> selectedColumns = parseSelectedColumns(selectQueryMatcher.group(1));
        String tableName = selectQueryMatcher.group(2);
        String columnName = selectQueryMatcher.group(3);
        String columnValue = firstNonNull(selectQueryMatcher.group(4), selectQueryMatcher.group(5), selectQueryMatcher.group(6));
        int limit = parseLimit(selectQueryMatcher.group(7));

        try {
            long startTime = System.nanoTime();
            QueryResult queryResult = QueryHandler.handleSelect(selectedDatabaseName, tableName, selectedColumns, columnName, columnValue, limit);
            long endTime = System.nanoTime();

            long durationInMillis = (endTime - startTime) / 1_000_000;

            printQueryResult(queryResult);
            System.out.println("Query took [" + durationInMillis + "] ms.");

            return true;
        } catch (IllegalArgumentException e) {
            System.out.println("Invalid query: " + e.getMessage());
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        return false;
    }

    private static void printDatabases() {
        List<String> databases = DatabaseExplorer.getDatabases();
        if (databases.isEmpty()) {
            System.out.println("No databases found.");
            return;
        }

        for (int i = 0; i < databases.size(); i++) {
            System.out.println((i + 1) + ". " + databases.get(i));
        }
    }

    private static void printTables(String dbName) {
        List<String> tables = DatabaseExplorer.getTables(dbName);
        if (tables.isEmpty()) {
            System.out.println("No tables found in database [" + dbName + "].");
            return;
        }

        for (int i = 0; i < tables.size(); i++) {
            System.out.println((i + 1) + ". " + tables.get(i));
        }
    }

    private static void printHelp() {
        System.out.println("use database {database name}");
        System.out.println("show tables");
        System.out.println("select count(*) from {table name} where {column name} = {value}");
        System.out.println("select count(*) from {table name} where {column name} = \"quoted value\"");
        System.out.println("select * from {table name} where {column name} = {value} limit {n}");
        System.out.println("select {column1},{column2} from {table name} where {column name} = \"quoted value\" limit {n}");
        System.out.println("exit");
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

    private static int parseLimit(String rawLimit) {
        if (rawLimit == null || rawLimit.isBlank()) {
            return -1;
        }

        return Integer.parseInt(rawLimit);
    }

    private static void printQueryResult(QueryResult queryResult) {
        if (!queryResult.hasRows()) {
            System.out.println("[" + queryResult.getNumRows() + "] rows found.");
            return;
        }

        List<String> columnNames = queryResult.getColumnNames();
        List<Object[]> rows = queryResult.getRows();
        int[] widths = new int[columnNames.size()];

        for (int i = 0; i < columnNames.size(); i++) {
            widths[i] = columnNames.get(i).length();
        }

        for (Object[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], String.valueOf(row[i]).length());
            }
        }

        printTableDivider(widths);
        printTableRow(columnNames.toArray(), widths);
        printTableDivider(widths);
        for (Object[] row : rows) {
            printTableRow(row, widths);
        }
        printTableDivider(widths);
        System.out.println("[" + rows.size() + "] rows returned.");
    }

    private static void printTableDivider(int[] widths) {
        StringBuilder divider = new StringBuilder("+");
        for (int width : widths) {
            divider.append("-".repeat(width + 2)).append("+");
        }
        System.out.println(divider);
    }

    private static void printTableRow(Object[] values, int[] widths) {
        StringBuilder row = new StringBuilder("|");
        for (int i = 0; i < values.length; i++) {
            row.append(" ").append(String.format("%-" + widths[i] + "s", values[i])).append(" |");
        }
        System.out.println(row);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }

        return "";
    }
}
