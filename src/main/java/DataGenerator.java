import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import model.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DataGenerator {
    private static final Object[][] CITIES = {
            {"New York", "NY", 41, -74}, {"Los Angeles", "CA", 34, -118}, {"Chicago", "IL", 42, -88},
            {"Houston", "TX", 30, -95}, {"Phoenix", "AZ", 33, -112}, {"Philadelphia", "PA", 40, -75},
            {"San Antonio", "TX", 29, -98}, {"San Diego", "CA", 33, -117}, {"Dallas", "TX", 33, -97},
            {"San Jose", "CA", 37, -122}, {"Austin", "TX", 30, -98}, {"Jacksonville", "FL", 30, -82},
            {"Fort Worth", "TX", 33, -97}, {"Columbus", "OH", 40, -83}, {"Charlotte", "NC", 35, -81},
            {"San Francisco", "CA", 38, -122}, {"Indianapolis", "IN", 40, -86}, {"Seattle", "WA", 48, -122},
            {"Denver", "CO", 40, -105}, {"Washington", "DC", 39, -77}, {"Boston", "MA", 42, -71},
            {"El Paso", "TX", 32, -106}, {"Nashville", "TN", 36, -87}, {"Detroit", "MI", 42, -83},
            {"Oklahoma City", "OK", 35, -98}, {"Portland", "OR", 46, -123}, {"Las Vegas", "NV", 36, -115},
            {"Memphis", "TN", 35, -90}, {"Louisville", "KY", 38, -86}, {"Baltimore", "MD", 39, -77},
            {"Milwaukee", "WI", 43, -88}, {"Albuquerque", "NM", 35, -107}, {"Tucson", "AZ", 32, -111},
            {"Fresno", "CA", 37, -120}, {"Mesa", "AZ", 33, -112}, {"Sacramento", "CA", 39, -121},
            {"Atlanta", "GA", 34, -84}, {"Kansas City", "MO", 39, -95}, {"Colorado Springs", "CO", 39, -105},
            {"Miami", "FL", 26, -80}, {"Raleigh", "NC", 36, -79}, {"Omaha", "NE", 41, -96},
            {"Long Beach", "CA", 34, -118}, {"Virginia Beach", "VA", 37, -76}, {"Oakland", "CA", 38, -122},
            {"Minneapolis", "MN", 45, -93}, {"Tulsa", "OK", 36, -96}, {"Arlington", "TX", 33, -97},
            {"New Orleans", "LA", 30, -90}, {"Wichita", "KS", 38, -97}, {"Cleveland", "OH", 41, -82},
            {"Tampa", "FL", 28, -82}, {"Bakersfield", "CA", 35, -119}, {"Aurora", "CO", 40, -105},
            {"Honolulu", "HI", 21, -158}, {"Anaheim", "CA", 34, -118}, {"Lexington", "KY", 38, -84},
            {"Stockton", "CA", 38, -121}, {"Corpus Christi", "TX", 28, -97}, {"Henderson", "NV", 36, -115},
            {"Riverside", "CA", 34, -117}, {"Saint Paul", "MN", 45, -93}, {"St. Louis", "MO", 39, -90},
            {"Cincinnati", "OH", 39, -84}, {"Pittsburgh", "PA", 40, -80}, {"Greensboro", "NC", 36, -80},
            {"Anchorage", "AK", 61, -150}, {"Plano", "TX", 33, -97}, {"Lincoln", "NE", 41, -97},
            {"Orlando", "FL", 28, -81}, {"Irvine", "CA", 34, -118}, {"Newark", "NJ", 41, -74},
            {"Toledo", "OH", 42, -83}, {"Durham", "NC", 36, -79}, {"Chula Vista", "CA", 33, -117},
            {"Fort Wayne", "IN", 41, -85}, {"Jersey City", "NJ", 41, -74}, {"St. Petersburg", "FL", 28, -83},
            {"Laredo", "TX", 27, -99}, {"Madison", "WI", 43, -89}, {"Chandler", "AZ", 33, -112},
            {"Buffalo", "NY", 43, -79}, {"Lubbock", "TX", 34, -102}, {"Scottsdale", "AZ", 34, -112},
            {"Reno", "NV", 40, -120}, {"Glendale", "AZ", 34, -112}, {"Gilbert", "AZ", 33, -112},
            {"Winston–Salem", "NC", 36, -80}, {"North Las Vegas", "NV", 36, -115}, {"Norfolk", "VA", 37, -76},
            {"Chesapeake", "VA", 37, -76}, {"Garland", "TX", 33, -96}, {"Irving", "TX", 33, -97},
            {"Hialeah", "FL", 26, -80}, {"Fremont", "CA", 37, -122}, {"Boise", "ID", 44, -116},
            {"Richmond", "VA", 37, -77}, {"Baton Rouge", "LA", 30, -91}, {"Spokane", "WA", 48, -117},
            {"Des Moines", "IA", 42, -94}
    };

    public static void main(String[] args) {
        try {
            generateCityData("city_data", "cities", 10, 1000, System.nanoTime());
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    public static TableSchema generateCityData(String dbName, String tableName, int numShards, int rowsPerShard, long seed) throws Exception {
        TableSchema tableSchema = createCityTableSchema(tableName);
        generateSchemaFile(tableSchema, dbName);
        generateShardAndIndexFiles(tableSchema, dbName, numShards, rowsPerShard, seed);
        return tableSchema;
    }

    public static TableSchema createCityTableSchema(String tableName) {
        ColumnSchema city = new StringColumnSchema("city", 20, true);
        ColumnSchema state = new StringColumnSchema("state", 2, true);
        ColumnSchema latitude = new IntColumnSchema("latitude", true);
        ColumnSchema longitude = new IntColumnSchema("longitude", false);

        List<ColumnSchema> columns = new ArrayList<>(List.of(city, state, latitude, longitude));

        return new TableSchema(columns, tableName);
    }

    public static void generateShardAndIndexFiles(TableSchema tableSchema, String dbName, int numShards, int rowsPerShard, long seed) throws Exception {
        generateShardAndIndexFiles(tableSchema, dbName, numShards, rowsPerShard, new Random(seed));
    }

    private static void generateShardAndIndexFiles(TableSchema tableSchema, String dbName, int numShards, int rowsPerShard, Random random) throws Exception {
        for (int shardSuffix = 0; shardSuffix < numShards; shardSuffix++) {
            String shardPath = String.format(Constants.SHARD_LOC, dbName, tableSchema.getTableName(), shardSuffix);
            File shard = new File(shardPath);
            ensureParentDirectoryExists(shard);

            Map<ColumnSchema, Map<Object, List<Integer>>> columnIndexMap = new HashMap<>();

            try (FileOutputStream shardStream = new FileOutputStream(shard)) {
                for (int rowIndex = 0; rowIndex < rowsPerShard; rowIndex++) {
                    Object[] randomCity = CITIES[random.nextInt(CITIES.length)];

                    for (int colIndex = 0; colIndex < tableSchema.getColumns().size(); colIndex++) {
                        ColumnSchema colSchema = tableSchema.getColumns().get(colIndex);
                        Object val;
                        if (colSchema.getColumnType() == ColumnSchema.COLUMN_TYPES.STRING_TYPE) {
                            val = String.valueOf(randomCity[colIndex]);
                            shardStream.write(toFixedStringBytes((String) val, colSchema.getNumBytes()));
                        } else if (colSchema.getColumnType() == ColumnSchema.COLUMN_TYPES.INT_TYPE) {
                            val = randomCity[colIndex];
                            shardStream.write(ByteBuffer.allocate(colSchema.getNumBytes()).putInt((Integer) val).array());
                        } else {
                            throw new Exception("Unknown column type");
                        }

                        if (colSchema.isIndexed()) {
                            columnIndexMap.computeIfAbsent(colSchema, ignored -> new HashMap<>())
                                    .computeIfAbsent(val, ignored -> new ArrayList<>())
                                    .add(rowIndex);
                        }
                    }
                }
            }

            for (ColumnSchema colSchema : columnIndexMap.keySet()) {
                String shardColIndexPath = String.format(Constants.INDEX_FILE_LOC, dbName, tableSchema.getTableName(),
                        shardSuffix, colSchema.getColumnName());
                File shardColIndex = new File(shardColIndexPath);
                ensureParentDirectoryExists(shardColIndex);

                try (FileOutputStream shardColIndexStream = new FileOutputStream(shardColIndex)) {
                    for (Object val : columnIndexMap.get(colSchema).keySet()) {
                        if (colSchema.getColumnType() == ColumnSchema.COLUMN_TYPES.STRING_TYPE) {
                            shardColIndexStream.write(toFixedStringBytes((String) val, colSchema.getNumBytes()));
                        } else if (colSchema.getColumnType() == ColumnSchema.COLUMN_TYPES.INT_TYPE) {
                            shardColIndexStream.write(ByteBuffer.allocate(colSchema.getNumBytes()).putInt((Integer) val).array());
                        } else {
                            throw new Exception("Unknown column type");
                        }

                        List<Integer> indexes = columnIndexMap.get(colSchema).get(val);

                        shardColIndexStream.write(ByteBuffer.allocate(4).putInt(indexes.size()).array());

                        for (Integer index : indexes) {
                            shardColIndexStream.write(ByteBuffer.allocate(4).putInt(index).array());
                        }
                    }
                }
            }
        }
    }

    public static void generateSchemaFile(TableSchema schema, String dbName) throws IOException {
        String schemaFilePath = String.format(Constants.SCHEMA_FILE_LOC, dbName, schema.getTableName());
        File schemaFile = new File(schemaFilePath);
        ensureParentDirectoryExists(schemaFile);

        try (FileOutputStream fos = new FileOutputStream(schemaFile)) {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(ColumnSchema.class, new ColumnSchemaTypeAdapter())
                    .create();
            String schemaJson = gson.toJson(schema);
            fos.write(schemaJson.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] toFixedStringBytes(String value, int numBytes) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > numBytes) {
            throw new IllegalArgumentException("Value [" + value + "] requires " + encoded.length + " bytes but column allows " + numBytes);
        }

        return ByteBuffer.allocate(numBytes).put(encoded).array();
    }

    private static void ensureParentDirectoryExists(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent);
        }
    }
}
