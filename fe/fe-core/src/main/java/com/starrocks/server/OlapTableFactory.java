// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.server;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.analysis.BloomFilterIndexUtil;
import com.starrocks.binlog.BinlogConfig;
import com.starrocks.catalog.ColocateTableIndex;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.ColumnId;
import com.starrocks.catalog.DataProperty;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.DistributionInfo;
import com.starrocks.catalog.ExpressionRangePartitionInfo;
import com.starrocks.catalog.ExternalOlapTable;
import com.starrocks.catalog.FlatJsonConfig;
import com.starrocks.catalog.HashDistributionInfo;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.ListPartitionInfo;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionInfo;
import com.starrocks.catalog.PartitionType;
import com.starrocks.catalog.RangePartitionInfo;
import com.starrocks.catalog.SinglePartitionInfo;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.TableIndexes;
import com.starrocks.catalog.constraint.ForeignKeyConstraint;
import com.starrocks.catalog.constraint.UniqueConstraint;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.FeConstants;
import com.starrocks.common.Pair;
import com.starrocks.common.util.DynamicPartitionUtil;
import com.starrocks.common.util.PropertyAnalyzer;
import com.starrocks.common.util.Util;
import com.starrocks.lake.DataCacheInfo;
import com.starrocks.lake.LakeTable;
import com.starrocks.lake.StorageInfo;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.FeNameFormat;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.AddRollupClause;
import com.starrocks.sql.ast.AlterClause;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.CreateTemporaryTableStmt;
import com.starrocks.sql.ast.DistributionDesc;
import com.starrocks.sql.ast.ExpressionPartitionDesc;
import com.starrocks.sql.ast.IndexDef.IndexType;
import com.starrocks.sql.ast.KeysDesc;
import com.starrocks.sql.ast.ListPartitionDesc;
import com.starrocks.sql.ast.PartitionDesc;
import com.starrocks.sql.ast.RangePartitionDesc;
import com.starrocks.sql.ast.SingleRangePartitionDesc;
import com.starrocks.sql.common.MetaUtils;
import com.starrocks.thrift.TCompactionStrategy;
import com.starrocks.thrift.TCompressionType;
import com.starrocks.thrift.TPersistentIndexType;
import com.starrocks.thrift.TStorageType;
import com.starrocks.thrift.TTabletType;
import com.starrocks.warehouse.cngroup.ComputeResource;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.threeten.extra.PeriodDuration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.validation.constraints.NotNull;

public class OlapTableFactory implements AbstractTableFactory {

    private static final Logger LOG = LogManager.getLogger(OlapTableFactory.class);
    public static final OlapTableFactory INSTANCE = new OlapTableFactory();

    private OlapTableFactory() {
    }

    @Override
    @NotNull
    public Table createTable(LocalMetastore metastore, Database db, CreateTableStmt stmt) throws DdlException {
        GlobalStateMgr stateMgr = metastore.getStateMgr();
        ColocateTableIndex colocateTableIndex = metastore.getColocateTableIndex();
        String tableName = stmt.getTableName();

        if (stmt instanceof CreateTemporaryTableStmt) {
            LOG.debug("begin create temp table {}.{} in session {}",
                    db.getFullName(), tableName, ((CreateTemporaryTableStmt) stmt).getSessionId());
        } else {
            LOG.debug("begin create olap table: {}", tableName);
        }

        // create columns
        List<Column> baseSchema = stmt.getColumns();
        metastore.validateColumns(baseSchema);

        // create partition info
        PartitionDesc partitionDesc = stmt.getPartitionDesc();
        PartitionInfo partitionInfo;
        Map<String, Long> partitionNameToId = Maps.newHashMap();
        if (partitionDesc != null) {
            // gen partition id first
            if (partitionDesc instanceof RangePartitionDesc) {
                RangePartitionDesc rangePartitionDesc = (RangePartitionDesc) partitionDesc;
                for (SingleRangePartitionDesc desc : rangePartitionDesc.getSingleRangePartitionDescs()) {
                    long partitionId = metastore.getNextId();
                    partitionNameToId.put(desc.getPartitionName(), partitionId);
                }
            } else if (partitionDesc instanceof ListPartitionDesc) {
                ListPartitionDesc listPartitionDesc = (ListPartitionDesc) partitionDesc;
                listPartitionDesc.findAllPartitionNames()
                        .forEach(partitionName -> partitionNameToId.put(partitionName, metastore.getNextId()));
            } else if (partitionDesc instanceof ExpressionPartitionDesc) {
                ExpressionPartitionDesc expressionPartitionDesc = (ExpressionPartitionDesc) partitionDesc;
                for (SingleRangePartitionDesc desc : expressionPartitionDesc.getRangePartitionDesc()
                        .getSingleRangePartitionDescs()) {
                    long partitionId = metastore.getNextId();
                    partitionNameToId.put(desc.getPartitionName(), partitionId);
                }

                DynamicPartitionUtil.checkIfExpressionPartitionAllowed(stmt.getProperties(),
                        expressionPartitionDesc.getExpr());

            } else {
                throw new DdlException("Currently only support range or list partition with engine type olap");
            }
            partitionInfo = partitionDesc.toPartitionInfo(baseSchema, partitionNameToId, false);

            // Automatic partitioning needs to ensure that at least one tablet is opened.
            if (partitionInfo.isAutomaticPartition()) {
                long partitionId = metastore.getNextId();
                String replicateNum = String.valueOf(RunMode.defaultReplicationNum());
                if (stmt.getProperties() != null) {
                    replicateNum = stmt.getProperties().getOrDefault("replication_num",
                            String.valueOf(RunMode.defaultReplicationNum()));
                }
                partitionInfo.createAutomaticShadowPartition(baseSchema, partitionId, replicateNum);
                partitionNameToId.put(ExpressionRangePartitionInfo.AUTOMATIC_SHADOW_PARTITION_NAME, partitionId);
            }

        } else {
            if (DynamicPartitionUtil.checkDynamicPartitionPropertiesExist(stmt.getProperties())) {
                throw new DdlException("Only support dynamic partition properties on range partition table");
            }
            long partitionId = metastore.getNextId();
            // use table name as single partition name
            partitionNameToId.put(tableName, partitionId);
            partitionInfo = new SinglePartitionInfo();
        }

        // get keys type
        KeysDesc keysDesc = stmt.getKeysDesc();
        Preconditions.checkNotNull(keysDesc);
        KeysType keysType = keysDesc.getKeysType();

        // create distribution info
        DistributionDesc distributionDesc = stmt.getDistributionDesc();
        Preconditions.checkNotNull(distributionDesc);
        DistributionInfo distributionInfo = distributionDesc.toDistributionInfo(baseSchema);

        short shortKeyColumnCount = 0;
        List<Integer> sortKeyIdxes = new ArrayList<>();
        if (stmt.getSortKeys() != null) {
            Set<Integer> addedSortKey = new HashSet<>();
            List<String> baseSchemaNames = baseSchema.stream().map(Column::getName).collect(Collectors.toList());
            for (String column : stmt.getSortKeys()) {
                int idx = IntStream.range(0, baseSchemaNames.size())
                        .filter(i -> baseSchemaNames.get(i).equalsIgnoreCase(column))
                        .findFirst().orElse(-1);
                if (idx == -1) {
                    throw new DdlException("Invalid column '" + column + "': not exists in all columns.");
                }
                if (!addedSortKey.add(idx)) {
                    throw new DdlException("Duplicate sort key column " + column + " is not allowed.");
                }
                sortKeyIdxes.add(idx);
            }
            shortKeyColumnCount =
                    GlobalStateMgr.calcShortKeyColumnCount(baseSchema, stmt.getProperties(), sortKeyIdxes);
        } else {
            shortKeyColumnCount = GlobalStateMgr.calcShortKeyColumnCount(baseSchema, stmt.getProperties());
        }
        LOG.debug("create table[{}] short key column count: {}", tableName, shortKeyColumnCount);
        // indexes
        TableIndexes indexes = new TableIndexes(stmt.getIndexes());

        // set base index info to table
        // this should be done before create partition.
        Map<String, String> properties = stmt.getProperties();

        // create table
        long tableId = GlobalStateMgr.getCurrentState().getNextId();
        OlapTable table;
        // only OlapTable support light schema change so far
        Boolean useFastSchemaEvolution = true;
        if (stmt.isExternal()) {
            table = new ExternalOlapTable(db.getId(), tableId, tableName, baseSchema, keysType, partitionInfo,
                    distributionInfo, indexes, properties);
            if (GlobalStateMgr.getCurrentState().getNodeMgr()
                    .checkFeExistByRPCPort(((ExternalOlapTable) table).getSourceTableHost(),
                            ((ExternalOlapTable) table).getSourceTablePort())) {
                throw new DdlException("can not create OLAP external table of self cluster");
            }
            useFastSchemaEvolution = false;
        } else if (stmt.isOlapEngine()) {
            RunMode runMode = RunMode.getCurrentRunMode();
            String volume = "";
            if (properties != null && properties.containsKey(PropertyAnalyzer.PROPERTIES_STORAGE_VOLUME)) {
                volume = properties.remove(PropertyAnalyzer.PROPERTIES_STORAGE_VOLUME);
            }
            if (runMode == RunMode.SHARED_DATA) {
                if (volume.equals(StorageVolumeMgr.LOCAL)) {
                    throw new DdlException("Cannot create table " +
                            "without persistent volume in current run mode \"" + runMode + "\"");
                }
                if (properties != null && (properties.containsKey(PropertyAnalyzer.PROPERTIES_FLAT_JSON_ENABLE)
                        || properties.containsKey(PropertyAnalyzer.PROPERTIES_FLAT_JSON_COLUMN_MAX)
                        || properties.containsKey(PropertyAnalyzer.PROPERTIES_FLAT_JSON_SPARSITY_FACTOR)
                        || properties.containsKey(PropertyAnalyzer.PROPERTIES_FLAT_JSON_NULL_FACTOR))) {
                    throw new DdlException("Cannot create table " +
                            "with flat json config in current run mode \"" + runMode + "\"");
                }
                table = new LakeTable(tableId, tableName, baseSchema, keysType, partitionInfo, distributionInfo, indexes);
                StorageVolumeMgr svm = GlobalStateMgr.getCurrentState().getStorageVolumeMgr();
                if (table.isCloudNativeTable() && !svm.bindTableToStorageVolume(volume, db.getId(), tableId)) {
                    throw new DdlException(String.format("Storage volume %s not exists", volume));
                }
                String storageVolumeId = svm.getStorageVolumeIdOfTable(tableId);
                metastore.setLakeStorageInfo(db, table, storageVolumeId, properties);
            } else {
                table = new OlapTable(tableId, tableName, baseSchema, keysType, partitionInfo, distributionInfo, indexes);
            }
            // set session id for temporary table
            if (stmt instanceof CreateTemporaryTableStmt) {
                CreateTemporaryTableStmt createTemporaryTableStmt = (CreateTemporaryTableStmt) stmt;
                Preconditions.checkArgument(createTemporaryTableStmt.getSessionId() != null,
                        "temporary table must set session id");
                table.setSessionId(createTemporaryTableStmt.getSessionId());
            }
        } else {
            throw new DdlException("Unrecognized engine \"" + stmt.getEngineName() + "\"");
        }

        try {
            table.setComment(stmt.getComment());

            // set base index id
            long baseIndexId = metastore.getNextId();
            table.setBaseIndexId(baseIndexId);

            // get use light schema change
            try {
                useFastSchemaEvolution &= PropertyAnalyzer.analyzeUseFastSchemaEvolution(properties);
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }
            table.setUseFastSchemaEvolution(useFastSchemaEvolution);
            for (Column column : baseSchema) {
                column.setUniqueId(table.incAndGetMaxColUniqueId());
                // check reserved column for PK table
                if (table.getKeysType() == KeysType.PRIMARY_KEYS
                        && FeNameFormat.FORBIDDEN_COLUMN_NAMES.contains(column.getName())) {
                    throw new DdlException("Column name '" + column.getName()
                            + "' is reserved for primary key table");
                }
            }
            List<Integer> sortKeyUniqueIds = new ArrayList<>();
            if (useFastSchemaEvolution) {
                for (Integer idx : sortKeyIdxes) {
                    sortKeyUniqueIds.add(baseSchema.get(idx).getUniqueId());
                }
            } else {
                LOG.debug("table: {} doesn't use light schema change", table.getName());
            }

            // analyze bloom filter columns
            Set<String> bfColumns = null;
            double bfFpp = 0;
            try {
                bfColumns = PropertyAnalyzer.analyzeBloomFilterColumns(properties, baseSchema,
                        table.getKeysType() == KeysType.PRIMARY_KEYS);
                if (bfColumns != null && bfColumns.isEmpty()) {
                    bfColumns = null;
                }

                bfFpp = PropertyAnalyzer.analyzeBloomFilterFpp(properties);
                if (bfColumns != null && bfFpp == 0) {
                    bfFpp = FeConstants.DEFAULT_BLOOM_FILTER_FPP;
                } else if (bfColumns == null) {
                    bfFpp = 0;
                }

                Set<ColumnId> bfColumnIds = null;
                if (bfColumns != null && !bfColumns.isEmpty()) {
                    bfColumnIds = Sets.newTreeSet(ColumnId.CASE_INSENSITIVE_ORDER);
                    bfColumnIds.addAll(bfColumns.stream().map(ColumnId::create).collect(Collectors.toSet()));
                }
                table.setBloomFilterInfo(bfColumnIds, bfFpp);

                BloomFilterIndexUtil.analyseBfWithNgramBf(table, new HashSet<>(stmt.getIndexes()), bfColumnIds);
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }

            // analyze replication_num
            short replicationNum = RunMode.defaultReplicationNum();
            String logReplicationNum = "";
            try {
                boolean isReplicationNumSet =
                        properties != null && properties.containsKey(PropertyAnalyzer.PROPERTIES_REPLICATION_NUM);
                if (properties != null) {
                    logReplicationNum = properties.get(PropertyAnalyzer.PROPERTIES_REPLICATION_NUM);
                }
                replicationNum = PropertyAnalyzer.analyzeReplicationNum(properties, replicationNum);
                if (isReplicationNumSet) {
                    table.setReplicationNum(replicationNum);
                }
            } catch (AnalysisException ex) {
                throw new DdlException(String.format("%s table=%s, properties.replication_num=%s",
                        ex.getMessage(), table.getName(), logReplicationNum));
            }

            // analyze location property
            PropertyAnalyzer.analyzeLocation(table, properties);

            // set in memory
            boolean isInMemory =
                    PropertyAnalyzer.analyzeBooleanProp(properties, PropertyAnalyzer.PROPERTIES_INMEMORY, false);
            table.setIsInMemory(isInMemory);

            boolean enablePersistentIndex = PropertyAnalyzer.analyzeEnablePersistentIndex(properties);
            if (table.getKeysType() == KeysType.PRIMARY_KEYS) {
                if (!enablePersistentIndex) {
                    // disable memory index
                    throw new DdlException("In-Memory index is not supported, please create table with persistent index");
                } else {
                    table.setEnablePersistentIndex(enablePersistentIndex);
                }
            }
            if (table.isCloudNativeTable() && table.getKeysType() == KeysType.PRIMARY_KEYS) {
                TPersistentIndexType persistentIndexType;
                try {
                    persistentIndexType = PropertyAnalyzer.analyzePersistentIndexType(properties);
                } catch (AnalysisException e) {
                    throw new DdlException(e.getMessage());
                }
                // Judge there are whether compute nodes without storagePath or not.
                // Cannot create cloud native table with persistent_index = true when ComputeNode without storagePath
                Set<Long> cnUnSetStoragePath =
                        GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getAvailableComputeNodeIds().
                                stream()
                                .filter(id -> !GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getComputeNode(id).
                                        isSetStoragePath()).collect(Collectors.toSet());
                if (cnUnSetStoragePath.size() != 0 && persistentIndexType == TPersistentIndexType.LOCAL) {
                    // Check CN storage path when using local persistent index
                    throw new DdlException("Cannot create cloud native table with local persistent index" +
                            "when ComputeNode without storage_path, nodeId:" + cnUnSetStoragePath);
                }
                table.setPersistentIndexType(persistentIndexType);
            }

            if (table.isCloudNativeTable()) {
                TCompactionStrategy compactionStrategy;
                try {
                    compactionStrategy = PropertyAnalyzer.analyzecompactionStrategy(properties);
                } catch (AnalysisException e) {
                    throw new DdlException(e.getMessage());
                }
                // REAL_TIME strategy only work for primary key table right now, so set compaction strategy to DEFAULT
                // if table is not primary key table.
                if (table.getKeysType() != KeysType.PRIMARY_KEYS && compactionStrategy != TCompactionStrategy.DEFAULT) {
                    throw new DdlException("Only default compaction strategy is allowed for non-pk table.");
                }
                table.setCompactionStrategy(compactionStrategy);
            }

            try {
                table.setPrimaryIndexCacheExpireSec(PropertyAnalyzer.analyzePrimaryIndexCacheExpireSecProp(properties,
                        PropertyAnalyzer.PROPERTIES_PRIMARY_INDEX_CACHE_EXPIRE_SEC, 0));
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }

            if (properties != null && (properties.containsKey(PropertyAnalyzer.PROPERTIES_BINLOG_ENABLE) ||
                    properties.containsKey(PropertyAnalyzer.PROPERTIES_BINLOG_MAX_SIZE) ||
                    properties.containsKey(PropertyAnalyzer.PROPERTIES_BINLOG_TTL))) {
                try {
                    boolean enableBinlog = PropertyAnalyzer.analyzeBooleanProp(properties,
                            PropertyAnalyzer.PROPERTIES_BINLOG_ENABLE, false);
                    long binlogTtl = PropertyAnalyzer.analyzeLongProp(properties,
                            PropertyAnalyzer.PROPERTIES_BINLOG_TTL, Config.binlog_ttl_second);
                    long binlogMaxSize = PropertyAnalyzer.analyzeLongProp(properties,
                            PropertyAnalyzer.PROPERTIES_BINLOG_MAX_SIZE, Config.binlog_max_size);
                    BinlogConfig binlogConfig = new BinlogConfig(0, enableBinlog,
                            binlogTtl, binlogMaxSize);
                    table.setCurBinlogConfig(binlogConfig);
                    LOG.info("create table {} set binlog config, enable_binlog = {}, binlogTtl = {}, binlog_max_size = {}",
                            tableName, enableBinlog, binlogTtl, binlogMaxSize);
                } catch (AnalysisException e) {
                    throw new DdlException(e.getMessage());
                }
            }

            if (properties != null && (properties.containsKey(PropertyAnalyzer.PROPERTIES_FLAT_JSON_ENABLE) ||
                    properties.containsKey(PropertyAnalyzer.PROPERTIES_FLAT_JSON_NULL_FACTOR) ||
                    properties.containsKey(PropertyAnalyzer.PROPERTIES_FLAT_JSON_SPARSITY_FACTOR) ||
                    properties.containsKey(PropertyAnalyzer.PROPERTIES_FLAT_JSON_COLUMN_MAX))) {
                try {
                    boolean enableFlatJson = PropertyAnalyzer.analyzeBooleanProp(properties,
                            PropertyAnalyzer.PROPERTIES_FLAT_JSON_ENABLE, false);
                    if (!enableFlatJson) {
                        throw new DdlException("flat JSON configuration must be set after enabling flat JSON.");
                    }
                    double flatJsonNullFactor = PropertyAnalyzer.analyzerDoubleProp(properties,
                            PropertyAnalyzer.PROPERTIES_FLAT_JSON_NULL_FACTOR, Config.flat_json_null_factor);
                    double flatJsonSparsityFactory = PropertyAnalyzer.analyzerDoubleProp(properties,
                            PropertyAnalyzer.PROPERTIES_FLAT_JSON_SPARSITY_FACTOR, Config.flat_json_sparsity_factory);
                    int flatJsonColumnMax = PropertyAnalyzer.analyzeIntProp(properties,
                            PropertyAnalyzer.PROPERTIES_FLAT_JSON_COLUMN_MAX, Config.flat_json_column_max);
                    FlatJsonConfig flatJsonConfig = new FlatJsonConfig(enableFlatJson, flatJsonNullFactor,
                            flatJsonSparsityFactory, flatJsonColumnMax);
                    table.setFlatJsonConfig(flatJsonConfig);
                    LOG.info("create table {} set flat json config, flat_json_enable = {}, flat_json_null_factor = {}, " +
                            "flat_json_sparsity_factor = {}, flat_json_column_max = {}",
                            tableName, enableFlatJson, flatJsonNullFactor, flatJsonSparsityFactory, flatJsonColumnMax);
                } catch (AnalysisException e) {
                    throw new RuntimeException(e);
                }
            }

            try {
                long bucketSize = PropertyAnalyzer.analyzeLongProp(properties,
                        PropertyAnalyzer.PROPERTIES_BUCKET_SIZE, Config.default_automatic_bucket_size);
                if (bucketSize >= 0) {
                    table.setAutomaticBucketSize(bucketSize);
                } else {
                    throw new DdlException("Illegal bucket size: " + bucketSize);
                }
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }

            try {
                long mutableBucketNum = PropertyAnalyzer.analyzeLongProp(properties,
                        PropertyAnalyzer.PROPERTIES_MUTABLE_BUCKET_NUM, 0);
                if (mutableBucketNum >= 0) {
                    table.setMutableBucketNum(mutableBucketNum);
                } else {
                    throw new DdlException("Illegal mutable bucket num: " + mutableBucketNum);
                }
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }

            if (PropertyAnalyzer.analyzeBooleanProp(properties, PropertyAnalyzer.PROPERTIES_ENABLE_LOAD_PROFILE, false)) {
                table.setEnableLoadProfile(true);
            }

            try {
                table.setBaseCompactionForbiddenTimeRanges(PropertyAnalyzer.analyzeBaseCompactionForbiddenTimeRanges(properties));
                if (!table.getBaseCompactionForbiddenTimeRanges().isEmpty()) {
                    if (table instanceof OlapTable) {
                        OlapTable olapTable = (OlapTable) table;
                        if (olapTable.getKeysType() == KeysType.PRIMARY_KEYS
                                || olapTable.isCloudNativeTableOrMaterializedView()) {
                            throw new SemanticException("Property " +
                                    PropertyAnalyzer.PROPERTIES_BASE_COMPACTION_FORBIDDEN_TIME_RANGES +
                                    " not support primary keys table or cloud native table");
                        }
                    }
                    GlobalStateMgr.getCurrentState().getCompactionControlScheduler().updateTableForbiddenTimeRanges(
                            table.getId(), table.getBaseCompactionForbiddenTimeRanges());
                }
                if (properties != null) {
                    properties.remove(PropertyAnalyzer.PROPERTIES_BASE_COMPACTION_FORBIDDEN_TIME_RANGES);
                }
            } catch (Exception e) {
                throw new DdlException(e.getMessage());
            }
            
            try {
                boolean fileBundling = PropertyAnalyzer.analyzeFileBundling(properties);
                table.setFileBundling(fileBundling);
            } catch (Exception e) {
                throw new DdlException(e.getMessage());
            }

            // write quorum
            try {
                table.setWriteQuorum(PropertyAnalyzer.analyzeWriteQuorum(properties));
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }

            // replicated storage
            table.setEnableReplicatedStorage(
                    PropertyAnalyzer.analyzeBooleanProp(
                            properties, PropertyAnalyzer.PROPERTIES_REPLICATED_STORAGE,
                            Config.enable_replicated_storage_as_default_engine));

            if (table.enableReplicatedStorage().equals(false)) {
                for (Column col : baseSchema) {
                    if (col.isAutoIncrement()) {
                        throw new DdlException("Table with AUTO_INCREMENT column must use Replicated Storage");
                    }
                }
            }

            boolean hasGin = table.getIndexes().stream()
                    .anyMatch(index -> index.getIndexType() == IndexType.GIN);
            if (hasGin && table.enableReplicatedStorage()) {
                throw new SemanticException("GIN does not support replicated mode");
            }

            TTabletType tabletType = TTabletType.TABLET_TYPE_DISK;
            try {
                tabletType = PropertyAnalyzer.analyzeTabletType(properties);
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }

            if (table.isCloudNativeTable()) {
                if (properties != null) {
                    try {
                        PeriodDuration duration = PropertyAnalyzer.analyzeDataCachePartitionDuration(properties);
                        if (duration != null) {
                            table.setDataCachePartitionDuration(duration);
                        }
                    } catch (AnalysisException e) {
                        throw new DdlException(e.getMessage());
                    }
                }
            }

            if (properties != null) {
                if (properties.containsKey(PropertyAnalyzer.PROPERTIES_STORAGE_COOLDOWN_TTL) ||
                        properties.containsKey(PropertyAnalyzer.PROPERTIES_STORAGE_COOLDOWN_TIME)) {
                    if (table.getKeysType() == KeysType.PRIMARY_KEYS) {
                        throw new DdlException("Primary key table does not support storage medium cool down currently.");
                    }
                    if (partitionInfo instanceof ListPartitionInfo) {
                        throw new DdlException("List partition table does not support storage medium cool down currently.");
                    }
                    if (partitionInfo instanceof RangePartitionInfo) {
                        RangePartitionInfo rangePartitionInfo = (RangePartitionInfo) partitionInfo;
                        List<Column> partitionColumns = rangePartitionInfo.getPartitionColumns(
                                MetaUtils.buildIdToColumn(baseSchema));
                        if (partitionColumns.size() > 1) {
                            throw new DdlException("Multi-column range partition table " +
                                    "does not support storage medium cool down currently.");
                        }
                        Column column = partitionColumns.get(0);
                        if (!column.getType().getPrimitiveType().isDateType()) {
                            throw new DdlException("Only support partition is date type for" +
                                    " storage medium cool down currently.");
                        }
                    }
                }
            }

            if (properties != null) {
                try {
                    PeriodDuration duration = PropertyAnalyzer.analyzeStorageCoolDownTTL(properties, false);
                    if (duration != null) {
                        table.setStorageCoolDownTTL(duration);
                    }
                } catch (AnalysisException e) {
                    throw new DdlException(e.getMessage());
                }
            }

            if (partitionInfo.getType() == PartitionType.UNPARTITIONED) {
                // if this is an unpartitioned table, we should analyze data property and replication num here.
                // if this is a partitioned table, there properties are already analyzed in RangePartitionDesc analyze phase.

                // use table name as this single partition name
                long partitionId = partitionNameToId.get(tableName);
                DataProperty dataProperty = null;
                try {
                    boolean hasMedium = false;
                    if (properties != null) {
                        hasMedium = properties.containsKey(PropertyAnalyzer.PROPERTIES_STORAGE_MEDIUM);
                    }
                    dataProperty = PropertyAnalyzer.analyzeDataProperty(properties,
                            DataProperty.getInferredDefaultDataProperty(), false);
                    if (hasMedium) {
                        table.setStorageMedium(dataProperty.getStorageMedium());
                    }
                } catch (AnalysisException e) {
                    throw new DdlException(e.getMessage());
                }
                Preconditions.checkNotNull(dataProperty);
                partitionInfo.setDataProperty(partitionId, dataProperty);
                partitionInfo.setReplicationNum(partitionId, replicationNum);
                partitionInfo.setIsInMemory(partitionId, isInMemory);
                partitionInfo.setTabletType(partitionId, tabletType);
                StorageInfo storageInfo = table.getTableProperty().getStorageInfo();
                DataCacheInfo dataCacheInfo = storageInfo == null ? null : storageInfo.getDataCacheInfo();
                partitionInfo.setDataCacheInfo(partitionId, dataCacheInfo);
            }

            // check colocation properties
            String colocateGroup = PropertyAnalyzer.analyzeColocate(properties);
            if (StringUtils.isNotEmpty(colocateGroup)) {
                if (!distributionInfo.supportColocate()) {
                    throw new DdlException("random distribution does not support 'colocate_with'");
                }

                colocateTableIndex.addTableToGroup(db, table, colocateGroup, false /* expectLakeTable */);
            }

            // get base index storage type. default is COLUMN
            TStorageType baseIndexStorageType;
            try {
                baseIndexStorageType = PropertyAnalyzer.analyzeStorageType(properties, table);
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }
            Preconditions.checkNotNull(baseIndexStorageType);
            // set base index meta
            int schemaVersion = 0;
            try {
                schemaVersion = PropertyAnalyzer.analyzeSchemaVersion(properties);
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }
            Util.checkColumnSupported(baseSchema);
            int schemaHash = Util.schemaHash(schemaVersion, baseSchema, bfColumns, bfFpp);

            if (stmt.getSortKeys() != null) {
                table.setIndexMeta(baseIndexId, tableName, baseSchema, schemaVersion, schemaHash,
                        shortKeyColumnCount, baseIndexStorageType, keysType, null, sortKeyIdxes,
                        sortKeyUniqueIds);
            } else {
                table.setIndexMeta(baseIndexId, tableName, baseSchema, schemaVersion, schemaHash,
                        shortKeyColumnCount, baseIndexStorageType, keysType, null);
            }

            for (AlterClause alterClause : stmt.getRollupAlterClauseList()) {
                AddRollupClause addRollupClause = (AddRollupClause) alterClause;

                Long baseRollupIndex = table.getIndexIdByName(tableName);

                // get storage type for rollup index
                TStorageType rollupIndexStorageType = null;
                try {
                    rollupIndexStorageType = PropertyAnalyzer.analyzeStorageType(addRollupClause.getProperties(), table);
                } catch (AnalysisException e) {
                    throw new DdlException(e.getMessage());
                }
                Preconditions.checkNotNull(rollupIndexStorageType);
                // set rollup index meta to olap table
                List<Column> rollupColumns = stateMgr.getRollupHandler().checkAndPrepareMaterializedView(addRollupClause,
                        table, baseRollupIndex);
                short rollupShortKeyColumnCount =
                        GlobalStateMgr.calcShortKeyColumnCount(rollupColumns, addRollupClause.getProperties());
                int rollupSchemaHash = Util.schemaHash(schemaVersion, rollupColumns, bfColumns, bfFpp);
                long rollupIndexId = metastore.getNextId();
                table.setIndexMeta(rollupIndexId, addRollupClause.getRollupName(), rollupColumns, schemaVersion,
                        rollupSchemaHash, rollupShortKeyColumnCount, rollupIndexStorageType, keysType);
            }

            // analyze version info
            Long version = null;
            try {
                version = PropertyAnalyzer.analyzeVersionInfo(properties);
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }
            Preconditions.checkNotNull(version);

            // storage_format is not necessary, remove storage_format if exists.
            if (properties != null) {
                properties.remove("storage_format");
            }

            //storage type
            table.setStorageType(baseIndexStorageType.name());

            // get compression type
            TCompressionType compressionType = TCompressionType.LZ4_FRAME;
            Integer compressionLevel = -1;
            try {
                Pair<TCompressionType, Integer> result = PropertyAnalyzer.analyzeCompressionType(properties);
                compressionType = result.first;
                compressionLevel = result.second;
            } catch (AnalysisException e) {
                throw new DdlException(e.getMessage());
            }
            table.setCompressionType(compressionType);
            table.setCompressionLevel(compressionLevel);

            // partition live number
            if (properties != null && properties.containsKey(PropertyAnalyzer.PROPERTIES_PARTITION_LIVE_NUMBER)) {
                int partitionLiveNumber = PropertyAnalyzer.analyzePartitionLiveNumber(properties, true);
                table.setPartitionLiveNumber(partitionLiveNumber);
            }

            // analyze partition ttl duration
            if (properties != null && properties.containsKey(PropertyAnalyzer.PROPERTIES_PARTITION_TTL)) {
                Pair<String, PeriodDuration> ttlDuration = PropertyAnalyzer.analyzePartitionTTL(properties, true);
                if (ttlDuration == null) {
                    throw new DdlException("Invalid partition ttl duration");
                }
                table.getTableProperty().getProperties().put(PropertyAnalyzer.PROPERTIES_PARTITION_TTL, ttlDuration.first);
                table.getTableProperty().setPartitionTTL(ttlDuration.second);
            }

            // analyze partition retention condition
            if (properties != null && properties.containsKey(PropertyAnalyzer.PROPERTIES_PARTITION_RETENTION_CONDITION)) {
                String ttlCondition = PropertyAnalyzer.analyzePartitionRetentionCondition(db, table, properties,
                        true, null);
                if (ttlCondition == null) {
                    throw new DdlException("Invalid partition retention condition");
                }
                table.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_PARTITION_RETENTION_CONDITION, ttlCondition);
                table.getTableProperty().setPartitionRetentionCondition(ttlCondition);
            }

            // analyze time drift constraint
            if (properties != null && properties.containsKey(PropertyAnalyzer.PROPERTIES_TIME_DRIFT_CONSTRAINT)) {
                String timeDriftConstraintSpec = properties.get(PropertyAnalyzer.PROPERTIES_TIME_DRIFT_CONSTRAINT);
                PropertyAnalyzer.analyzeTimeDriftConstraint(timeDriftConstraintSpec, table, properties);
                table.getTableProperty().getProperties()
                        .put(PropertyAnalyzer.PROPERTIES_TIME_DRIFT_CONSTRAINT, timeDriftConstraintSpec);
                table.getTableProperty().setTimeDriftConstraintSpec(timeDriftConstraintSpec);
            }

            try {
                processConstraint(db, table, properties);
            } catch (AnalysisException e) {
                throw new DdlException(
                        String.format("processing constraint failed when creating table:%s. exception msg:%s",
                                table.getName(), e.getMessage()), e);
            }

            // a set to record every new tablet created when create table
            // if failed in any step, use this set to do clear things
            Set<Long> tabletIdSet = new HashSet<Long>();

            ComputeResource computeResource = WarehouseManager.DEFAULT_RESOURCE;
            if (ConnectContext.get() != null) {
                ConnectContext connectContext = ConnectContext.get();
                computeResource = connectContext.getCurrentComputeResource();
            }

            // do not create partition for external table
            if (table.isOlapOrCloudNativeTable()) {
                if (partitionInfo.getType() == PartitionType.UNPARTITIONED) {
                    if (properties != null && !properties.isEmpty()) {
                        // here, all properties should be checked
                        throw new DdlException("Unknown properties: " + properties);
                    }

                    // this is a 1-level partitioned table, use table name as partition name
                    long partitionId = partitionNameToId.get(tableName);
                    Partition partition = metastore.createPartition(db, table, partitionId, tableName, version, tabletIdSet,
                            computeResource);
                    metastore.buildPartitions(db, table, partition.getSubPartitions().stream().collect(Collectors.toList()),
                            computeResource);
                    table.addPartition(partition);
                } else if (partitionInfo.isRangePartition() || partitionInfo.getType() == PartitionType.LIST) {
                    try {
                        // just for remove entries in stmt.getProperties(),
                        // and then check if there still has unknown properties
                        boolean hasMedium = false;
                        if (properties != null) {
                            hasMedium = properties.containsKey(PropertyAnalyzer.PROPERTIES_STORAGE_MEDIUM);
                        }
                        DataProperty dataProperty = PropertyAnalyzer.analyzeDataProperty(properties,
                                DataProperty.getInferredDefaultDataProperty(), false);
                        DynamicPartitionUtil.checkAndSetDynamicPartitionProperty(table, properties);
                        if (table.dynamicPartitionExists() && table.getColocateGroup() != null) {
                            HashDistributionInfo info = (HashDistributionInfo) distributionInfo;
                            if (info.getBucketNum() !=
                                    table.getTableProperty().getDynamicPartitionProperty().getBuckets()) {
                                throw new DdlException("dynamic_partition.buckets should equal the distribution buckets"
                                        + " if creating a colocate table");
                            }
                        }
                        if (hasMedium) {
                            table.setStorageMedium(dataProperty.getStorageMedium());
                        }
                        if (properties != null && !properties.isEmpty()) {
                            // here, all properties should be checked
                            throw new DdlException("Unknown properties: " + properties);
                        }
                    } catch (AnalysisException e) {
                        throw new DdlException(e.getMessage());
                    }

                    // this is a 2-level partitioned tables
                    List<Partition> partitions = new ArrayList<>(partitionNameToId.size());
                    for (Map.Entry<String, Long> entry : partitionNameToId.entrySet()) {
                        Partition partition = metastore.createPartition(db, table, entry.getValue(), entry.getKey(), version,
                                tabletIdSet, computeResource);
                        partitions.add(partition);
                    }
                    // It's ok if partitions is empty.
                    metastore.buildPartitions(db, table, partitions.stream().map(Partition::getSubPartitions)
                            .flatMap(p -> p.stream()).collect(Collectors.toList()), computeResource);
                    for (Partition partition : partitions) {
                        table.addPartition(partition);
                    }
                } else {
                    throw new DdlException("Unsupported partition method: " + partitionInfo.getType().name());
                }
                // if binlog_enable is true when creating table,
                // then set binlogAvailableVersion without statistics through reportHandler
                if (table.isBinlogEnabled()) {
                    Map<String, String> binlogAvailableVersion = table.buildBinlogAvailableVersion();
                    table.setBinlogAvailableVersion(binlogAvailableVersion);
                    LOG.info("set binlog available version when create table, tableName : {}, partitions : {}",
                            tableName, binlogAvailableVersion.toString());
                }
            }

            // process lake table colocation properties, after partition and tablet creation
            colocateTableIndex.addTableToGroup(db, table, colocateGroup, true /* expectLakeTable */);
        } catch (DdlException e) {
            GlobalStateMgr.getCurrentState().getStorageVolumeMgr().unbindTableToStorageVolume(tableId);
            throw e;
        }

        return table;
    }

    private void processConstraint(
            Database db, OlapTable olapTable, Map<String, String> properties) throws AnalysisException {
        List<UniqueConstraint> uniqueConstraints = PropertyAnalyzer.analyzeUniqueConstraint(properties, db, olapTable);
        olapTable.setUniqueConstraints(uniqueConstraints);

        List<ForeignKeyConstraint> foreignKeyConstraints =
                PropertyAnalyzer.analyzeForeignKeyConstraint(properties, db, olapTable);
        olapTable.setForeignKeyConstraints(foreignKeyConstraints);
    }

}
