import java.io.File;
import java.util.regex.Pattern;

public final class Constants {
    public static final String STORAGE_PROPERTY = "db.storage.dir";
    public static final String STORAGE_ENV = "DB_STORAGE_DIR";
    public static final String STORAGE_LOC = resolveStorageLocation();
    public static final String SCHEMA_FILE_LOC = STORAGE_LOC + File.separator + "%s" + File.separator + "%s.schema";
    public static final String SHARD_LOC = STORAGE_LOC + File.separator + "%s" + File.separator + "%s_%s.data";
    public static final String INDEX_FILE_LOC = STORAGE_LOC + File.separator + "%s" + File.separator + "%s_%s_%s.index";

    public static final Pattern USE_DATABASE_COMMAND_PATTERN = Pattern.compile("^\\s*use\\s+database\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    public static final Pattern SHOW_TABLES_PATTERN = Pattern.compile("^\\s*show\\s+tables\\s*$", Pattern.CASE_INSENSITIVE);
    public static final Pattern SHOW_DATABASES_PATTERN = Pattern.compile("^\\s*show\\s+databases\\s*$", Pattern.CASE_INSENSITIVE);
    public static final Pattern COUNT_QUERY_PATTERN = Pattern.compile("^\\s*select\\s+count\\s*\\(\\s*\\*\\s*\\)\\s+from\\s+(\\S+)\\s+where\\s+(\\S+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|(\\S+))\\s*$", Pattern.CASE_INSENSITIVE);
    public static final Pattern SELECT_QUERY_PATTERN = Pattern.compile("^\\s*select\\s+(.+?)\\s+from\\s+(\\S+)\\s+where\\s+(\\S+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|(\\S+))(?:\\s+limit\\s+(\\d+))?\\s*$", Pattern.CASE_INSENSITIVE);

    private static String resolveStorageLocation() {
        String configuredPath = System.getProperty(STORAGE_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv(STORAGE_ENV);
        }

        if (configuredPath == null || configuredPath.isBlank()) {
            return System.getProperty("user.home") + File.separator + "dbs";
        }

        return configuredPath;
    }
}
