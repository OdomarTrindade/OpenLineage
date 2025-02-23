/*
/* Copyright 2018-2024 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark.agent.util;

import io.openlineage.client.utils.DatasetIdentifier;
import io.openlineage.client.utils.filesystem.FilesystemDatasetUtils;
import java.net.URI;
import java.util.Optional;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.TableIdentifier;
import org.apache.spark.sql.catalyst.catalog.CatalogTable;
import org.apache.spark.sql.internal.StaticSQLConf;

@Slf4j
@SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
public class PathUtils {
  private static final String DEFAULT_DB = "default";

  public static DatasetIdentifier fromPath(Path path) {
    return fromURI(path.toUri());
  }

  public static DatasetIdentifier fromURI(URI location) {
    return FilesystemDatasetUtils.fromLocation(location);
  }

  /**
   * Create DatasetIdentifier from CatalogTable, using storage's locationURI if it exists. In other
   * way, use defaultTablePath.
   */
  @SneakyThrows
  public static DatasetIdentifier fromCatalogTable(
      CatalogTable catalogTable, SparkSession sparkSession) {
    String tableName = nameFromTableIdentifier(catalogTable.identifier());

    URI locationUri;
    if (catalogTable.storage() != null && catalogTable.storage().locationUri().isDefined()) {
      locationUri = catalogTable.storage().locationUri().get();
    } else {
      locationUri = getDefaultLocationUri(sparkSession, catalogTable.identifier());
    }
    DatasetIdentifier locationDataset = fromURI(locationUri);
    // perform normalization
    locationUri = FilesystemDatasetUtils.toLocation(locationDataset);

    Optional<DatasetIdentifier> symlinkDataset = Optional.empty();

    SparkContext sparkContext = sparkSession.sparkContext();
    SparkConf sparkConf = sparkContext.getConf();
    Configuration hadoopConf = sparkContext.hadoopConfiguration();
    Optional<URI> metastoreUri = getMetastoreUri(sparkContext);
    if (metastoreUri.isPresent()) {
      // dealing with Hive tables
      URI hiveUri = prepareHiveUri(metastoreUri.get());
      symlinkDataset = Optional.of(FilesystemDatasetUtils.fromLocationAndName(hiveUri, tableName));
    } else if (catalogTable.provider().isDefined()
        && "hive".equals(catalogTable.provider().get())
        && "com.amazonaws.glue.catalog.metastore.AWSGlueDataCatalogHiveClientFactory"
            .equals(
                SparkConfUtils.findHadoopConfigKey(
                        hadoopConf, "hive.metastore.client.factory.class")
                    .orElse(""))) {
      symlinkDataset =
          getGlueUri(sparkConf)
              .map(uri -> FilesystemDatasetUtils.fromLocationAndName(uri, tableName));
    } else {
      Optional<URI> warehouseLocation =
          getWarehouseLocation(sparkConf, hadoopConf)
              // perform normalization
              .map(FilesystemDatasetUtils::fromLocation)
              .map(FilesystemDatasetUtils::toLocation);

      if (warehouseLocation.isPresent()) {
        URI relativePath = warehouseLocation.get().relativize(locationUri);
        if (!relativePath.equals(locationUri)) {
          // if there is no metastore, and table has custom location,
          // it cannot be accessed via default warehouse location
          symlinkDataset =
              Optional.of(
                  FilesystemDatasetUtils.fromLocationAndName(warehouseLocation.get(), tableName));
        }
      }
    }

    if (symlinkDataset.isPresent()) {
      locationDataset.withSymlink(
          symlinkDataset.get().getName(),
          symlinkDataset.get().getNamespace(),
          DatasetIdentifier.SymlinkType.TABLE);
    }

    return locationDataset;
  }

  public static URI getDefaultLocationUri(SparkSession sparkSession, TableIdentifier identifier) {
    return sparkSession.sessionState().catalog().defaultTablePath(identifier);
  }

  public static Path reconstructDefaultLocation(String warehouse, String[] namespace, String name) {
    String database = null;
    if (namespace.length == 1) {
      // {"database"}
      database = namespace[0];
    } else if (namespace.length > 1) {
      // {"spark_catalog", "database"}
      database = namespace[1];
    }

    // /warehouse/mytable
    if (database == null || database.equals(DEFAULT_DB)) {
      return new Path(warehouse, name);
    }

    // /warehouse/mydb.db/mytable
    return new Path(warehouse, database + ".db", name);
  }

  @SneakyThrows
  public static URI prepareHiveUri(URI uri) {
    return new URI("hive", uri.getAuthority(), null, null, null);
  }

  @SneakyThrows
  private static Optional<URI> getWarehouseLocation(SparkConf sparkConf, Configuration hadoopConf) {
    Optional<String> warehouseLocation =
        SparkConfUtils.findSparkConfigKey(sparkConf, StaticSQLConf.WAREHOUSE_PATH().key());
    if (!warehouseLocation.isPresent()) {
      warehouseLocation =
          SparkConfUtils.findHadoopConfigKey(hadoopConf, "hive.metastore.warehouse.dir");
    }
    return warehouseLocation.map(URI::create);
  }

  private static Optional<URI> getMetastoreUri(SparkContext context) {
    // make sure enableHiveSupport is called
    Optional<String> setting =
        SparkConfUtils.findSparkConfigKey(
            context.getConf(), StaticSQLConf.CATALOG_IMPLEMENTATION().key());
    if (!setting.isPresent() || !"hive".equals(setting.get())) {
      return Optional.empty();
    }
    return SparkConfUtils.getMetastoreUri(context);
  }

  @SneakyThrows
  private static Optional<URI> getGlueUri(SparkConf sparkConf) {
    Optional<String> region =
        Optional.ofNullable(System.getenv("AWS_DEFAULT_REGION"))
            .filter(s -> !s.isEmpty())
            .map(Optional::of)
            .orElseGet(() -> Optional.ofNullable(System.getenv("AWS_REGION")));

    Optional<String> accountId =
        SparkConfUtils.findSparkConfigKey(sparkConf, "spark.glue.accountId");

    if (!region.isPresent() || !accountId.isPresent()) {
      return Optional.empty();
    }

    return Optional.of(new URI("aws:glue:" + region.get() + ":" + accountId.get()));
  }

  /** Get DatasetIdentifier name in format database.table or table */
  private static String nameFromTableIdentifier(TableIdentifier identifier) {
    // calling `unquotedString` method includes `spark_catalog`, so instead get proper identifier
    // manually
    String name;
    if (identifier.database().isDefined()) {
      // include database in name
      name = String.format("%s.%s", identifier.database().get(), identifier.table());
    } else {
      // just table name
      name = identifier.table();
    }

    return name;
  }
}
