import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import model.ColumnSchema;
import model.ColumnSchemaTypeAdapter;
import model.TableSchema;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class DatabaseExplorer {
    private static final Map<String, CachedSchema> TABLE_SCHEMA_CACHE = new HashMap<>();

    public static List<String> getDatabases() {
        List<String> databases = new ArrayList<>();
        File dbsFolder = new File(Constants.STORAGE_LOC);
        if (dbsFolder.exists() && dbsFolder.isDirectory()) {
            File[] files = dbsFolder.listFiles();
            if (files == null) {
                return databases;
            }
            for (File file : files) {
                if (file.isDirectory()) {
                    databases.add(file.getName());
                }
            }
        }
        return databases;
    }

    public static List<String> getTables(String dbName) {
        List<String> tables = new ArrayList<>();
        File dbFolder = new File(Constants.STORAGE_LOC + File.separator + dbName);
        if (dbFolder.exists() && dbFolder.isDirectory()) {
            File[] files = dbFolder.listFiles();
            if (files == null) {
                return tables;
            }
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }

                String fileName = file.getName();
                if (fileName.endsWith("_0.data")) {
                    tables.add(fileName.substring(0, fileName.length() - "_0.data".length()));
                }
            }
        }
        return tables;
    }

    public static TableSchema getTableSchema(String dbName, String tableName) throws Exception {
        String schemaFilePath = String.format(Constants.SCHEMA_FILE_LOC, dbName, tableName);
        File schemaFile = new File(schemaFilePath);

        if (!schemaFile.exists()) {
            throw new Exception("Schema file does not exist");
        }

        Path schemaPath = schemaFile.toPath();
        long lastModified = schemaFile.lastModified();
        long fileSize = schemaFile.length();
        String cacheKey = schemaPath.toAbsolutePath().normalize().toString();
        CachedSchema cachedSchema = TABLE_SCHEMA_CACHE.get(cacheKey);
        if (cachedSchema != null && cachedSchema.lastModified == lastModified && cachedSchema.fileSize == fileSize) {
            return cachedSchema.tableSchema;
        }

        String schemaFileJson = Files.readString(schemaPath);
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(ColumnSchema.class, new ColumnSchemaTypeAdapter())
                .create();

        TableSchema tableSchema = gson.fromJson(schemaFileJson, TableSchema.class);
        TABLE_SCHEMA_CACHE.put(cacheKey, new CachedSchema(tableSchema, lastModified, fileSize));
        return tableSchema;
    }

    private record CachedSchema(TableSchema tableSchema, long lastModified, long fileSize) {
    }
}
