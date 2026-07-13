# DatabaseEngine

A custom file-based database engine written in Java 21. Stores fixed-width binary rows with per-column indexes, and runs equality queries over sharded data using memory-mapped I/O.

## Requirements

- Java 21
- Maven 3.9+

## Run

**REST API** (default, port 8080):

```bash
mvn spring-boot:run
```

**CLI:**

```bash
mvn -q exec:java -Dexec.mainClass=DatabaseEngineApp -Dexec.args="--cli"
```

Or build and run the jar:

```bash
mvn -q -DskipTests package
java -jar target/DatabaseEngine-1.0-SNAPSHOT.jar
java -jar target/DatabaseEngine-1.0-SNAPSHOT.jar --cli
```

Storage defaults to `~/dbs`. Override with `DB_STORAGE_DIR` or `-Ddb.storage.dir=...`.

## Docker

```bash
docker build -t database-engine .
docker run -p 8080:8080 -v db-data:/data/dbs database-engine
```

## API

- `GET /api/health`
- `GET /api/databases`
- `GET /api/databases/{name}/tables`
- `POST /api/query`
- `POST /api/sample-data`

## CLI commands

```text
show databases
use database {name}
show tables
select count(*) from {table} where {column} = {value}
select {cols} from {table} where {column} = {value} limit {n}
```
