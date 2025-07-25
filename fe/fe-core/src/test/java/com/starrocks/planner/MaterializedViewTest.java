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

package com.starrocks.planner;

import com.google.api.client.util.Lists;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.starrocks.analysis.TableName;
import com.starrocks.backup.Status;
import com.starrocks.backup.mv.MVRestoreUpdater;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Table;
import com.starrocks.common.Pair;
import com.starrocks.qe.SessionVariable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.plan.PlanTestBase;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class MaterializedViewTest extends MaterializedViewTestBase {
    private static final List<String> outerJoinTypes = ImmutableList.of("left", "right");

    @BeforeAll
    public static void beforeClass() throws Exception {
        MaterializedViewTestBase.beforeClass();

        starRocksAssert.useDatabase(MATERIALIZED_DB_NAME);

        starRocksAssert.useTable("depts");
        starRocksAssert.useTable("depts_null");
        starRocksAssert.useTable("locations");
        starRocksAssert.useTable("emps");
        starRocksAssert.useTable("emps_null");
        starRocksAssert.useTable("emps_bigint");
        starRocksAssert.useTable("emps_no_constraint");
        starRocksAssert.useTable("dependents");

        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `customer_unique` (\n" +
                "    `c_custkey` int(11) NOT NULL,\n" +
                "    `c_name` varchar(26) NOT NULL,\n" +
                "    `c_address` varchar(41) NOT NULL,\n" +
                "    `c_city` varchar(11) NOT NULL,\n" +
                "    `c_nation` varchar(16) NOT NULL,\n" +
                "    `c_region` varchar(13) NOT NULL,\n" +
                "    `c_phone` varchar(16) NOT NULL,\n" +
                "    `c_mktsegment` varchar(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "UNIQUE KEY(`c_custkey`)\n" +
                "DISTRIBUTED BY HASH(`c_custkey`) BUCKETS 12\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa2\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ")\n");

        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `customer_primary` (\n" +
                "    `c_custkey` int(11) NOT NULL,\n" +
                "    `c_name` varchar(26) NOT NULL,\n" +
                "    `c_address` varchar(41) NOT NULL,\n" +
                "    `c_city` varchar(11) NOT NULL,\n" +
                "    `c_nation` varchar(16) NOT NULL,\n" +
                "    `c_region` varchar(13) NOT NULL,\n" +
                "    `c_phone` varchar(16) NOT NULL,\n" +
                "    `c_mktsegment` varchar(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "PRIMARY KEY(`c_custkey`)\n" +
                "DISTRIBUTED BY HASH(`c_custkey`) BUCKETS 12\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa2\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ")\n");

        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `customer_null` (\n" +
                "    `c_custkey` int(11) NULL,\n" +
                "    `c_name` varchar(26) NULL,\n" +
                "    `c_address` varchar(41) NULL,\n" +
                "    `c_city` varchar(11) NULL,\n" +
                "    `c_nation` varchar(16) NULL,\n" +
                "    `c_region` varchar(13) NULL,\n" +
                "    `c_phone` varchar(16) NULL,\n" +
                "    `c_mktsegment` varchar(11) NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`c_custkey`)\n" +
                "DISTRIBUTED BY HASH(`c_custkey`) BUCKETS 12\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa2\",\n" +
                "    \"in_memory\" = \"false\",\n" +
                "    \"unique_constraints\" = \"c_custkey\"\n" +
                ")\n");

        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `customer` (\n" +
                "    `c_custkey` int(11) NOT NULL,\n" +
                "    `c_name` varchar(26) NOT NULL,\n" +
                "    `c_address` varchar(41) NOT NULL,\n" +
                "    `c_city` varchar(11) NOT NULL,\n" +
                "    `c_nation` varchar(16) NOT NULL,\n" +
                "    `c_region` varchar(13) NOT NULL,\n" +
                "    `c_phone` varchar(16) NOT NULL,\n" +
                "    `c_mktsegment` varchar(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`c_custkey`)\n" +
                "DISTRIBUTED BY HASH(`c_custkey`) BUCKETS 12\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa2\",\n" +
                "    \"in_memory\" = \"false\",\n" +
                "    \"unique_constraints\" = \"c_custkey\"\n" +
                ")\n");
        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `dates` (\n" +
                "    `d_datekey` int(11) NOT NULL,\n" +
                "    `d_date` varchar(20) NOT NULL,\n" +
                "    `d_dayofweek` varchar(10) NOT NULL,\n" +
                "    `d_month` varchar(11) NOT NULL,\n" +
                "    `d_year` int(11) NOT NULL,\n" +
                "    `d_yearmonthnum` int(11) NOT NULL,\n" +
                "    `d_yearmonth` varchar(9) NOT NULL,\n" +
                "    `d_daynuminweek` int(11) NOT NULL,\n" +
                "    `d_daynuminmonth` int(11) NOT NULL,\n" +
                "    `d_daynuminyear` int(11) NOT NULL,\n" +
                "    `d_monthnuminyear` int(11) NOT NULL,\n" +
                "    `d_weeknuminyear` int(11) NOT NULL,\n" +
                "    `d_sellingseason` varchar(14) NOT NULL,\n" +
                "    `d_lastdayinweekfl` int(11) NOT NULL,\n" +
                "    `d_lastdayinmonthfl` int(11) NOT NULL,\n" +
                "    `d_holidayfl` int(11) NOT NULL,\n" +
                "    `d_weekdayfl` int(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`d_datekey`)\n" +
                "DISTRIBUTED BY HASH(`d_datekey`) BUCKETS 1\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\",\n" +
                "    \"colocate_with\" = \"groupa3\",\n" +
                "    \"unique_constraints\" = \"d_datekey\"\n" +
                ")");
        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `part` (\n" +
                "    `p_partkey` int(11) NOT NULL,\n" +
                "    `p_name` varchar(23) NOT NULL,\n" +
                "    `p_mfgr` varchar(7) NOT NULL,\n" +
                "    `p_category` varchar(8) NOT NULL,\n" +
                "    `p_brand` varchar(10) NOT NULL,\n" +
                "    `p_color` varchar(12) NOT NULL,\n" +
                "    `p_type` varchar(26) NOT NULL,\n" +
                "    `p_size` int(11) NOT NULL,\n" +
                "    `p_container` varchar(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`p_partkey`)\n" +
                "DISTRIBUTED BY HASH(`p_partkey`) BUCKETS 12\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa5\",\n" +
                "    \"in_memory\" = \"false\",\n" +
                "    \"unique_constraints\" = \"p_partkey\"\n" +
                ")");
        starRocksAssert.withTable(" CREATE TABLE IF NOT EXISTS `supplier` (\n" +
                "    `s_suppkey` int(11) NOT NULL,\n" +
                "    `s_name` varchar(26) NOT NULL,\n" +
                "    `s_address` varchar(26) NOT NULL,\n" +
                "    `s_city` varchar(11) NOT NULL,\n" +
                "    `s_nation` varchar(16) NOT NULL,\n" +
                "    `s_region` varchar(13) NOT NULL,\n" +
                "    `s_phone` varchar(16) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`s_suppkey`)\n" +
                "DISTRIBUTED BY HASH(`s_suppkey`) BUCKETS 12\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa4\",\n" +
                "    \"in_memory\" = \"false\",\n" +
                "    \"unique_constraints\" = \"s_suppkey\"\n" +
                ")");

        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `lineorder` (\n" +
                "    `lo_orderkey` int(11) NOT NULL,\n" +
                "    `lo_linenumber` int(11) NOT NULL,\n" +
                "    `lo_custkey` int(11) NOT NULL,\n" +
                "    `lo_partkey` int(11) NOT NULL,\n" +
                "    `lo_suppkey` int(11) NOT NULL,\n" +
                "    `lo_orderdate` int(11) NOT NULL,\n" +
                "    `lo_orderpriority` varchar(16) NOT NULL,\n" +
                "    `lo_shippriority` int(11) NOT NULL,\n" +
                "    `lo_quantity` int(11) NOT NULL,\n" +
                "    `lo_extendedprice` int(11) NOT NULL,\n" +
                "    `lo_ordtotalprice` int(11) NOT NULL,\n" +
                "    `lo_discount` int(11) NOT NULL,\n" +
                "    `lo_revenue` int(11) NOT NULL,\n" +
                "    `lo_supplycost` int(11) NOT NULL,\n" +
                "    `lo_tax` int(11) NOT NULL,\n" +
                "    `lo_commitdate` int(11) NOT NULL,\n" +
                "    `lo_shipmode` varchar(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`lo_orderkey`)\n" +
                "PARTITION BY RANGE(`lo_orderdate`)\n" +
                "(\n" +
                "    PARTITION p1 VALUES [(\"-2147483648\"), (\"19930101\")),\n" +
                "    PARTITION p2 VALUES [(\"19930101\"), (\"19940101\")),\n" +
                "    PARTITION p3 VALUES [(\"19940101\"), (\"19950101\")),\n" +
                "    PARTITION p4 VALUES [(\"19950101\"), (\"19960101\")),\n" +
                "    PARTITION p5 VALUES [(\"19960101\"), (\"19970101\")),\n" +
                "    PARTITION p6 VALUES [(\"19970101\"), (\"19980101\")),\n" +
                "    PARTITION p7 VALUES [(\"19980101\"), (\"19990101\")))\n" +
                "DISTRIBUTED BY HASH(`lo_orderkey`) BUCKETS 48\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa1\",\n" +
                "    \"in_memory\" = \"false\",\n" +
                "    \"unique_constraints\" = \"lo_custkey\",\n" +
                "    \"foreign_key_constraints\" = \"(lo_custkey) REFERENCES customer(c_custkey);" +
                " (lo_partkey) REFERENCES part(p_partkey);  (lo_suppkey) REFERENCES supplier(s_suppkey);" +
                "  (lo_orderdate) REFERENCES dates(d_datekey)\"\n" +
                ")");

        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `lineorder_primary` (\n" +
                "    `lo_custkey` int(11) NOT NULL,\n" +
                "    `lo_orderkey` int(11) NOT NULL,\n" +
                "    `lo_linenumber` int(11) NOT NULL,\n" +
                "    `lo_partkey` int(11) NOT NULL,\n" +
                "    `lo_suppkey` int(11) NOT NULL,\n" +
                "    `lo_orderdate` int(11) NOT NULL,\n" +
                "    `lo_orderpriority` varchar(16) NOT NULL,\n" +
                "    `lo_shippriority` int(11) NOT NULL,\n" +
                "    `lo_quantity` int(11) NOT NULL,\n" +
                "    `lo_extendedprice` int(11) NOT NULL,\n" +
                "    `lo_ordtotalprice` int(11) NOT NULL,\n" +
                "    `lo_discount` int(11) NOT NULL,\n" +
                "    `lo_revenue` int(11) NOT NULL,\n" +
                "    `lo_supplycost` int(11) NOT NULL,\n" +
                "    `lo_tax` int(11) NOT NULL,\n" +
                "    `lo_commitdate` int(11) NOT NULL,\n" +
                "    `lo_shipmode` varchar(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "PRIMARY KEY(`lo_custkey`)\n" +
                "DISTRIBUTED BY HASH(`lo_custkey`) BUCKETS 48\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ")");

        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `lineorder_unique` (\n" +
                "    `lo_custkey` int(11) NOT NULL,\n" +
                "    `lo_orderkey` int(11) NOT NULL,\n" +
                "    `lo_linenumber` int(11) NOT NULL,\n" +
                "    `lo_partkey` int(11) NOT NULL,\n" +
                "    `lo_suppkey` int(11) NOT NULL,\n" +
                "    `lo_orderdate` int(11) NOT NULL,\n" +
                "    `lo_orderpriority` varchar(16) NOT NULL,\n" +
                "    `lo_shippriority` int(11) NOT NULL,\n" +
                "    `lo_quantity` int(11) NOT NULL,\n" +
                "    `lo_extendedprice` int(11) NOT NULL,\n" +
                "    `lo_ordtotalprice` int(11) NOT NULL,\n" +
                "    `lo_discount` int(11) NOT NULL,\n" +
                "    `lo_revenue` int(11) NOT NULL,\n" +
                "    `lo_supplycost` int(11) NOT NULL,\n" +
                "    `lo_tax` int(11) NOT NULL,\n" +
                "    `lo_commitdate` int(11) NOT NULL,\n" +
                "    `lo_shipmode` varchar(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "UNIQUE KEY(`lo_custkey`)\n" +
                "DISTRIBUTED BY HASH(`lo_custkey`) BUCKETS 48\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ")");

        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `lineorder_null` (\n" +
                "    `lo_orderkey` int(11) NOT NULL,\n" +
                "    `lo_linenumber` int(11) NOT NULL,\n" +
                "    `lo_custkey` int(11),\n" +
                "    `lo_partkey` int(11),\n" +
                "    `lo_suppkey` int(11),\n" +
                "    `lo_orderdate` int(11),\n" +
                "    `lo_orderpriority` varchar(16) NOT NULL,\n" +
                "    `lo_shippriority` int(11) NOT NULL,\n" +
                "    `lo_quantity` int(11) NOT NULL,\n" +
                "    `lo_extendedprice` int(11) NOT NULL,\n" +
                "    `lo_ordtotalprice` int(11) NOT NULL,\n" +
                "    `lo_discount` int(11) NOT NULL,\n" +
                "    `lo_revenue` int(11) NOT NULL,\n" +
                "    `lo_supplycost` int(11) NOT NULL,\n" +
                "    `lo_tax` int(11) NOT NULL,\n" +
                "    `lo_commitdate` int(11) NOT NULL,\n" +
                "    `lo_shipmode` varchar(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`lo_orderkey`)\n" +
                "PARTITION BY RANGE(`lo_orderdate`)\n" +
                "(\n" +
                "    PARTITION p1 VALUES [(\"-2147483648\"), (\"19930101\")),\n" +
                "    PARTITION p2 VALUES [(\"19930101\"), (\"19940101\")),\n" +
                "    PARTITION p3 VALUES [(\"19940101\"), (\"19950101\")),\n" +
                "    PARTITION p4 VALUES [(\"19950101\"), (\"19960101\")),\n" +
                "    PARTITION p5 VALUES [(\"19960101\"), (\"19970101\")),\n" +
                "    PARTITION p6 VALUES [(\"19970101\"), (\"19980101\")),\n" +
                "    PARTITION p7 VALUES [(\"19980101\"), (\"19990101\")))\n" +
                "DISTRIBUTED BY HASH(`lo_orderkey`) BUCKETS 48\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa1\",\n" +
                "    \"in_memory\" = \"false\",\n" +
                "    \"foreign_key_constraints\" = \"(lo_custkey) REFERENCES customer(c_custkey);" +
                " (lo_partkey) REFERENCES part(p_partkey);  (lo_suppkey) REFERENCES supplier(s_suppkey);" +
                "  (lo_orderdate) REFERENCES dates(d_datekey)\"\n" +
                ")");

        starRocksAssert.withTable(" CREATE TABLE IF NOT EXISTS `t2` (\n" +
                "    `c5` int(11) NOT NULL,\n" +
                "    `c6` int(11) NOT NULL,\n" +
                "    `c7` int(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`c5`)\n" +
                "DISTRIBUTED BY HASH(`c5`) BUCKETS 12\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa4\",\n" +
                "    \"in_memory\" = \"false\",\n" +
                "    \"unique_constraints\" = \"c5\"\n" +
                ")");

        starRocksAssert.withTable(" CREATE TABLE IF NOT EXISTS `t3` (\n" +
                "    `c5` int(11) NOT NULL,\n" +
                "    `c6` int(11) NOT NULL,\n" +
                "    `c7` int(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`c5`)\n" +
                "DISTRIBUTED BY HASH(`c5`) BUCKETS 12\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa4\",\n" +
                "    \"in_memory\" = \"false\",\n" +
                "    \"unique_constraints\" = \"c5\"\n" +
                ")");

        starRocksAssert.withTable(" CREATE TABLE IF NOT EXISTS `t1` (\n" +
                "    `c1` int(11) NOT NULL,\n" +
                "    `c2` int(11) NOT NULL,\n" +
                "    `c3` int(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`c1`)\n" +
                "DISTRIBUTED BY HASH(`c1`) BUCKETS 12\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"colocate_with\" = \"groupa4\",\n" +
                "    \"in_memory\" = \"false\",\n" +
                "    \"foreign_key_constraints\" = \"(c2) REFERENCES t2(c5);(c3) REFERENCES t2(c5)\"\n" +
                ")");

        String userTagTable = "create table user_tags " +
                "(time date, user_id int, user_name varchar(20), tag_id int) " +
                "partition by range (time) (partition p1 values less than MAXVALUE) " +
                "distributed by hash(time) " +
                "buckets 3 " +
                "properties('replication_num' = '1');";
        starRocksAssert
                .withTable(userTagTable);

        String eventTable = "CREATE TABLE `event1` (\n" +
                "  `event_id` int(11) NOT NULL,\n" +
                "  `event_type` varchar(26) NOT NULL,\n" +
                "  `event_time` datetime NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`event_id`)\n" +
                "DISTRIBUTED BY HASH(`event_id`) BUCKETS 12\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");";
        starRocksAssert.withTable(eventTable);

        starRocksAssert.withTable("CREATE TABLE test_agg_with_having_tbl (\n" +
                "dt date NULL,\n" +
                "col1 varchar(240) NULL,\n" +
                "col2 varchar(30) NULL,\n" +
                "col3 varchar(60) NULL,\n" +
                "col4 decimal128(22, 2) NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(dt, col1)\n" +
                "DISTRIBUTED BY HASH(dt, col1) BUCKETS 1 " +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\"\n" +
                ");");

        GlobalStateMgr globalStateMgr = connectContext.getGlobalStateMgr();
        OlapTable t7 = (OlapTable) globalStateMgr.getLocalMetastore().getDb(MATERIALIZED_DB_NAME).getTable("emps");
        setTableStatistics(t7, 6000000);

        connectContext.getSessionVariable().setEnableEliminateAgg(false);
    }

    @Test
    public void testFilter0() {
        String mv = "select empid + 1 as col1 from emps where deptno = 10";
        testRewriteOK(mv, "select empid + 1 from emps where deptno = 10");
        testRewriteOK(mv, "select max(empid + 1) from emps where deptno = 10");
        testRewriteFail(mv, "select max(empid) from emps where deptno = 10");
        // with typecast, cast(empid as bigint) is rewritten as col1 - 1
        testRewriteOK(mv, "select max(empid + 2) from emps where deptno = 10").contains("col1 - 1 + 2");

        testRewriteFail(mv, "select max(empid) from emps where deptno = 11");
        testRewriteFail(mv, "select max(empid) from emps");
        testRewriteFail(mv, "select empid from emps where deptno = 10");

        // no typecasting, empid is rewritten as col1 - 1
        mv = "select empid + 1 as col1 from emps_bigint where deptno = 10";
        testRewriteOK(mv, "select max(empid) from emps_bigint where deptno = 10").contains("col1 - 1");
        testRewriteOK(mv, "select empid from emps_bigint where deptno = 10").contains("col1 - 1");
    }

    @Test
    public void testFilterProject0() {
        String mv = "select empid, locationid * 2 as col2 from emps where deptno = 10";
        testRewriteOK(mv, "select empid + 1 from emps where deptno = 10");
        testRewriteOK(mv, "select empid from emps where deptno = 10 and (locationid * 2) < 10");
    }

    @Test
    public void testSwapInnerJoin() {
        String mv = "select count(*) as col1 from emps join locations on emps.locationid = locations.locationid";
        testRewriteOK(mv, "select count(*) from locations join emps on emps.locationid = locations.locationid");
        testRewriteOK(mv, "select count(*) + 1 from  locations join emps on emps.locationid = locations.locationid");
    }

    @Test
    public void testsInnerJoinComplete() {
        String mv = "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                "join locations on emps.locationid = locations.locationid";
        testRewriteOK(mv, "select count(*)  from emps " +
                "join locations on emps.locationid = locations.locationid ");
        testRewriteOK(mv, "select count(*)  from emps " +
                "join locations on emps.locationid = locations.locationid " +
                "and emps.locationid > 10");
        testRewriteOK(mv, "select empid as col2, emps.locationid from emps " +
                "join locations on emps.locationid = locations.locationid " +
                "and emps.locationid > 10");
        testRewriteOK(mv, "select empid as col2, locations.locationid from emps " +
                "join locations on emps.locationid = locations.locationid " +
                "and locations.locationid > 10");
        testRewriteOK(mv, "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                "join locations on emps.locationid = locations.locationid where emps.locationid=10");
    }

    @Test
    public void testsInnerJoinCompleteWithPredicates() {
        String mv = "select locations.locationid, empid, sum(emps.deptno) as col3 from emps " +
                "join locations on emps.locationid = locations.locationid group by empid,locations.locationid";
        testRewriteOK(mv, "select emps.locationid, empid, sum(emps.deptno) as col3 from emps " +
                "join locations on emps.locationid = locations.locationid where empid = 10 group by empid,emps.locationid");
        testRewriteOK(mv, "select emps.locationid, empid, sum(emps.deptno) as col3 from emps " +
                "join locations on emps.locationid = locations.locationid where locations.locationid= 10 " +
                "group by empid,emps.locationid");
        testRewriteOK(mv, "select emps.locationid, empid, sum(emps.deptno) as col3 from emps " +
                "join locations on emps.locationid = locations.locationid where emps.locationid= 10 " +
                "group by empid,emps.locationid");
        testRewriteOK(mv, "select emps.locationid, empid, sum(emps.deptno) as col3 from emps " +
                "join locations on emps.locationid = locations.locationid where emps.locationid= 10 " +
                "group by empid,emps.locationid");
        testRewriteOK(mv, "select emps.locationid, empid, sum(emps.deptno) as col3 from " +
                "locations inner join emps on locations.locationid = emps.locationid where emps.locationid= 10 " +
                "group by empid,emps.locationid");
    }

    @Test
    public void testsInnerJoinCompleteWithPredicates2() {
        String mv = "select locations.name, locations.locationid, sum(emps.deptno) as col3 from emps " +
                "join locations on emps.locationid = locations.locationid group by locations.name,locations.locationid";
        testRewriteOK(mv, "select locations.name, emps.locationid, sum(emps.deptno) as col3 from " +
                "locations inner join emps on locations.locationid = emps.locationid where emps.locationid= 10 " +
                "group by locations.name,emps.locationid");
        testRewriteOK(mv, "select locations.name, emps.locationid, sum(emps.deptno) as col3 from " +
                "locations inner join emps on locations.locationid = emps.locationid where locations.locationid>10 " +
                "group by locations.name,emps.locationid");
    }

    @Test
    public void testMultiInnerJoinQueryDelta() {
        String mv = "select deptno as col1, empid as col2, locations.locationid as col3 " +
                "from emps join locations on emps.locationid = locations.locationid";
        testRewriteOK(mv, "select count(*)  from emps join locations " +
                "join depts on emps.locationid = locations.locationid and depts.deptno = emps.deptno");
    }

    @Test
    public void testMultiInnerJoinViewDelta() {
        String mv = "select emps.deptno as col1, empid as col2, locations.locationid as col3 " +
                "from emps join locations join depts " +
                "on emps.locationid = locations.locationid and depts.deptno = emps.deptno";
        testRewriteOK(mv, "select deptno as col1, empid as col2, locations.locationid as col3 " +
                "from emps join locations on emps.locationid = locations.locationid");
    }

    @Test
    public void testSwapOuterJoin() {
        for (String joinType : outerJoinTypes) {
            String mv = "select count(*) as col1 from " +
                    "emps " + joinType + " join locations on emps.locationid = locations.locationid";
            testRewriteOK(mv, "select count(*)  + 1 from " +
                    "emps " + joinType + " join locations on emps.locationid = locations.locationid");

            // outer join cannot swap join orders
            testRewriteFail(mv, "select count(*) as col1 from " +
                    "locations " + joinType + " join emps on emps.locationid = locations.locationid");
            testRewriteFail(mv, "select count(*) + 1 as col1 from " +
                    "locations " + joinType + " join emps on emps.locationid = locations.locationid");

            // outer join cannot change join type
            if (joinType.equalsIgnoreCase("right")) {
                testRewriteFail(mv, "select count(*)  as col1 from " +
                        "emps left join locations on emps.locationid = locations.locationid");
            } else {
                testRewriteFail(mv, "select count(*) as col1 from " +
                        "emps right join locations on emps.locationid = locations.locationid");
            }
        }
    }

    @Test
    public void testLeftOuterJoinQueryComplete() {
        String mv = "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                " left join locations on emps.locationid = locations.locationid";

        testRewriteOK(mv, "select count(*) from " +
                "emps  left join locations on emps.locationid = locations.locationid");
        testRewriteOK(mv, "select empid as col2, emps.locationid from " +
                "emps  left join locations on emps.locationid = locations.locationid " +
                "where emps.deptno > 10");
        testRewriteOK(mv, "select count(*) from " +
                "emps  left join locations on emps.locationid = locations.locationid " +
                "where emps.deptno > 10");

        testRewriteOK(mv, "select empid as col2, emps.locationid from " +
                "emps left join locations on emps.locationid = locations.locationid " +
                "where emps.locationid > 10");
        // no locations.locationid in mv
        testRewriteFail(mv, "select empid as col2, locations.locationid from " +
                "emps left join locations on emps.locationid = locations.locationid " +
                "where locations.locationid > 10");
        testRewriteFail(mv, "select empid as col2, locations.locationid from " +
                "emps inner join locations on emps.locationid = locations.locationid " +
                "and locations.locationid > 10");
        testRewriteFail(mv, "select empid as col2, locations.locationid from " +
                "emps inner join locations on emps.locationid = locations.locationid " +
                "and emps.locationid > 10");
    }

    @Test
    public void testRightOuterJoinQueryComplete() {
        String mv = "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                " right join locations on emps.locationid = locations.locationid";
        testRewriteOK(mv, "select count(*) from " +
                "emps  right join locations on emps.locationid = locations.locationid");
        // TODO: Query's right outer join will be converted to Inner Join.
        testRewriteOK(mv, "select empid as col2, emps.locationid from " +
                "emps  right join locations on emps.locationid = locations.locationid " +
                "where emps.deptno > 10");
        // TODO: Query's right outer join will be converted to Inner Join.
        testRewriteOK(mv, "select count(*) from " +
                "emps  right join locations on emps.locationid = locations.locationid " +
                "where emps.deptno > 10");
        testRewriteFail(mv, "select empid as col2, locations.locationid from " +
                "emps  right join locations on emps.locationid = locations.locationid " +
                "where locations.locationid > 10");
        testRewriteOK(mv, "select empid as col2, locations.locationid from " +
                "emps inner join locations on emps.locationid = locations.locationid " +
                "and locations.locationid > 10");
        testRewriteOK(mv, "select empid as col2, locations.locationid from " +
                "emps inner join locations on emps.locationid = locations.locationid " +
                "and emps.locationid > 10");
    }

    @Test
    public void testMultiOuterJoinQueryDelta() {
        for (String joinType : outerJoinTypes) {
            String mv = "select deptno as col1, empid as col2, locations.locationid as col3 from emps " +
                    "" + joinType + " join locations on emps.locationid = locations.locationid";
            testRewriteOK(mv, "select count(*)  from emps " +
                    "" + joinType + " join locations on emps.locationid = locations.locationid " +
                    "" + joinType + " join depts on depts.deptno = emps.deptno");
        }
    }

    @Test
    public void testAggregateBasic() {
        String mv = "select deptno as col1, locationid + 1 as b, count(*) as c, sum(empid) as s " +
                "from emps group by locationid + 1, deptno";
        testRewriteOK(mv, "select count(*) as c, deptno from emps group by deptno");
        testRewriteOK(mv, "select count(*) as c, locationid + 1 from emps group by locationid + 1");
    }

    @Test
    public void testAggregate0() {
        testRewriteOK("select deptno, count(*) as c, empid + 2 as d, sum(empid) as s "
                        + "from emps group by empid, deptno",
                "select count(*) + 1 as c, deptno from emps group by deptno");
    }

    @Test
    public void testAggregate1() {
        testRewriteOK("select empid, deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps group by empid, deptno",
                "select deptno from emps group by deptno");
    }

    @Test
    public void testAggregate2() {
        testRewriteOK("select empid, deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps group by empid, deptno",
                "select deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps group by deptno");
    }

    @Test
    public void testAggregate3() {
        testRewriteOK("select empid, deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps group by empid, deptno",
                "select deptno, empid, sum(empid) as s, count(*) as c\n"
                        + "from emps group by empid, deptno");
    }

    @Test
    public void testAggregate4() {
        testRewriteOK("select empid, deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps where deptno >= 10 group by empid, deptno",
                "select deptno, sum(empid) as s\n"
                        + "from emps where deptno > 10 group by deptno");
    }

    @Test
    public void testAggregate5() {
        testRewriteOK("select empid, deptno, count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps where deptno >= 10 group by empid, deptno",
                "select deptno, sum(empid) + 1 as s\n"
                        + "from emps where deptno > 10 group by deptno");
    }

    @Test
    public void testAggregate6() {
        testRewriteOK("select empid, deptno, count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps where deptno >= 10 group by empid, deptno",
                "select deptno + 1, sum(empid) + 1 as s\n"
                        + "from emps where deptno > 10 group by deptno");
    }

    @Test
    public void testAggregate7() {

        testRewriteFail("select empid, deptno, count(*) + 1 as c, sum(empid) + 2 as s\n"
                        + "from emps where deptno >= 10 group by empid, deptno",
                "select deptno, sum(empid) + 1 as s\n"
                        + "from emps where deptno > 10 group by deptno");
    }

    @Test
    public void testAggregate8() {
        //TODO: Support deptno + 1 rewrite to deptno (considering typecasting)
        testRewriteFail("select empid, deptno + 1, count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps where deptno >= 10 group by empid, deptno",
                "select deptno + 1, sum(empid) + 1 as s\n"
                        + "from emps where deptno > 10 group by deptno");

        testRewriteOK("select empid, deptno + 1, count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps_bigint where deptno >= 10 group by empid, deptno",
                "select deptno + 1, sum(empid) + 1 as s\n"
                        + "from emps_bigint where deptno > 10 group by deptno");
    }

    @Test
    public void testAggregate9() {
        testRewriteOK("select sum(salary) as col1, count(salary) + 1 from emps",
                "select sum(salary), count(salary) + 1 from emps");
        testRewriteFail("select empid, deptno," +
                        " sum(salary) as total, count(salary) + 1 as cnt" +
                        " from emps group by empid, deptno ",
                "select sum(salary), count(salary) + 1 from emps");
    }

    @Test
    public void testAggregate10() {
        String mv = "select empid, deptno,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid, deptno ";
        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid, deptno having sum(salary) > 10");
        testRewriteOK(mv, "select empid,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid having sum(salary) > 10");
    }

    @Test
    public void testAggregate11() throws Exception {
        String mv = "CREATE MATERIALIZED VIEW IF NOT EXISTS test_mv1\n" +
                "DISTRIBUTED BY HASH(col1) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\"\n" +
                ")" +
                "as select " +
                " col1,col2, col3,\n" +
                "      sum(col4) as sum_amt\n" +
                "    from\n" +
                "      test_agg_with_having_tbl p1\n" +
                "    group by\n" +
                "      1, 2, 3";
        starRocksAssert.withMaterializedView(mv);
        sql("select col1 from test_agg_with_having_tbl p1\n" +
                "    where p1.col2 = '02' and p1.col3 = \"2023-03-31\"\n" +
                "    group by 1\n" +
                "    having sum(p1.col4) >= 500000\n")
                .contains("test_mv1");
    }

    @Test
    public void testAggregate12() {
        String mv = "select empid, deptno,\n" +
                " sum(salary) as total, count(salary) as cnt \n" +
                " from emps group by empid, deptno ";
        // count(salary): salary is nullable
        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteOK(mv, "select empid,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid");
        testRewriteFail(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(1)  as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteFail(mv, "select empid,\n" +
                " sum(salary) as total, count(1)  as cnt\n" +
                " from emps group by empid");
    }

    @Test
    public void testAggregate13() {
        String mv = "select empid, deptno,\n" +
                " sum(salary) as total, count(empid) as cnt \n" +
                " from emps group by empid, deptno ";
        // count(empid): empid is not nullable
        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(empid)  as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteOK(mv, "select empid,\n" +
                " sum(salary) as total, count(empid)  as cnt\n" +
                " from emps group by empid");
        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(1) as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteOK(mv, "select empid,\n" +
                " sum(salary) as total, count(1)  as cnt\n" +
                " from emps group by empid");
        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(*) as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteOK(mv, "select empid,\n" +
                " sum(salary) as total, count(*)  as cnt\n" +
                " from emps group by empid");
    }

    @Test
    public void testAggregate14() {
        String mv = "select empid, deptno,\n" +
                " sum(salary) as total, count(1)  as cnt\n" +
                " from emps group by empid, deptno ";
        // count(salary): salary is nullable
        testRewriteFail(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteFail(mv, "select empid,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid");

        // count(empid): empid is not nullable
        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(empid)  as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteOK(mv, "select empid,\n" +
                " sum(salary) as total, count(empid)  as cnt\n" +
                " from emps group by empid");

        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(1)  as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteOK(mv, "select empid,\n" +
                " sum(salary) as total, count(1)  as cnt\n" +
                " from emps group by empid");
    }

    @Test
    public void testAggregate15() {
        String mv = "select empid, deptno,\n" +
                " sum(salary) as total, count(*)  as cnt\n" +
                " from emps group by empid, deptno ";
        // count(salary): salary is nullable
        testRewriteFail(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteFail(mv, "select empid,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid");

        // count(empid): empid is not nullable
        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(empid)  as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteOK(mv, "select empid,\n" +
                " sum(salary) as total, count(empid)  as cnt\n" +
                " from emps group by empid");

        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(1)  as cnt\n" +
                " from emps group by empid, deptno");
        testRewriteOK(mv, "select empid,\n" +
                " sum(salary) as total, count(1)  as cnt\n" +
                " from emps group by empid");
    }

    @Test
    public void testAggregate16() throws Exception {
        String mv = "CREATE MATERIALIZED VIEW IF NOT EXISTS test_mv1\n" +
                "DISTRIBUTED BY HASH(col1) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\"\n" +
                ")" +
                "as select " +
                " col1,col2, col3,\n" +
                "      sum(col4) as sum_amt\n" +
                "    from\n" +
                "      test_agg_with_having_tbl p1\n" +
                "    group by\n" +
                "      1, 2, 3";
        starRocksAssert.withMaterializedView(mv);
        {
            sql("select col1 from test_agg_with_having_tbl p1\n" +
                    "    where p1.col2 = '02' and p1.col3 = \"2023-03-31\"\n" +
                    "    group by 1\n" +
                    "    having sum(p1.col4) >= 500000\n")
                    .notContain("AGGREGATE")
                    .contains("PREDICATES: 10: sum_amt >= 500000, 8: col2 = '02', 9: col3 = '2023-03-31'\n" +
                            "     partitions=1/1\n" +
                            "     rollup: test_mv1");
        }
        {
            sql("select col1, col2 from test_agg_with_having_tbl p1\n" +
                    "    where p1.col3 = \"2023-03-31\"\n" +
                    "    group by 1, 2\n" +
                    "    having sum(p1.col4) >= 500000\n")
                    .notContain("AGGREGATE")
                    .contains("PREDICATES: 10: sum_amt >= 500000, 9: col3 = '2023-03-31'\n" +
                            "     partitions=1/1\n" +
                            "     rollup: test_mv1");

        }
        {
            sql("select col1, col2 from test_agg_with_having_tbl p1\n" +
                    "    group by 1, 2\n" +
                    "    having sum(p1.col4) >= 500000\n")
                    .contains(":AGGREGATE (update finalize)\n" +
                            "  |  output: sum(10: sum_amt)\n" +
                            "  |  group by: 7: col1, 8: col2\n" +
                            "  |  having: 11: sum >= 500000\n" +
                            "  |  \n" +
                            "  0:OlapScanNode\n" +
                            "     TABLE: test_mv1\n" +
                            "     PREAGGREGATION: ON\n" +
                            "     partitions=1/1\n" +
                            "     rollup: test_mv1");

        }
    }

    @Test
    public void testAggregateWithAggExpr() {
        // support agg expr: empid -> abs(empid)
        testRewriteOK("select empid, deptno," +
                        " sum(salary) as total, count(salary) + 1 as cnt" +
                        " from emps group by empid, deptno ",
                "select abs(empid), sum(salary) from emps group by empid");
        testRewriteOK("select empid, deptno," +
                        " sum(salary) as total, count(salary) + 1 as cnt" +
                        " from emps group by empid, deptno ",
                "select abs(empid), sum(salary) from emps group by empid, deptno");
    }

    @Test
    public void testAggregateWithGroupByKeyExpr1() {
        testRewriteOK("select empid, deptno," +
                        " sum(salary) as total, count(salary) + 1 as cnt" +
                        " from emps group by empid, deptno ",
                "select abs(empid), sum(salary) from emps group by abs(empid), deptno")
                .contains("  0:OlapScanNode\n" +
                        "     TABLE: mv0")
                .contains("2:AGGREGATE (update serialize)\n" +
                        "  |  STREAMING\n" +
                        "  |  output: sum(11: total)\n" +
                        "  |  group by: 14: abs, 10: deptno\n" +
                        "  |  \n" +
                        "  1:Project\n" +
                        "  |  <slot 10> : 10: deptno\n" +
                        "  |  <slot 11> : 11: total\n" +
                        "  |  <slot 14> : abs(9: empid)");
    }

    @Test
    public void testAggregateWithGroupByKeyExpr2() {
        testRewriteOK("select empid, deptno," +
                        " sum(salary) as total, " +
                        " min(salary) as min_total, " +
                        " max(salary) as max_total, " +
                        " count(salary) + 1 as cnt" +
                        " from emps group by empid, deptno ",
                "select abs(empid), sum(salary) from emps group by abs(empid), deptno")
                .contains("  0:OlapScanNode\n" +
                        "     TABLE: mv0")
                // only contains required columns from mv scan operator.
                .contains("  2:AGGREGATE (update serialize)\n" +
                        "  |  STREAMING\n" +
                        "  |  output: sum(11: total)\n" +
                        "  |  group by: 16: abs, 10: deptno\n" +
                        "  |  \n" +
                        "  1:Project\n" +
                        "  |  <slot 10> : 10: deptno\n" +
                        "  |  <slot 11> : 11: total\n" +
                        "  |  <slot 16> : abs(9: empid)");
    }

    @Test
    public void testAggregateWithContGroupByKey() {
        testRewriteOK("select empid, floor(cast('1997-01-20 12:34:56' as DATETIME)), "
                        + "count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps\n"
                        + "group by empid, floor(cast('1997-01-20 12:34:56' as DATETIME))",
                "select floor(cast('1997-01-20 12:34:56' as DATETIME)), sum(empid) as s\n"
                        + "from emps group by floor(cast('1997-01-20 12:34:56' as DATETIME))");
    }

    @Test
    public void testAggregateRollup() {
        String mv = "select deptno, count(*) as c, sum(empid) as s from emps group by locationid, deptno";
        testRewriteOK(mv, "select count(*) as c, deptno from emps group by deptno");
        testRewriteOK(mv, "select sum(empid), count(*) as c, deptno from emps group by deptno");
        testRewriteOK(mv, "select count(*) + 1 as c, deptno from emps group by deptno");
    }

    @Test
    public void testsInnerJoinAggregate() {
        String mv = "select count(*) as col1, emps.deptno from " +
                "emps join locations on emps.locationid = locations.locationid + 1 " +
                "group by emps.deptno";
        testRewriteOK(mv, "select count(*) from " +
                "locations join emps on emps.locationid = locations.locationid + 1 " +
                "group by emps.deptno");
        testRewriteOK(mv, "select count(*) , emps.deptno from " +
                "locations join emps on emps.locationid = locations.locationid + 1 " +
                "where emps.deptno > 10 " +
                "group by emps.deptno");
    }

    @Test
    public void testUnionAll0() {
        String mv = "select deptno as col1, count(*) as c, sum(empid) as s from emps group by locationid + 1, deptno";
        testRewriteOK(mv, "select count(*) as c, deptno from emps group by deptno " +
                "union all " +
                "select count(*) as c, deptno from emps where deptno > 10 group by deptno ");
    }

    @Test
    public void testAggregateProject() {
        testRewriteOK("select deptno, count(*) as c, empid + 2, sum(empid) as s "
                        + "from emps group by empid, deptno",
                "select count(*) as c, deptno from emps group by deptno");
        testRewriteOK("select deptno, count(*) as c, empid + 2, sum(empid) as s "
                        + "from emps group by empid, deptno",
                "select count(*) + 1 as c, deptno from emps group by deptno");
    }

    @Test
    public void testAggregateMaterializationNoAggregateFuncs1() {
        testRewriteOK("select empid, deptno from emps group by empid, deptno",
                "select empid, deptno from emps group by empid, deptno");
    }

    @Test
    public void testAggregateMaterializationNoAggregateFuncs2() {
        testRewriteOK("select empid, deptno from emps group by empid, deptno",
                "select deptno from emps group by deptno");
    }

    @Test
    public void testAggregateMaterializationNoAggregateFuncs3() {
        testRewriteFail("select deptno from emps group by deptno",
                "select empid, deptno from emps group by empid, deptno");
    }

    @Test
    public void testAggregateMaterializationNoAggregateFuncs4() {
        testRewriteOK("select empid, deptno\n"
                        + "from emps where deptno = 10 group by empid, deptno",
                "select deptno from emps where deptno = 10 group by deptno");
    }

    @Test
    public void testAggregateMaterializationNoAggregateFuncs5() {
        testRewriteFail("select empid, deptno\n"
                        + "from emps where deptno = 5 group by empid, deptno",
                "select deptno from emps where deptno = 10 group by deptno");
    }

    @Test
    public void testAggregateMaterializationNoAggregateFuncs6() {
        testRewriteOK("select empid, deptno\n"
                        + "from emps where deptno > 5 group by empid, deptno",
                "select deptno from emps where deptno > 10 group by deptno");
    }

    @Test
    public void testAggregateMaterializationNoAggregateFuncs7() {
        testRewriteFail("select empid, deptno\n"
                        + "from emps where deptno > 5 group by empid, deptno",
                "select deptno from emps where deptno < 10 group by deptno");
    }

    @Test
    public void testAggregateMaterializationNoAggregateFuncs8() {
        testRewriteFail("select empid from emps group by empid, deptno",
                "select deptno from emps group by deptno");
    }

    @Test
    public void testAggregateMaterializationNoAggregateFuncs9() {
        testRewriteFail("select empid, deptno from emps\n"
                        + "where salary > 1000 group by name, empid, deptno",
                "select empid from emps\n"
                        + "where salary > 2000 group by name, empid");
    }

    @Test
    public void testAggregateMaterializationAggregateFuncs1() {
        testRewriteOK("select empid, deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps group by empid, deptno",
                "select deptno from emps group by deptno");
    }

    @Test
    public void testAggregateMaterializationAggregateFuncs2() {
        testRewriteOK("select empid, deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps group by empid, deptno",
                "select deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps group by deptno");
    }

    @Test
    public void testAggregateMaterializationAggregateFuncs3() {
        testRewriteOK("select empid, deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps group by empid, deptno",
                "select deptno, empid, sum(empid) as s, count(*) as c\n"
                        + "from emps group by empid, deptno");
    }

    @Test
    public void testAggregateMaterializationAggregateFuncs4() {
        testRewriteOK("select empid, deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps where deptno >= 10 group by empid, deptno",
                "select deptno, sum(empid) as s\n"
                        + "from emps where deptno > 10 group by deptno");
    }

    @Test
    public void testAggregateMaterializationAggregateFuncs5() {
        testRewriteOK("select empid, deptno, count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps where deptno >= 10 group by empid, deptno",
                "select deptno, sum(empid) + 1 as s\n"
                        + "from emps where deptno > 10 group by deptno");
    }

    @Test
    public void testAggregateMaterializationAggregateFuncs6() {
        testRewriteFail("select empid, deptno, count(*) + 1 as c, sum(empid) + 2 as s\n"
                        + "from emps where deptno >= 10 group by empid, deptno",
                "select deptno, sum(empid) + 1 as s\n"
                        + "from emps where deptno > 10 group by deptno");
    }

    @Test
    public void testAggregateMaterializationAggregateFuncs7() {
        testRewriteOK("select empid, deptno, count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps where deptno >= 10 group by empid, deptno",
                "select deptno + 1, sum(empid) + 1 as s\n"
                        + "from emps where deptno > 10 group by deptno");
    }

    @Test
    public void testAggregateMaterializationAggregateFuncs8() {
        //TODO: Support deptno + 1 rewrite to deptno (considering typecasting)
        testRewriteFail("select empid, deptno + 1, count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps where deptno >= 10 group by empid, deptno",
                "select deptno + 1, sum(empid) + 1 as s\n"
                        + "from emps where deptno > 10 group by deptno");

        testRewriteOK("select empid, deptno + 1 as col, count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps_bigint where deptno >= 10 group by empid, deptno",
                "select deptno + 1, sum(empid) + 1 as s\n"
                        + "from emps_bigint where deptno > 10 group by deptno").contains("col - 1");
    }

    @Test
    public void testAggregateMaterializationAggregateFuncsWithConstGroupByKeys() {
        testRewriteOK("select empid, floor(cast('1997-01-20 12:34:56' as DATETIME)), "
                        + "count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps\n"
                        + "group by empid, floor(cast('1997-01-20 12:34:56' as DATETIME))",
                "select floor(cast('1997-01-20 12:34:56' as DATETIME)), sum(empid) as s\n"
                        + "from emps group by floor(cast('1997-01-20 12:34:56' as DATETIME))");
        testRewriteOK("select empid, floor(cast('1997-01-20 12:34:56' as DATETIME)), "
                        + "count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps\n"
                        + "group by empid, floor(cast('1997-01-20 12:34:56' as DATETIME))",
                "select floor(cast('1997-01-20 12:34:56' as DATETIME)), sum(empid) + 1 as s\n"
                        + "from emps group by floor(cast('1997-01-20 12:34:56' as DATETIME))");
        testRewriteOK("select empid, floor(cast('1997-01-20 12:34:56' as DATETIME)), "
                        + "count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps\n"
                        + "group by empid, floor(cast('1997-01-20 12:34:56' as DATETIME))",
                "select floor(cast('1997-01-20 12:34:56' as DATETIME) ), sum(empid) as s\n"
                        + "from emps group by floor(cast('1997-01-20 12:34:56' as DATETIME) )");
        testRewriteOK("select empid, floor(cast('1997-01-20 12:34:56' as DATETIME)), "
                        + "count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps\n"
                        + "group by empid, floor(cast('1997-01-20 12:34:56' as DATETIME))",
                "select floor(cast('1997-01-20 12:34:56' as DATETIME)), sum(empid) as s\n"
                        + "from emps group by floor(cast('1997-01-20 12:34:56' as DATETIME))");
        testRewriteOK("select empid, cast('1997-01-20 12:34:56' as DATETIME), "
                        + "count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps\n"
                        + "group by empid, cast('1997-01-20 12:34:56' as DATETIME)",
                "select floor(cast('1997-01-20 12:34:56' as DATETIME)), sum(empid) as s\n"
                        + "from emps group by floor(cast('1997-01-20 12:34:56' as DATETIME))");
        testRewriteOK("select empid, floor(cast('1997-01-20 12:34:56' as DATETIME)), "
                        + "count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps\n"
                        + "group by empid, floor(cast('1997-01-20 12:34:56' as DATETIME))",
                "select floor(cast('1997-01-20 12:34:56' as DATETIME)), sum(empid) as s\n"
                        + "from emps group by floor(cast('1997-01-20 12:34:56' as DATETIME))");
        //        testRewriteOK("select eventid, floor(cast(ts as DATETIME)), "
        //                        + "count(*) + 1 as c, sum(eventid) as s\n"
        //                        + "from events group by eventid, floor(cast(ts as DATETIME))",
        //                "select floor(cast(ts as DATETIME) ), sum(eventid) as s\n"
        //                        + "from events group by floor(cast(ts as DATETIME) )");
        //        testRewriteOK("select eventid, cast(ts as DATETIME), count(*) + 1 as c, sum(eventid) as s\n"
        //                        + "from events group by eventid, cast(ts as DATETIME)",
        //                "select floor(cast(ts as DATETIME)), sum(eventid) as s\n"
        //                        + "from events group by floor(cast(ts as DATETIME))");
        //        testRewriteOK("select eventid, floor(cast(ts as DATETIME)), "
        //                        + "count(*) + 1 as c, sum(eventid) as s\n"
        //                        + "from events group by eventid, floor(cast(ts as DATETIME))",
        //                "select floor(cast(ts as DATETIME)), sum(eventid) as s\n"
        //                        + "from events group by floor(cast(ts as DATETIME))");
    }

    @Test
    public void testAggregateMaterializationAggregateFuncs18() {
        testRewriteOK("select empid, deptno, count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps group by empid, deptno",
                "select empid*deptno, sum(empid) as s\n"
                        + "from emps group by empid*deptno");
    }


    @Test
    public void testAggregateMaterializationAggregateFuncs19() {
        testRewriteOK("select empid, deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps group by empid, deptno",
                "select empid + 10, count(*) + 1 as c\n"
                        + "from emps group by empid + 10");
    }

    @Test
    public void testJoinAggregateMaterializationNoAggregateFuncs1() {
        // If agg push down is open, cannot rewrite.
        testRewriteOK("select empid, depts.deptno from emps\n"
                        + "join depts using (deptno) where depts.deptno > 10\n"
                        + "group by empid, depts.deptno",
                "select empid from emps\n"
                        + "join depts using (deptno) where depts.deptno > 20\n"
                        + "group by empid, depts.deptno");
    }

    @Test
    public void testJoinAggregateMaterializationNoAggregateFuncs2() {
        testRewriteOK("select depts.deptno, empid from depts\n"
                        + "join emps using (deptno) where depts.deptno > 10\n"
                        + "group by empid, depts.deptno",
                "select empid from emps\n"
                        + "join depts using (deptno) where depts.deptno > 20\n"
                        + "group by empid, depts.deptno");
    }

    @Test
    public void testJoinAggregateMaterializationNoAggregateFuncs3() {
        // It does not match, Project on top of query
        testRewriteFail("select empid from emps\n"
                        + "join depts using (deptno) where depts.deptno > 10\n"
                        + "group by empid, depts.deptno",
                "select empid from emps\n"
                        + "join depts using (deptno) where depts.deptno > 20\n"
                        + "group by empid, depts.deptno");
    }

    @Test
    public void testJoinAggregateMaterializationNoAggregateFuncs4() {
        testRewriteOK("select empid, depts.deptno from emps\n"
                        + "join depts using (deptno) where emps.deptno > 10\n"
                        + "group by empid, depts.deptno",
                "select empid from emps\n"
                        + "join depts using (deptno) where depts.deptno > 20\n"
                        + "group by empid, depts.deptno");
    }

    @Test
    public void testJoinAggregateMaterializationNoAggregateFuncs5() {
        testRewriteOK("select depts.deptno, emps.empid from depts\n"
                        + "join emps using (deptno) where emps.empid > 10\n"
                        + "group by depts.deptno, emps.empid",
                "select depts.deptno from depts\n"
                        + "join emps using (deptno) where emps.empid > 15\n"
                        + "group by depts.deptno, emps.empid");
    }

    @Test
    public void testJoinAggregateMaterializationNoAggregateFuncs6() {
        testRewriteOK("select depts.deptno, emps.empid from depts\n"
                        + "join emps using (deptno) where emps.empid > 10\n"
                        + "group by depts.deptno, emps.empid",
                "select depts.deptno from depts\n"
                        + "join emps using (deptno) where emps.empid > 15\n"
                        + "group by depts.deptno");
    }

    @Test
    public void testJoinAggregateMaterializationNoAggregateFuncs7() {
        testRewriteOK("select depts.deptno, dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 11\n"
                        + "group by depts.deptno, dependents.empid",
                "select dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 10\n"
                        + "group by dependents.empid");
    }

    @Test
    public void testJoinAggregateMaterializationNoAggregateFuncs8() {
        testRewriteFail("select depts.deptno, dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 20\n"
                        + "group by depts.deptno, dependents.empid",
                "select dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 10 and depts.deptno < 20\n"
                        + "group by dependents.empid");
    }

    @Test
    public void testJoinAggregateMaterializationNoAggregateFuncs9() {
        testRewriteOK("select depts.deptno, dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 11 and depts.deptno < 19\n"
                        + "group by depts.deptno, dependents.empid",
                "select dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 10 and depts.deptno < 20\n"
                        + "group by dependents.empid");
    }

    @Test
    public void testJoinAggregateMaterializationNoAggregateFuncs10() {
        testRewriteOK("select depts.name, dependents.name as name2, "
                        + "emps.deptno, depts.deptno as deptno2, "
                        + "dependents.empid\n"
                        + "from depts, dependents, emps\n"
                        + "where depts.deptno > 10\n"
                        + "group by depts.name, dependents.name, "
                        + "emps.deptno, depts.deptno, "
                        + "dependents.empid",
                "select dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 10\n"
                        + "group by dependents.empid");
    }


    @Test
    public void testJoinAggregateMaterializationAggregateFuncs1() {
        testRewriteOK("select empid, depts.deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps join depts using (deptno)\n"
                        + "group by empid, depts.deptno",
                "select deptno from emps group by deptno");
    }

    @Test
    public void testJoinAggregateMaterializationAggregateFuncs2() {
        testRewriteOK("select empid, emps.deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps join depts using (deptno)\n"
                        + "group by empid, emps.deptno",
                "select depts.deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps join depts using (deptno)\n"
                        + "group by depts.deptno");
    }

    @Test
    public void testJoinAggregateMaterializationAggregateFuncs3() {
        testRewriteOK("select empid, depts.deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps join depts using (deptno)\n"
                        + "group by empid, depts.deptno",
                "select deptno, empid, sum(empid) as s, count(*) as c\n"
                        + "from emps group by empid, deptno");
    }

    @Test

    public void testJoinAggregateMaterializationAggregateFuncs4() {
        testRewriteOK("select empid, emps.deptno, count(*) as c, sum(empid) as s\n"
                        + "from emps join depts using (deptno)\n"
                        + "where emps.deptno >= 10 group by empid, emps.deptno",
                "select depts.deptno, sum(empid) as s\n"
                        + "from emps join depts using (deptno)\n"
                        + "where emps.deptno > 10 group by depts.deptno");
    }

    @Test

    public void testJoinAggregateMaterializationAggregateFuncs5() {
        testRewriteOK("select empid, depts.deptno, count(*) + 1 as c, sum(empid) as s\n"
                        + "from emps join depts using (deptno)\n"
                        + "where depts.deptno >= 10 group by empid, depts.deptno",
                "select depts.deptno, sum(empid) + 1 as s\n"
                        + "from emps join depts using (deptno)\n"
                        + "where depts.deptno > 10 group by depts.deptno");
    }

    @Test
    @Disabled
    public void testJoinAggregateMaterializationAggregateFuncs6() {
        final String m = "select depts.name, sum(salary) as s\n"
                + "from emps\n"
                + "join depts on (emps.deptno = depts.deptno)\n"
                + "group by depts.name";
        final String q = "select dependents.empid, sum(salary) as s\n"
                + "from emps\n"
                + "join depts on (emps.deptno = depts.deptno)\n"
                + "join dependents on (depts.name = dependents.name)\n"
                + "group by dependents.empid";
        testRewriteOK(m, q);
    }

    @Test
    @Disabled
    public void testJoinAggregateMaterializationAggregateFuncs7() {
        testRewriteOK("select dependents.empid, emps.deptno, sum(salary) as s\n"
                        + "from emps\n"
                        + "join dependents on (emps.empid = dependents.empid)\n"
                        + "group by dependents.empid, emps.deptno",
                "select dependents.empid, sum(salary) as s\n"
                        + "from emps\n"
                        + "join depts on (emps.deptno = depts.deptno)\n"
                        + "join dependents on (emps.empid = dependents.empid)\n"
                        + "group by dependents.empid");
    }

    @Test
    @Disabled
    public void testJoinAggregateMaterializationAggregateFuncs8() {
        testRewriteOK("select dependents.empid, emps.deptno, sum(salary) as s\n"
                        + "from emps\n"
                        + "join dependents on (emps.empid = dependents.empid)\n"
                        + "group by dependents.empid, emps.deptno",
                "select depts.name, sum(salary) as s\n"
                        + "from emps\n"
                        + "join depts on (emps.deptno = depts.deptno)\n"
                        + "join dependents on (emps.empid = dependents.empid)\n"
                        + "group by depts.name");
    }

    @Test
    public void testJoinAggregateMaterializationAggregateFuncs9() {
        testRewriteOK("select dependents.empid, emps.deptno, count(distinct salary) as s\n"
                        + "from emps\n"
                        + "join dependents on (emps.empid = dependents.empid)\n"
                        + "group by dependents.empid, emps.deptno",
                "select emps.deptno, count(distinct salary) as s\n"
                        + "from emps\n"
                        + "join dependents on (emps.empid = dependents.empid)\n"
                        + "group by dependents.empid, emps.deptno");
    }

    @Test
    public void testJoinAggregateMaterializationAggregateFuncs10() {
        testRewriteFail("select dependents.empid, emps.deptno, count(distinct salary) as s\n"
                        + "from emps\n"
                        + "join dependents on (emps.empid = dependents.empid)\n"
                        + "group by dependents.empid, emps.deptno",
                "select emps.deptno, count(distinct salary) as s\n"
                        + "from emps\n"
                        + "join dependents on (emps.empid = dependents.empid)\n"
                        + "group by emps.deptno");
    }

    @Test
    @Disabled
    public void testJoinAggregateMaterializationAggregateFuncs11() {
        testRewriteOK("select depts.deptno, dependents.empid, count(emps.salary) as s\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 11 and depts.deptno < 19\n"
                        + "group by depts.deptno, dependents.empid",
                "select dependents.empid, count(emps.salary) + 1\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 10 and depts.deptno < 20\n"
                        + "group by dependents.empid");
    }

    @Test
    public void testJoinAggregateMaterializationAggregateFuncs12() {
        testRewriteFail("select depts.deptno, dependents.empid, "
                        + "count(distinct emps.salary) as s\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 11 and depts.deptno < 19\n"
                        + "group by depts.deptno, dependents.empid",
                "select dependents.empid, count(distinct emps.salary) + 1\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 10 and depts.deptno < 20\n"
                        + "group by dependents.empid");
    }

    @Test
    public void testJoinAggregateMaterializationAggregateFuncs13() {
        testRewriteFail("select dependents.empid, emps.deptno, count(distinct salary) as s\n"
                        + "from emps\n"
                        + "join dependents on (emps.empid = dependents.empid)\n"
                        + "group by dependents.empid, emps.deptno",
                "select emps.deptno, count(salary) as s\n"
                        + "from emps\n"
                        + "join dependents on (emps.empid = dependents.empid)\n"
                        + "group by dependents.empid, emps.deptno");
    }

    @Test
    @Disabled
    public void testJoinAggregateMaterializationAggregateFuncs14() {
        testRewriteOK("select empid, emps.name, emps.deptno, depts.name, "
                        + "count(*) as c, sum(empid) as s\n"
                        + "from emps join depts using (deptno)\n"
                        + "where (depts.name is not null and emps.name = 'a') or "
                        + "(depts.name is not null and emps.name = 'b')\n"
                        + "group by empid, emps.name, depts.name, emps.deptno",
                "select depts.deptno, sum(empid) as s\n"
                        + "from emps join depts using (deptno)\n"
                        + "where depts.name is not null and emps.name = 'a'\n"
                        + "group by depts.deptno");
    }

    @Test
    @Disabled
    public void testJoinAggregateMaterializationAggregateFuncs15() {
        final String m = ""
                + "SELECT deptno,\n"
                + "  COUNT(*) AS dept_size,\n"
                + "  SUM(salary) AS dept_budget\n"
                + "FROM emps\n"
                + "GROUP BY deptno";
        final String q = ""
                + "SELECT FLOOR(CREATED_AT TO YEAR) AS by_year,\n"
                + "  COUNT(*) AS num_emps\n"
                + "FROM (SELECTdeptno\n"
                + "    FROM emps) AS t\n"
                + "JOIN (SELECT deptno,\n"
                + "        inceptionDate as CREATED_AT\n"
                + "    FROM depts2) using (deptno)\n"
                + "GROUP BY FLOOR(CREATED_AT TO YEAR)";
        testRewriteOK(m, q);
    }

    @Test
    public void testJoinMaterialization1() {
        String q = "select depts.deptno, empid, locationid, t.name \n"
                + "from (select * from emps where empid < 300) t \n"
                + "join depts using (deptno)";
        testRewriteOK("select deptno, empid, locationid, name from emps where empid < 500", q);
    }

    @Test
    public void testJoinMaterialization2() {
        String q = "select *\n"
                + "from emps\n"
                + "join depts using (deptno)";
        String m = "select deptno, empid, locationid, name,\n"
                + "salary, commission from emps";
        testRewriteOK(m, q);
    }

    @Test
    public void testJoinMaterializationStar() {
        String q = "select *\n"
                + "from emps\n"
                + "join depts using (deptno)";
        String m = "select * from emps";
        testRewriteOK(m, q);
    }

    @Test
    public void testJoinMaterialization3() {
        String q = "select empid deptno from emps\n"
                + "join depts using (deptno) where empid = 1";
        String m = "select empid deptno from emps\n"
                + "join depts using (deptno)";
        testRewriteOK(m, q);
    }

    @Test
    public void testJoinMaterialization4() {
        testRewriteOK("select empid deptno from emps\n"
                        + "join depts using (deptno)",
                "select empid deptno from emps\n"
                        + "join depts using (deptno) where empid = 1");
    }

    @Test
    @Disabled
    // TODO: Need add no-loss-cast in lineage factory.
    public void testJoinMaterialization5() {
        testRewriteOK("select cast(empid as BIGINT) as a from emps\n"
                        + "join depts using (deptno)",
                "select empid from emps\n"
                        + "join depts using (deptno) where empid > 1");
    }

    @Test
    @Disabled
    // TODO: Need add no-loss-cast in lineage factory.
    public void testJoinMaterialization6() {
        testRewriteOK("select cast(empid as BIGINT) as a from emps\n"
                        + "join depts using (deptno)",
                "select empid deptno from emps\n"
                        + "join depts using (deptno) where empid = 1");
    }

    @Test
    public void testJoinMaterialization7() {
        testRewriteOK("select depts.name\n"
                        + "from emps\n"
                        + "join depts on (emps.deptno = depts.deptno)",
                "select dependents.empid\n"
                        + "from emps\n"
                        + "join depts on (emps.deptno = depts.deptno)\n"
                        + "join dependents on (depts.name = dependents.name)");
    }

    @Test
    public void testJoinMaterialization8() {
        testRewriteOK("select depts.name\n"
                        + "from emps\n"
                        + "join depts on (emps.deptno = depts.deptno)",
                "select dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)");
    }

    @Test
    public void testJoinMaterialization9() {
        testRewriteOK("select depts.name\n"
                        + "from emps\n"
                        + "join depts on (emps.deptno = depts.deptno)",
                "select dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join locations on (locations.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)");
    }


    @Test
    @Disabled
    public void testJoinMaterialization10() {
        testRewriteOK("select depts.deptno, dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 30",
                "select dependents.empid\n"
                        + "from depts\n"
                        + "join dependents on (depts.name = dependents.name)\n"
                        + "join emps on (emps.deptno = depts.deptno)\n"
                        + "where depts.deptno > 10");
    }

    @Test
    public void testJoinMaterialization11() {
        testRewriteOK("select empid from emps\n"
                        + "join depts using (deptno)",
                "select empid from emps\n"
                        + "where deptno in (select deptno from depts)");
    }

    @Test
    @Disabled
    public void testJoinMaterialization12() {
        testRewriteOK("select empid, emps.name as a, emps.deptno, depts.name as b \n"
                        + "from emps join depts using (deptno)\n"
                        + "where (depts.name is not null and emps.name = 'a') or "
                        + "(depts.name is not null and emps.name = 'b') or "
                        + "(depts.name is not null and emps.name = 'c')",
                "select depts.deptno, depts.name\n"
                        + "from emps join depts using (deptno)\n"
                        + "where (depts.name is not null and emps.name = 'a') or "
                        + "(depts.name is not null and emps.name = 'b')");
    }


    @Test
    @Disabled
    // TODO: agg push down below Join
    public void testAggregateOnJoinKeys() {
        testRewriteOK("select deptno, empid, salary "
                        + "from emps\n"
                        + "group by deptno, empid, salary",
                "select empid, depts.deptno "
                        + "from emps\n"
                        + "join depts on depts.deptno = empid group by empid, depts.deptno");
    }


    @Test
    @Disabled
    public void testAggregateOnJoinKeys2() {
        testRewriteOK("select deptno, empid, salary, sum(1) as c "
                        + "from emps\n"
                        + "group by deptno, empid, salary",
                "select sum(1) "
                        + "from emps\n"
                        + "join depts on depts.deptno = emps.empid group by empid, depts.deptno");
    }

    @Test
    public void testAggregateMaterializationOnCountDistinctQuery1() {
        // The column empid is already unique, thus DISTINCT is not
        // in the COUNT of the resulting rewriting
        testRewriteOK("select deptno, empid, salary\n"
                        + "from emps\n"
                        + "group by deptno, empid, salary",
                "select deptno, count(distinct empid) as c from (\n"
                        + "select deptno, empid\n"
                        + "from emps\n"
                        + "group by deptno, empid) t\n"
                        + "group by deptno");
    }

    @Test
    public void testAggregateMaterializationOnCountDistinctQuery2() {
        // The column empid is already unique, thus DISTINCT is not
        // in the COUNT of the resulting rewriting
        testRewriteOK("select deptno, salary, empid\n"
                        + "from emps\n"
                        + "group by deptno, salary, empid",
                "select deptno, count(distinct empid) as c from (\n"
                        + "select deptno, empid\n"
                        + "from emps\n"
                        + "group by deptno, empid) t \n"
                        + "group by deptno");
    }

    @Test
    public void testAggregateMaterializationOnCountDistinctQuery3() {
        // The column salary is not unique, thus we end up with
        // a different rewriting
        testRewriteOK("select deptno, empid, salary\n"
                        + "from emps\n"
                        + "group by deptno, empid, salary",
                "select deptno, count(distinct salary) from (\n"
                        + "select deptno, salary\n"
                        + "from emps\n"
                        + "group by deptno, salary) t \n"
                        + "group by deptno");
    }

    @Test
    public void testAggregateMaterializationOnCountDistinctQuery4() {
        // Although there is no DISTINCT in the COUNT, this is
        // equivalent to previous query
        testRewriteOK("select deptno, salary, empid\n"
                        + "from emps\n"
                        + "group by deptno, salary, empid",
                "select deptno, count(salary) from (\n"
                        + "select deptno, salary\n"
                        + "from emps\n"
                        + "group by deptno, salary) t\n"
                        + "group by deptno");
    }

    @Test
    public void testInnerJoinViewDelta() {
        String mv = "SELECT" +
                " `l`.`LO_ORDERKEY` as col1, `l`.`LO_ORDERDATE`, `l`.`LO_LINENUMBER`, `l`.`LO_CUSTKEY`, `l`.`LO_PARTKEY`," +
                " `l`.`LO_SUPPKEY`, `l`.`LO_ORDERPRIORITY`, `l`.`LO_SHIPPRIORITY`, `l`.`LO_QUANTITY`," +
                " `l`.`LO_EXTENDEDPRICE`, `l`.`LO_ORDTOTALPRICE`, `l`.`LO_DISCOUNT`, `l`.`LO_REVENUE`," +
                " `l`.`LO_SUPPLYCOST`, `l`.`LO_TAX`, `l`.`LO_COMMITDATE`, `l`.`LO_SHIPMODE`," +
                " `c`.`C_NAME`, `c`.`C_ADDRESS`, `c`.`C_CITY`, `c`.`C_NATION`, `c`.`C_REGION`, `c`.`C_PHONE`," +
                " `c`.`C_MKTSEGMENT`, `s`.`S_NAME`, `s`.`S_ADDRESS`, `s`.`S_CITY`, `s`.`S_NATION`, `s`.`S_REGION`," +
                " `s`.`S_PHONE`, `p`.`P_NAME`, `p`.`P_MFGR`, `p`.`P_CATEGORY`, `p`.`P_BRAND`, `p`.`P_COLOR`," +
                " `p`.`P_TYPE`, `p`.`P_SIZE`, `p`.`P_CONTAINER`\n" +
                "FROM `lineorder` AS `l` INNER" +
                " JOIN `customer` AS `c` ON `c`.`C_CUSTKEY` = `l`.`LO_CUSTKEY`" +
                " INNER JOIN `supplier` AS `s` ON `s`.`S_SUPPKEY` = `l`.`LO_SUPPKEY`" +
                " INNER JOIN `part` AS `p` ON `p`.`P_PARTKEY` = `l`.`LO_PARTKEY`;";

        String query = "SELECT `lineorder`.`lo_orderkey`, `lineorder`.`lo_orderdate`, `customer`.`c_custkey` AS `cd`\n" +
                "FROM `lineorder` INNER JOIN `customer` ON `lineorder`.`lo_custkey` = `customer`.`c_custkey`\n" +
                "WHERE `lineorder`.`lo_orderkey` = 100;";

        testRewriteOK(mv, query);
    }

    @Test
    public void testOuterJoinViewDelta() {
        {
            String mv = "SELECT" +
                    " `l`.`LO_ORDERKEY` as col1, `l`.`LO_ORDERDATE`, `l`.`LO_LINENUMBER`, `l`.`LO_CUSTKEY`, `l`.`LO_PARTKEY`," +
                    " `l`.`LO_SUPPKEY`, `l`.`LO_ORDERPRIORITY`, `l`.`LO_SHIPPRIORITY`, `l`.`LO_QUANTITY`," +
                    " `l`.`LO_EXTENDEDPRICE`, `l`.`LO_ORDTOTALPRICE`, `l`.`LO_DISCOUNT`, `l`.`LO_REVENUE`," +
                    " `l`.`LO_SUPPLYCOST`, `l`.`LO_TAX`, `l`.`LO_COMMITDATE`, `l`.`LO_SHIPMODE`," +
                    " `c`.`C_NAME`, `c`.`C_ADDRESS`, `c`.`C_CITY`, `c`.`C_NATION`, `c`.`C_REGION`, `c`.`C_PHONE`," +
                    " `c`.`C_MKTSEGMENT`, `s`.`S_NAME`, `s`.`S_ADDRESS`, `s`.`S_CITY`, `s`.`S_NATION`, `s`.`S_REGION`," +
                    " `s`.`S_PHONE`, `p`.`P_NAME`, `p`.`P_MFGR`, `p`.`P_CATEGORY`, `p`.`P_BRAND`, `p`.`P_COLOR`," +
                    " `p`.`P_TYPE`, `p`.`P_SIZE`, `p`.`P_CONTAINER`\n" +
                    "FROM `lineorder` AS `l` " +
                    " LEFT OUTER JOIN `customer` AS `c` ON `c`.`C_CUSTKEY` = `l`.`LO_CUSTKEY`" +
                    " LEFT OUTER JOIN `supplier` AS `s` ON `s`.`S_SUPPKEY` = `l`.`LO_SUPPKEY`" +
                    " LEFT OUTER JOIN `part` AS `p` ON `p`.`P_PARTKEY` = `l`.`LO_PARTKEY`;";

            String query = "SELECT `lineorder`.`lo_orderkey`, `lineorder`.`lo_orderdate`, `lineorder`.`lo_custkey` AS `cd`\n" +
                    "FROM `lineorder` LEFT OUTER JOIN `customer` ON `lineorder`.`lo_custkey` = `customer`.`c_custkey`\n" +
                    "WHERE `lineorder`.`lo_orderkey` = 100;";

            testRewriteOK(mv, query);
        }

        {
            String mv2 = "SELECT" +
                    " `l`.`LO_ORDERKEY` as col1, `l`.`LO_ORDERDATE`, `l`.`LO_LINENUMBER`, `l`.`LO_CUSTKEY`, `l`.`LO_PARTKEY`," +
                    " `l`.`LO_SUPPKEY`, `l`.`LO_ORDERPRIORITY`, `l`.`LO_SHIPPRIORITY`, `l`.`LO_QUANTITY`," +
                    " `l`.`LO_EXTENDEDPRICE`, `l`.`LO_ORDTOTALPRICE`, `l`.`LO_DISCOUNT`, `l`.`LO_REVENUE`," +
                    " `l`.`LO_SUPPLYCOST`, `l`.`LO_TAX`, `l`.`LO_COMMITDATE`, `l`.`LO_SHIPMODE`," +
                    " `c`.`C_NAME`, `c`.`C_ADDRESS`, `c`.`C_CITY`, `c`.`C_NATION`, `c`.`C_REGION`, `c`.`C_PHONE`," +
                    " `c`.`C_MKTSEGMENT`, `s`.`S_NAME`, `s`.`S_ADDRESS`, `s`.`S_CITY`, `s`.`S_NATION`, `s`.`S_REGION`," +
                    " `s`.`S_PHONE`, `p`.`P_NAME`, `p`.`P_MFGR`, `p`.`P_CATEGORY`, `p`.`P_BRAND`, `p`.`P_COLOR`," +
                    " `p`.`P_TYPE`, `p`.`P_SIZE`, `p`.`P_CONTAINER`\n" +
                    "FROM `lineorder_null` AS `l` " +
                    " LEFT OUTER JOIN `customer` AS `c` ON `c`.`C_CUSTKEY` = `l`.`LO_CUSTKEY`" +
                    " LEFT OUTER JOIN `supplier` AS `s` ON `s`.`S_SUPPKEY` = `l`.`LO_SUPPKEY`" +
                    " LEFT OUTER JOIN `part` AS `p` ON `p`.`P_PARTKEY` = `l`.`LO_PARTKEY`;";

            String query2 = "SELECT `lineorder_null`.`lo_orderkey`, `lineorder_null`.`lo_orderdate`, `lineorder_null`.`lo_custkey` AS `cd`\n" +
                    "FROM `lineorder_null` LEFT OUTER JOIN `customer` ON `lineorder_null`.`lo_custkey` = `customer`.`c_custkey`\n" +
                    "WHERE `lineorder_null`.`lo_orderkey` = 100;";

            testRewriteOK(mv2, query2);
        }
    }

    @Test
    public void testAggJoinViewDelta() {
        String mv = "SELECT" +
                " `LO_ORDERKEY` as col1, C_CUSTKEY, S_SUPPKEY, P_PARTKEY," +
                " sum(LO_QUANTITY) as total_quantity, sum(LO_ORDTOTALPRICE) as total_price, count(*) as num" +
                " FROM `lineorder` AS `l` " +
                " LEFT OUTER JOIN `customer` AS `c` ON `c`.`C_CUSTKEY` = `l`.`LO_CUSTKEY`" +
                " LEFT OUTER JOIN `supplier` AS `s` ON `s`.`S_SUPPKEY` = `l`.`LO_SUPPKEY`" +
                " LEFT OUTER JOIN `part` AS `p` ON `p`.`P_PARTKEY` = `l`.`LO_PARTKEY`" +
                " group by col1, C_CUSTKEY, S_SUPPKEY, P_PARTKEY";

        String query = "SELECT `lineorder`.`lo_orderkey`, sum(LO_QUANTITY), sum(LO_ORDTOTALPRICE), count(*)\n" +
                "FROM `lineorder` LEFT OUTER JOIN `customer` ON `lineorder`.`lo_custkey` = `customer`.`c_custkey`\n" +
                " group by lo_orderkey";

        testRewriteOK(mv, query);
    }

    @Test
    public void testHiveViewDeltaJoinUKFK1() {
        String mv = "select a.c1, a.c2 from\n"
                + "(select * from hive0.partitioned_db.t1 where c1 = 1) a\n"
                + "join hive0.partitioned_db2.t2 using (c2)";
        String query = "select c2 from hive0.partitioned_db.t1 where c1 = 1";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2)\" ";
        testRewriteOK(mv, query, constraint)
                .contains("0:OlapScanNode\n" +
                        "     TABLE: mv0\n" +
                        "     PREAGGREGATION: ON\n");
    }

    @Test
    public void testHiveViewDeltaJoinUKFK2() {
        String mv = "select t1.c1, l.c2 as c2_1, r.c2 as c2_2, r.c3 from hive0.partitioned_db.t1 " +
                "join hive0.partitioned_db2.t2 l on t1.c2= l.c2 " +
                "join hive0.partitioned_db2.t2 r on t1.c3 = r.c2";
        String query = "select t1.c1, t2.c2 from hive0.partitioned_db.t1 join hive0.partitioned_db2.t2 on t1.c2 = t2.c2";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2); " +
                "hive0.partitioned_db.t1(c3) references hive0.partitioned_db2.t2(c2)\" ";
        testRewriteOK(mv, query, constraint)
                .contains("0:OlapScanNode\n" +
                        "     TABLE: mv0\n" +
                        "     PREAGGREGATION: ON\n");
    }

    @Test
    public void testHiveViewDeltaJoinUKFK3() {
        String mv = "select t1.c1, l.c2 as c2_1, r.c2 as c2_2, r.c3, t3.c1 as new_c1 from hive0.partitioned_db.t1 " +
                "join hive0.partitioned_db2.t2 l on t1.c2 = l.c2 " +
                "join hive0.partitioned_db2.t2 r on t1.c3 = r.c2 " +
                "join hive0.partitioned_db.t3 on t1.c2 = t3.c2";
        String query = "select t1.c1, t3.c1 from hive0.partitioned_db.t1 join hive0.partitioned_db.t3 on t1.c2 = t3.c2";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2); " +
                "hive0.partitioned_db.t1(c3) references hive0.partitioned_db2.t2(c2)\" ";
        testRewriteOK(mv, query, constraint)
                .contains("0:OlapScanNode\n" +
                        "     TABLE: mv0\n" +
                        "     PREAGGREGATION: ON\n");
    }

    @Test
    public void testHiveViewDeltaJoinUKFK4() {
        String mv = "select a.c1, a.c2 from\n" +
                "(select * from hive0.partitioned_db.t1 where c1 = 1) a\n" +
                "join hive0.partitioned_db2.t2 on a.c2 = t2.c2\n" +
                "join hive0.partitioned_db.t3 on a.c2 = t3.c2";
        String query = "select a.c2 from (select * from hive0.partitioned_db.t1 where c1 = 1) a join hive0.partitioned_db.t3 " +
                "on a.c2 = hive0.partitioned_db.t3.c2";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2);\" ";
        testRewriteOK(mv, query, constraint)
                .contains("0:OlapScanNode\n" +
                        "     TABLE: mv0\n" +
                        "     PREAGGREGATION: ON\n");
    }

    @Test
    public void testHiveViewDeltaJoinUKFK5() {
        String mv = "select a.c1, a.c2 from\n" +
                "(select * from hive0.partitioned_db.t1 where c1 = 1) a\n" +
                "join hive0.partitioned_db2.t2 on a.c2 = t2.c2\n" +
                "join hive0.partitioned_db.t3 on a.c2 = t3.c2";
        String query = "select a.c3 from (select * from hive0.partitioned_db.t1 where c1 = 1) a join hive0.partitioned_db.t3 " +
                "on a.c2 = hive0.partitioned_db.t3.c2";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2);\" ";
        testRewriteFail(mv, query, constraint);
    }

    @Test
    public void testHiveViewDeltaJoinUKFK6() {
        String mv = "select t1.c1, l.c2 as c2_1, r.c2 as c2_2, r.c3 from hive0.partitioned_db.t1 " +
                "join hive0.partitioned_db2.t2 l on t1.c2= l.c2 " +
                "join hive0.partitioned_db2.t2 r on t1.c3 = r.c2 " +
                "where t1.c1 = 1";
        String query = "select t1.c1, t2.c2 from hive0.partitioned_db.t1 join hive0.partitioned_db2.t2 on t1.c2 = t2.c2 " +
                "where t1.c1 = 1";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2); " +
                "hive0.partitioned_db.t1(c3) references hive0.partitioned_db2.t2(c2)\" ";
        testRewriteOK(mv, query, constraint)
                .contains("0:OlapScanNode\n" +
                        "     TABLE: mv0\n" +
                        "     PREAGGREGATION: ON\n");
    }

    @Test
    public void testHiveViewDeltaJoinUKFK7() {
        String mv = "select t1.c1, l.c2 as c2_1, r.c2 as c2_2, r.c3 from hive0.partitioned_db.t1 " +
                "join hive0.partitioned_db2.t2 l on t1.c2= l.c1 " +
                "join hive0.partitioned_db2.t2 r on t1.c3 = r.c1 " +
                "where t1.c1 = 1";
        String query = "select t1.c1, t2.c2 from hive0.partitioned_db.t1 join hive0.partitioned_db2.t2 on t1.c2 = t2.c1 " +
                "where t1.c1 = 1";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2); " +
                "hive0.partitioned_db.t1(c3) references hive0.partitioned_db2.t2(c2)\" ";
        testRewriteFail(mv, query, constraint);
    }

    @Test
    public void testHiveViewDeltaJoinUKFK8() {
        String mv = "select t1.c1, l.c2 as c2_1, r.c2 as c2_2, t3.c3 from hive0.partitioned_db.t1 " +
                "left join hive0.partitioned_db2.t2 l on t1.c2= l.c2 " +
                "join hive0.partitioned_db2.t2 r on t1.c3 = r.c2 " +
                "join hive0.partitioned_db.t3 on t1.c2 = t3.c2 " +
                "where t1.c1 = 1";
        String query = "select t1.c1, t3.c3 from hive0.partitioned_db.t1 join hive0.partitioned_db.t3 on t1.c2 = t3.c2 " +
                "where t1.c1 = 1";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2); " +
                "hive0.partitioned_db.t1(c3) references hive0.partitioned_db2.t2(c2)\" ";
        testRewriteOK(mv, query, constraint);
    }

    @Test
    public void testHiveViewDeltaJoinUKFK9() {
        String mv = "select t1.c1, l.c2 as c2_1, r.c2 as c2_2, t3.c3 from hive0.partitioned_db.t1 " +
                "join hive0.partitioned_db.t3 on t1.c2 = t3.c2 " +
                "left join hive0.partitioned_db2.t2 l on t1.c2 = l.c2 " +
                "join hive0.partitioned_db2.t2 r on t1.c3 = r.c2 " +
                "where t1.c1 = 1";
        String query = "select t1.c1, t3.c3 from hive0.partitioned_db.t1 join hive0.partitioned_db.t3 on t1.c2 = t3.c2 " +
                "where t1.c1 = 1";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2); " +
                "hive0.partitioned_db.t1(c3) references hive0.partitioned_db2.t2(c2)\" ";
        testRewriteOK(mv, query, constraint);
    }

    @Test
    public void testHiveViewDeltaJoinUKFK10() {
        String mv = "select t1.c1, l.c2 as c2_1, r.c2 as c2_2, t3.c3 from hive0.partitioned_db.t1 " +
                "join hive0.partitioned_db.t3 on t1.c2 = t3.c2 " +
                "join hive0.partitioned_db2.t2 r on t1.c3 = r.c2 " +
                "left join hive0.partitioned_db2.t2 l on t1.c2 = l.c2 " +
                "where t1.c1 = 1";
        String query = "select t1.c1, t3.c3 from hive0.partitioned_db.t1 join hive0.partitioned_db.t3 on t1.c2 = t3.c2 " +
                "where t1.c1 = 1";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2); " +
                "hive0.partitioned_db.t1(c3) references hive0.partitioned_db2.t2(c2)\" ";
        testRewriteOK(mv, query, constraint);
    }

    @Test
    public void testHiveViewDeltaJoinUKFK11() {
        String mv = "select t1.c1, r.c2, t3.c3 from hive0.partitioned_db.t1 " +
                "join hive0.partitioned_db2.t2 r on t1.c3 = r.c2 " +
                "left join hive0.partitioned_db.t3 on t1.c2= t3.c2 " +
                "where t1.c1 = 1";
        String query = "select t1.c1, t3.c3 from hive0.partitioned_db.t1 left join hive0.partitioned_db.t3 on t1.c2 = t3.c2 " +
                "where t1.c1 = 1";
        String constraint = "\"unique_constraints\" = \"hive0.partitioned_db2.t2.c2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c3) references hive0.partitioned_db2.t2(c2);\" ";
        testRewriteOK(mv, query, constraint);
    }

    // mv: t1, t2, t2
    // query: t1, t2
    @Test
    public void testViewDeltaJoinUKFK1() {
        String mv = "select c1 as col1, c2, c3, l.c6, r.c7" +
                " from t1 join t2 l on t1.c2 = l.c5" +
                " join t2 r on t1.c3 = r.c5";
        String query = "select c1, c2, c3, c6 from t1 join t2 on t1.c2 = t2.c5";
        testRewriteOK(mv, query);
    }

    // mv: t1, t3, t2, t2
    // query: t1, t3
    @Test
    public void testViewDeltaJoinUKFK2() {
        String mv = "select c1 as col1, c2, c3, l.c6, r.c7" +
                " from t1 join t2 l on t1.c2 = l.c5" +
                " join t2 r on t1.c3 = r.c5" +
                " join t3 on t1.c2 = t3.c5";
        String query = "select c1, c2, c3, t3.c5 from t1 join t3 on t1.c2 = t3.c5";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK3() {
        String mv = "select a.empid, a.deptno from\n"
                + "(select * from emps where empid = 1) a\n"
                + "join depts using (deptno)\n"
                + "join dependents using (empid)";
        String query = "select a.empid from \n"
                + "(select * from emps where empid = 1) a\n"
                + "join dependents using (empid)";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK4() {
        String mv = "select a.empid, a.deptno from\n"
                + "(select * from emps where empid = 1) a\n"
                + "join depts using (deptno)\n"
                + "join dependents using (empid)";
        String query = "select a.name from \n"
                + "(select * from emps where empid = 1) a\n"
                + "join dependents using (empid)";
        testRewriteFail(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK5() {
        String mv = "select a.empid, a.deptno from\n"
                + "(select * from emps where empid = 1) a\n"
                + "join depts using (deptno)";
        String query = "select empid from emps where empid = 1";
        testRewriteOK(mv, query)
                .contains("0:OlapScanNode\n" +
                        "     TABLE: mv0\n" +
                        "     PREAGGREGATION: ON\n");
    }

    @Test
    public void testViewDeltaJoinUKFK6() {
        String mv = "select emps.empid, emps.deptno from emps\n"
                + "join depts using (deptno)\n"
                + "join dependents using (empid)"
                + "where emps.empid = 1";
        String query = "select emps.empid from emps\n"
                + "join dependents using (empid)\n"
                + "where emps.empid = 1";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK7() {
        String mv = "select emps.empid, emps.deptno from emps\n"
                + "join depts a on (emps.deptno=a.deptno)\n"
                + "join depts b on (emps.deptno=b.deptno)\n"
                + "join dependents using (empid)"
                + "where emps.empid = 1";

        String query = "select emps.empid from emps\n"
                + "join dependents using (empid)\n"
                + "where emps.empid = 1";
        testRewriteOK(mv, query);
    }

    // join key is not foreign key
    @Test
    public void testViewDeltaJoinUKFK8() {
        String mv = "select emps.empid, emps.deptno from emps\n"
                + "join depts a on (emps.name=a.name)\n"
                + "join depts b on (emps.name=b.name)\n"
                + "join dependents using (empid)"
                + "where emps.empid = 1";

        String query = "select emps.empid from emps\n"
                + "join dependents using (empid)\n"
                + "where emps.empid = 1";
        testRewriteFail(mv, query);
    }

    // join key is not foreign key
    @Test
    public void testViewDeltaJoinUKFK9() {
        String mv = "select emps.empid, emps.deptno from emps\n"
                + "join depts a on (emps.deptno=a.deptno)\n"
                + "join depts b on (emps.name=b.name)\n"
                + "join dependents using (empid)"
                + "where emps.empid = 1";

        String query = "select emps.empid from emps\n"
                + "join dependents using (empid)\n"
                + "where emps.empid = 1";
        testRewriteFail(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK10() {
        String mv = "select emps.empid as empid1, emps.name, emps.deptno, dependents.empid as empid2 from emps\n"
                + "join dependents using (empid)";

        String query = "select emps.empid, dependents.empid, emps.deptno\n"
                + "from emps\n"
                + "join dependents using (empid)"
                + "join depts a on (emps.deptno=a.deptno)\n"
                + "where emps.name = 'Bill'";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK11() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps\n"
                + "left outer join depts a on (emps.deptno=a.deptno)\n"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "inner join dependents using (empid)"
                + "where emps.empid = 1";

        String query = "select emps.empid, dependents.name from emps\n"
                + "inner join dependents using (empid)\n"
                + "where emps.empid = 1";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK12() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps\n"
                + "inner join dependents using (empid)"
                + "left outer join depts a on (emps.deptno=a.deptno)\n"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "where emps.empid = 1";

        String query = "select emps.empid, dependents.name from emps\n"
                + "inner join dependents using (empid)\n"
                + "where emps.empid = 1";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK13() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps\n"
                + "inner join dependents using (empid)"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "left outer join depts a on (emps.deptno=a.deptno)\n"
                + "where emps.empid = 1";

        String query = "select emps.empid, dependents.name from emps\n"
                + "inner join dependents using (empid)\n"
                + "where emps.empid = 1";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK14() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps\n"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "left outer join dependents using (empid)"
                + "where emps.empid = 1";

        String query = "select emps.empid, dependents.name from emps\n"
                + "left outer join dependents using (empid)\n"
                + "where emps.empid = 1";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK15() {
        connectContext.getSessionVariable().setMaterializedViewMaxRelationMappingSize(0);
        String mv = "select emps.empid, emps.deptno, dependents.name from emps\n"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "left outer join dependents using (empid)"
                + "where emps.empid = 1";

        String query = "select emps.empid, dependents.name from emps\n"
                + "left outer join dependents using (empid)\n"
                + "where emps.empid = 1";
        testRewriteFail(mv, query);
        connectContext.getSessionVariable().setMaterializedViewMaxRelationMappingSize(5);
    }

    @Test
    public void testViewDeltaJoinUKFK16() {
        // set join derive rewrite in view delta
        String mv = "select emps.empid, emps.deptno, dependents.name from emps\n"
                + "left outer join depts b on (emps.deptno=b.deptno)\n"
                + "left outer join dependents using (empid)";

        String query = "select emps.empid, dependents.name from emps\n"
                + "left outer join dependents using (empid)\n"
                + "where dependents.name = 'name1'";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK17() {
        // set join derive rewrite in view delta
        String mv = "select emps.empid, emps.deptno, dependents.name from emps\n"
                + "left outer join depts b on (emps.deptno=b.deptno)\n"
                + "left outer join dependents using (empid)";

        String query = "select emps.empid, dependents.name from emps\n"
                + " join dependents using (empid)\n"
                + "where dependents.name = 'name1'";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK18() {
        // set join derive rewrite in view delta
        String mv = "select emps.empid, emps.deptno, dependents.name from emps\n"
                + "left outer join depts b on (emps.deptno=b.deptno)\n"
                + "full outer join dependents using (empid)";

        String query = "select emps.empid, dependents.name from emps\n"
                + " full join dependents using (empid)\n"
                + "where emps.empid = '1'";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFK19() {
        // set join derive rewrite in view delta
        String mv = "select emps.empid, emps.deptno, dependents.name from emps\n"
                + "left outer join depts b on (emps.deptno=b.deptno)\n"
                + "full outer join dependents using (empid)";

        String query = "select emps.empid, dependents.name from emps\n"
                + " inner join dependents using (empid)\n"
                + "where emps.empid = '1'";
        testRewriteOK(mv, query);
    }

    @Test
    public void testViewDeltaJoinUKFKInMV1() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps\n"
                + "join dependents using (empid)";
        String query = "select empid, deptno from emps\n"
                + "where emps.empid = 1";
        String constraint = "\"unique_constraints\" = \"dependents.empid\"," +
                "\"foreign_key_constraints\" = \"emps(empid) references dependents(empid)\" ";
        testRewriteOK(mv, query, constraint).
                contains("0:OlapScanNode\n" +
                        "     TABLE: mv0");
    }

    @Test
    public void testViewDeltaJoinUKFKInMV2() {
        String mv = "select emps_no_constraint.empid, emps_no_constraint.deptno, dependents.name from emps_no_constraint\n"
                + "join dependents using (empid)";
        String query = "select empid, deptno from emps_no_constraint\n"
                + "where empid = 1";
        String constraint = "\"unique_constraints\" = \"dependents.empid\"," +
                "\"foreign_key_constraints\" = \"emps_no_constraint(empid) references dependents(empid)\" ";
        testRewriteOK(mv, query, constraint).
                contains("0:OlapScanNode\n" +
                        "     TABLE: mv0");
    }

    @Test
    public void testViewDeltaJoinUKFKInMV3() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps_no_constraint emps\n"
                + "join dependents using (empid)"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "where emps.empid = 1";
        String query = "select empid, emps.deptno from emps_no_constraint emps join depts b on (emps.deptno=b.deptno) \n"
                + "where empid = 1";
        String constraint = "\"unique_constraints\" = \"dependents.empid\"," +
                "\"foreign_key_constraints\" = \"emps_no_constraint(empid) references dependents(empid)\" ";
        testRewriteOK(mv, query, constraint).
                contains("0:OlapScanNode\n" +
                        "     TABLE: mv0");
    }

    @Test
    public void testViewDeltaJoinUKFKInMV4() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps_no_constraint emps\n"
                + "left join dependents using (empid)"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "where emps.empid = 1";
        String query = "select empid, emps.deptno from emps_no_constraint emps join depts b on (emps.deptno=b.deptno) \n"
                + "where empid = 1";
        String constraint = "\"unique_constraints\" = \"dependents.empid\"," +
                "\"foreign_key_constraints\" = \"emps_no_constraint(empid) references dependents(empid)\" ";
        testRewriteOK(mv, query, constraint).
                contains("0:OlapScanNode\n" +
                        "     TABLE: mv0");
    }

    @Test
    public void testViewDeltaJoinUKFKInMV5() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps_no_constraint emps\n"
                + "left join dependents using (empid)"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "left outer join depts a on (emps.deptno=a.deptno)\n"
                + "where emps.empid = 1";
        String query = "select empid, emps.deptno from emps_no_constraint emps join depts b on (emps.deptno=b.deptno) \n"
                + "where empid = 1";
        String constraint = "\"unique_constraints\" = \"dependents.empid\"," +
                "\"foreign_key_constraints\" = \"emps_no_constraint(empid) references dependents(empid)\" ";
        testRewriteFail(mv, query, constraint);
    }

    @Test
    public void testViewDeltaJoinUKFKInMV6() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps_no_constraint emps\n"
                + "left join dependents using (empid)"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "left outer join depts a on (emps.deptno=a.deptno)\n"
                + "where emps.empid = 1";
        String query = "select empid, emps.deptno from emps_no_constraint emps join depts b on (emps.deptno=b.deptno) \n"
                + "where empid = 1";
        String constraint = "\"unique_constraints\" = \"dependents.empid; depts.deptno\"," +
                "\"foreign_key_constraints\" = \"emps_no_constraint(empid) references dependents(empid);" +
                "emps_no_constraint(deptno) references depts(deptno)\" ";
        testRewriteOK(mv, query, constraint).
                contains("0:OlapScanNode\n" +
                        "     TABLE: mv0");
    }

    @Test
    public void testViewDeltaJoinUKFKInMV7() {
        connectContext.getSessionVariable().setMaterializedViewMaxRelationMappingSize(1);
        String mv = "select emps.empid, emps.deptno, dependents.name from emps_no_constraint emps\n"
                + "left join dependents using (empid)"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "left outer join depts a on (emps.deptno=a.deptno)\n"
                + "where emps.empid = 1";
        String query = "select empid, emps.deptno from emps_no_constraint emps join depts b on (emps.deptno=b.deptno) \n"
                + "where empid = 1";
        String constraint = "\"unique_constraints\" = \"dependents.empid; depts.deptno\"," +
                "\"foreign_key_constraints\" = \"emps_no_constraint(empid) references dependents(empid);" +
                "emps_no_constraint(deptno) references depts(deptno)\" ";
        testRewriteFail(mv, query, constraint);
        connectContext.getSessionVariable().setMaterializedViewMaxRelationMappingSize(5);
    }

    @Test
    public void testViewDeltaColumnCaseSensitiveOnDuplicate() throws Exception {
        {
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_03` (\n" +
                    "    `c5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c5`)\n" +
                    "DISTRIBUTED BY HASH(`c5`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"colocate_with\" = \"groupa4\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"unique_constraints\" = \"C5\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_02` (\n" +
                    "    `c5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c5`)\n" +
                    "DISTRIBUTED BY HASH(`c5`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"colocate_with\" = \"groupa4\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"unique_constraints\" = \"C5\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_01` (\n" +
                    "    `c1` int(11) NOT NULL,\n" +
                    "    `C2` int(11) NOT NULL,\n" +
                    "    `c3` int(11) NOT NULL,\n" +
                    "    `C4` decimal(38, 19) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c1`)\n" +
                    "DISTRIBUTED BY HASH(`c1`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"colocate_with\" = \"groupa4\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"foreign_key_constraints\" = \"(c2) REFERENCES tbl_02(C5);(C3) REFERENCES tbl_02(C5)\"\n" +
                    ");");

            String mv = "select c1 as col1, c2, c3, l.c6, r.c7\n" +
                    " from tbl_01 join tbl_02 l on tbl_01.c2 = l.c5\n" +
                    " join tbl_02 r on tbl_01.c3 = r.c5\n" +
                    " join tbl_03 on tbl_01.c2 = tbl_03.c5";
            String query = "select c1, c2, c3, tbl_03.c5 from tbl_01 join tbl_03 on tbl_01.c2 = tbl_03.c5;";
            testRewriteOK(mv, query);

            starRocksAssert.dropTable("tbl_01");
            starRocksAssert.dropTable("tbl_02");
            starRocksAssert.dropTable("tbl_03");
        }

        {
            // multi key columns
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_03` (\n" +
                    "    `c5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c5`, `c6`)\n" +
                    "DISTRIBUTED BY HASH(`c5`, `c6`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"unique_constraints\" = \"C5\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_02` (\n" +
                    "    `C5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`C5`, `C6`, `c7`)\n" +
                    "DISTRIBUTED BY HASH(`C5`, `C6`, `c7`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"unique_constraints\" = \"C5, C6, c7\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_01` (\n" +
                    "    `c1` int(11) NOT NULL,\n" +
                    "    `C2` int(11) NOT NULL,\n" +
                    "    `c3` int(11) NOT NULL,\n" +
                    "    `C4` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c1`)\n" +
                    "DISTRIBUTED BY HASH(`c1`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"foreign_key_constraints\" = \"(c2, c4, C3) REFERENCES tbl_02(c5, C7, C6)\"\n" +
                    ");");

            String mv = "select c1 as col1, c2, c3, l.c6, l.c7\n" +
                    " from tbl_01 join tbl_02 l on tbl_01.c2 = l.C5 and tbl_01.c3 = l.c6 and tbl_01.c4 = l.c7\n" +
                    " join tbl_03 on tbl_01.c2 = tbl_03.c5";
            String query = "select c1, c2, c3, tbl_03.c5 from tbl_01 join tbl_03 on tbl_01.c2 = tbl_03.c5;";
            testRewriteOK(mv, query);

            starRocksAssert.dropTable("tbl_01");
            starRocksAssert.dropTable("tbl_02");
            starRocksAssert.dropTable("tbl_03");
        }
    }

    @Test
    public void testViewDeltaColumnCaseSensitiveOnPrimary() throws Exception {
        {
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_03` (\n" +
                    "    `c5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c5`)\n" +
                    "DISTRIBUTED BY HASH(`c5`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"colocate_with\" = \"groupa4\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"unique_constraints\" = \"C5\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_02` (\n" +
                    "    `c5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "PRIMARY KEY(`c5`)\n" +
                    "DISTRIBUTED BY HASH(`c5`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"colocate_with\" = \"groupa4\",\n" +
                    "    \"in_memory\" = \"false\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_01` (\n" +
                    "    `c1` int(11) NOT NULL,\n" +
                    "    `C2` int(11) NOT NULL,\n" +
                    "    `c3` int(11) NOT NULL,\n" +
                    "    `C4` decimal(38, 19) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c1`)\n" +
                    "DISTRIBUTED BY HASH(`c1`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"colocate_with\" = \"groupa4\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"foreign_key_constraints\" = \"(c2) REFERENCES tbl_02(C5);(C3) REFERENCES tbl_02(c5)\"\n" +
                    ");");

            String mv = "select c1 as col1, c2, c3, l.c6, r.c7\n" +
                    " from tbl_01 join tbl_02 l on tbl_01.c2 = l.c5\n" +
                    " join tbl_02 r on tbl_01.c3 = r.c5\n" +
                    " join tbl_03 on tbl_01.c2 = tbl_03.c5";
            String query = "select c1, c2, c3, tbl_03.c5 from tbl_01 join tbl_03 on tbl_01.c2 = tbl_03.c5;";
            testRewriteOK(mv, query);

            starRocksAssert.dropTable("tbl_01");
            starRocksAssert.dropTable("tbl_02");
            starRocksAssert.dropTable("tbl_03");
        }

        {
            // multi key columns
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_03` (\n" +
                    "    `c5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c5`, `c6`)\n" +
                    "DISTRIBUTED BY HASH(`c5`, `c6`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"unique_constraints\" = \"C5\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_02` (\n" +
                    "    `C5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "PRIMARY KEY(`C5`, `C6`)\n" +
                    "DISTRIBUTED BY HASH(`C5`, `C6`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_01` (\n" +
                    "    `c1` int(11) NOT NULL,\n" +
                    "    `C2` int(11) NOT NULL,\n" +
                    "    `c3` int(11) NOT NULL,\n" +
                    "    `C4` decimal(38, 19) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c1`)\n" +
                    "DISTRIBUTED BY HASH(`c1`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"foreign_key_constraints\" = \"(c2, C3) REFERENCES tbl_02(c5, C6)\"\n" +
                    ");");

            String mv = "select c1 as col1, c2, c3, l.c6, l.c7\n" +
                    " from tbl_01 join tbl_02 l on tbl_01.c2 = l.C5 and tbl_01.c3 = l.c6\n" +
                    " join tbl_03 on tbl_01.c2 = tbl_03.c5";
            String query = "select c1, c2, c3, tbl_03.c5 from tbl_01 join tbl_03 on tbl_01.c2 = tbl_03.c5;";
            testRewriteOK(mv, query);

            starRocksAssert.dropTable("tbl_01");
            starRocksAssert.dropTable("tbl_02");
            starRocksAssert.dropTable("tbl_03");
        }
    }


    @Test
    public void testViewDeltaColumnCaseSensitiveOnUnique() throws Exception {
        {
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_03` (\n" +
                    "    `c5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c5`)\n" +
                    "DISTRIBUTED BY HASH(`c5`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"unique_constraints\" = \"C5\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_02` (\n" +
                    "    `C5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "UNIQUE KEY(`C5`)\n" +
                    "DISTRIBUTED BY HASH(`C5`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_01` (\n" +
                    "    `c1` int(11) NOT NULL,\n" +
                    "    `C2` int(11) NOT NULL,\n" +
                    "    `c3` int(11) NOT NULL,\n" +
                    "    `C4` decimal(38, 19) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c1`)\n" +
                    "DISTRIBUTED BY HASH(`c1`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"foreign_key_constraints\" = \"(c2) REFERENCES tbl_02(C5);(C3) REFERENCES tbl_02(c5)\"\n" +
                    ");");

            String mv = "select c1 as col1, c2, c3, l.c6, r.c7\n" +
                    " from tbl_01 join tbl_02 l on tbl_01.c2 = l.C5\n" +
                    " join tbl_02 r on tbl_01.c3 = r.c5\n" +
                    " join tbl_03 on tbl_01.c2 = tbl_03.c5";
            String query = "select c1, c2, c3, tbl_03.c5 from tbl_01 join tbl_03 on tbl_01.c2 = tbl_03.c5;";
            testRewriteOK(mv, query);

            starRocksAssert.dropTable("tbl_01");
            starRocksAssert.dropTable("tbl_02");
            starRocksAssert.dropTable("tbl_03");
        }

        {
            // multi key columns
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_03` (\n" +
                    "    `c5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c5`, `c6`)\n" +
                    "DISTRIBUTED BY HASH(`c5`, `c6`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"unique_constraints\" = \"C5\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_02` (\n" +
                    "    `C5` int(11) NOT NULL,\n" +
                    "    `c6` int(11) NOT NULL,\n" +
                    "    `c7` int(11) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "UNIQUE KEY(`C5`, `C6`)\n" +
                    "DISTRIBUTED BY HASH(`C5`, `C6`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\"\n" +
                    ");");
            starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS `tbl_01` (\n" +
                    "    `c1` int(11) NOT NULL,\n" +
                    "    `C2` int(11) NOT NULL,\n" +
                    "    `c3` int(11) NOT NULL,\n" +
                    "    `C4` decimal(38, 19) NOT NULL\n" +
                    ") ENGINE=OLAP\n" +
                    "DUPLICATE KEY(`c1`)\n" +
                    "DISTRIBUTED BY HASH(`c1`) BUCKETS 12\n" +
                    "PROPERTIES (\n" +
                    "    \"replication_num\" = \"1\",\n" +
                    "    \"in_memory\" = \"false\",\n" +
                    "    \"foreign_key_constraints\" = \"(C3, c2) REFERENCES tbl_02(C6, c5)\"\n" +
                    ");");

            String mv = "select c1 as col1, c2, c3, l.c6, l.c7\n" +
                    " from tbl_01 join tbl_02 l on tbl_01.c2 = l.C5 and tbl_01.c3 = l.c6\n" +
                    " join tbl_03 on tbl_01.c2 = tbl_03.c5";
            String query = "select c1, c2, c3, tbl_03.c5 from tbl_01 join tbl_03 on tbl_01.c2 = tbl_03.c5;";
            testRewriteOK(mv, query);

            starRocksAssert.dropTable("tbl_01");
            starRocksAssert.dropTable("tbl_02");
            starRocksAssert.dropTable("tbl_03");
        }
    }

    @Test
    public void testJoinTypeMismatchRewriteForViewDelta() throws Exception {
        {
            String query = "select sum(lo_linenumber) from `lineorder`" +
                    " LEFT OUTER JOIN `dates`" +
                    " ON `lineorder`.`lo_orderdate` = `dates`.`d_datekey` WHERE `lineorder`.`lo_orderdate` = 19961001;";

            String mv = "SELECT\n" +
                    "       l.LO_ORDERKEY AS LO_ORDERKEY,\n" +
                    "       l.LO_LINENUMBER AS LO_LINENUMBER,\n" +
                    "       l.LO_CUSTKEY AS LO_CUSTKEY,\n" +
                    "       l.LO_PARTKEY AS LO_PARTKEY,\n" +
                    "       l.LO_SUPPKEY AS LO_SUPPKEY,\n" +
                    "       l.LO_ORDERDATE AS LO_ORDERDATE,\n" +
                    "       l.LO_ORDERPRIORITY AS LO_ORDERPRIORITY,\n" +
                    "       l.LO_SHIPPRIORITY AS LO_SHIPPRIORITY,\n" +
                    "       l.LO_QUANTITY AS LO_QUANTITY,\n" +
                    "       l.LO_EXTENDEDPRICE AS LO_EXTENDEDPRICE,\n" +
                    "       l.LO_ORDTOTALPRICE AS LO_ORDTOTALPRICE,\n" +
                    "       l.LO_DISCOUNT AS LO_DISCOUNT,\n" +
                    "       l.LO_REVENUE AS LO_REVENUE,\n" +
                    "       l.LO_SUPPLYCOST AS LO_SUPPLYCOST,\n" +
                    "       l.LO_TAX AS LO_TAX,\n" +
                    "       l.LO_COMMITDATE AS LO_COMMITDATE,\n" +
                    "       l.LO_SHIPMODE AS LO_SHIPMODE,\n" +
                    "       c.C_NAME AS C_NAME,\n" +
                    "       c.C_ADDRESS AS C_ADDRESS,\n" +
                    "       c.C_CITY AS C_CITY,\n" +
                    "       c.C_NATION AS C_NATION,\n" +
                    "       c.C_REGION AS C_REGION,\n" +
                    "       c.C_PHONE AS C_PHONE,\n" +
                    "       c.C_MKTSEGMENT AS C_MKTSEGMENT,\n" +
                    "       s.S_NAME AS S_NAME,\n" +
                    "       s.S_ADDRESS AS S_ADDRESS,\n" +
                    "       s.S_CITY AS S_CITY,\n" +
                    "       s.S_NATION AS S_NATION,\n" +
                    "       s.S_REGION AS S_REGION,\n" +
                    "       s.S_PHONE AS S_PHONE,\n" +
                    "       p.P_NAME AS P_NAME,\n" +
                    "       p.P_MFGR AS P_MFGR,\n" +
                    "       p.P_CATEGORY AS P_CATEGORY,\n" +
                    "       p.P_BRAND AS P_BRAND,\n" +
                    "       p.P_COLOR AS P_COLOR,\n" +
                    "       p.P_TYPE AS P_TYPE,\n" +
                    "       p.P_SIZE AS P_SIZE,\n" +
                    "       p.P_CONTAINER AS P_CONTAINER,\n" +
                    "       d.d_date AS d_date,\n" +
                    "       d.d_dayofweek AS d_dayofweek,\n" +
                    "       d.d_month AS d_month,\n" +
                    "       d.d_year AS d_year,\n" +
                    "       d.d_yearmonthnum AS d_yearmonthnum,\n" +
                    "       d.d_yearmonth AS d_yearmonth,\n" +
                    "       d.d_daynuminweek AS d_daynuminweek,\n" +
                    "       d.d_daynuminmonth AS d_daynuminmonth,\n" +
                    "       d.d_daynuminyear AS d_daynuminyear,\n" +
                    "       d.d_monthnuminyear AS d_monthnuminyear,\n" +
                    "       d.d_weeknuminyear AS d_weeknuminyear,\n" +
                    "       d.d_sellingseason AS d_sellingseason,\n" +
                    "       d.d_lastdayinweekfl AS d_lastdayinweekfl,\n" +
                    "       d.d_lastdayinmonthfl AS d_lastdayinmonthfl,\n" +
                    "       d.d_holidayfl AS d_holidayfl,\n" +
                    "       d.d_weekdayfl AS d_weekdayfl\n" +
                    "   FROM lineorder AS l\n" +
                    "            INNER JOIN customer AS c ON c.C_CUSTKEY = l.LO_CUSTKEY\n" +
                    "            INNER JOIN supplier AS s ON s.S_SUPPKEY = l.LO_SUPPKEY\n" +
                    "            INNER JOIN part AS p ON p.P_PARTKEY = l.LO_PARTKEY\n" +
                    "            INNER JOIN dates AS d ON l.lo_orderdate = d.d_datekey;";
            testRewriteFail(mv, query);
        }

        {
            String mv = "select" +
                    " l.LO_ORDERKEY AS LO_ORDERKEY,\n" +
                    " l.LO_LINENUMBER AS LO_LINENUMBER,\n" +
                    " l.LO_CUSTKEY AS LO_CUSTKEY,\n" +
                    " l.LO_PARTKEY AS LO_PARTKEY," +
                    " c.C_NAME AS C_NAME,\n" +
                    " c.C_ADDRESS AS C_ADDRESS,\n" +
                    " c.C_CITY AS C_CITY," +
                    " s.S_NAME AS S_NAME,\n" +
                    " s.S_ADDRESS AS S_ADDRESS,\n" +
                    " s.S_CITY AS S_CITY" +
                    " from lineorder AS l\n" +
                    " LEFT OUTER JOIN customer AS c ON c.C_CUSTKEY = l.LO_CUSTKEY\n" +
                    " LEFT OUTER JOIN supplier AS s ON s.S_SUPPKEY = l.LO_SUPPKEY";

            String query = "select" +
                    " l.LO_ORDERKEY AS LO_ORDERKEY,\n" +
                    " l.LO_LINENUMBER AS LO_LINENUMBER,\n" +
                    " l.LO_CUSTKEY AS LO_CUSTKEY,\n" +
                    " l.LO_PARTKEY AS LO_PARTKEY," +
                    " c.C_NAME AS C_NAME,\n" +
                    " c.C_ADDRESS AS C_ADDRESS,\n" +
                    " c.C_CITY AS C_CITY" +
                    " from customer AS c\n" +
                    " LEFT OUTER JOIN lineorder AS l ON c.C_CUSTKEY = l.LO_CUSTKEY";
            testRewriteFail(mv, query);
        }
    }

    @Test
    public void testRewriteAvg1() {
        String mv1 = "select user_id, avg(tag_id) from user_tags group by user_id;";
        testRewriteOK(mv1, "select user_id, avg(tag_id) from user_tags group by user_id;");
        String mv2 = "select user_id, sum(tag_id), count(tag_id) from user_tags group by user_id;";
        testRewriteOK(mv2, "select user_id, avg(tag_id) from user_tags group by user_id;");
    }

    @Test
    public void testRewriteAvg2() {
        String mv2 = "select user_id, time, sum(tag_id) as sum_tag, count(tag_id) as count_tag " +
                "from user_tags group by user_id, time;";
        testRewriteOK(mv2, "select user_id, avg(tag_id) from user_tags group by user_id;");
    }

    @Test
    public void testRewriteAvg3() {
        String mv2 = "select user_id, time, sum(tag_id % 10), count(tag_id % 10) from user_tags group by user_id, time;";
        testRewriteOK(mv2, "select user_id, avg(tag_id % 10) from user_tags group by user_id;");
    }

    @Test
    public void testCountWithRollup() {
        String mv = "select user_id, count(tag_id) from user_tags group by user_id, time;";
        testRewriteOK(mv, "select user_id, count(tag_id) from user_tags group by user_id, time;")
                .notContain("coalesce");
        testRewriteOK(mv, "select user_id, count(tag_id) from user_tags group by user_id;")
                .notContain("coalesce");
        testRewriteOK(mv, "select count(tag_id) from user_tags;")
                .contains("coalesce");
    }

    @Test
    public void testArrayAggDistinctWithRollup() {
        String mv = "select user_id, array_distinct(array_agg(tag_id)) from user_tags group by user_id, time;";
        testRewriteOK(mv, "select user_id, array_distinct(array_agg(tag_id)) from user_tags group by user_id, time;")
                .notContain("array_unique_agg");
        testRewriteOK(mv, "select user_id, array_distinct(array_agg(tag_id)) from user_tags group by user_id")
                .contains("array_unique_agg");
        testRewriteOK(mv, "select array_distinct(array_agg(tag_id)) from user_tags")
                .contains("array_unique_agg");
    }

    @Test
    public void testCountDistinctToBitmapCount1() {
        String mv = "select user_id, bitmap_union(to_bitmap(tag_id)) from user_tags group by user_id;";
        testRewriteOK(mv, "select user_id, bitmap_union(to_bitmap(tag_id)) x from user_tags group by user_id;")
                .match("  |  <slot 2> : 6: user_id\n" +
                        "  |  <slot 5> : 7: bitmap_union(to_bitmap(tag_id))");
        testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(to_bitmap(tag_id))) x from user_tags group by user_id;")
                .match("  |  <slot 2> : 7: user_id\n" +
                        "  |  <slot 6> : bitmap_count(8: bitmap_union(to_bitmap(tag_id)))");
        testRewriteOK(mv, "select user_id, count(distinct tag_id) x from user_tags group by user_id;")
                .match("  |  <slot 2> : 6: user_id\n" +
                        "  |  <slot 5> : bitmap_count(7: bitmap_union(to_bitmap(tag_id)))");
    }

    @Test
    public void testCountDistinctToBitmapCount2() {
        String mv = "select user_id, time, bitmap_union(to_bitmap(tag_id)) from user_tags group by user_id, time;";
        testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(to_bitmap(tag_id))) x from user_tags group by user_id;");
        // rewrite count distinct to bitmap_count(bitmap_union(to_bitmap(x)));
        testRewriteOK(mv, "select user_id, count(distinct tag_id) x from user_tags group by user_id;");
    }

    @Test
    public void testCountDistinctToBitmapCount3() {
        String mv = "select user_id, time, bitmap_union(to_bitmap(tag_id % 10)) from user_tags group by user_id, time;";
        testRewriteOK(mv, "select user_id, bitmap_union(to_bitmap(tag_id % 10)) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(to_bitmap(tag_id % 10))) x from user_tags group by user_id;");
        // rewrite count distinct to bitmap_count(bitmap_union(to_bitmap(x)));
        testRewriteOK(mv, "select user_id, count(distinct tag_id % 10) x from user_tags group by user_id;");
    }

    @Test
    public void testCountDistinctToBitmapCount4() {
        String mv = "select user_id, tag_id from user_tags where user_id > 10;";
        testRewriteOK(mv, "select user_id, bitmap_union(to_bitmap(tag_id)) x from user_tags where user_id > 10 group by user_id ;");
        testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(to_bitmap(tag_id))) x from user_tags where user_id > 10 group by user_id;");
        testRewriteOK(mv, "select user_id, count(distinct tag_id) x from user_tags  where user_id > 10 group by user_id;");
    }

    @Test
    public void testCountDistinctToBitmapCount5() {
        String mv = "select user_id, tag_id from user_tags;";
        testRewriteOK(mv, "select user_id, bitmap_union(to_bitmap(tag_id)) x from user_tags where user_id > 10 group by user_id ;");
        testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(to_bitmap(tag_id))) x from user_tags where user_id > 10 group by user_id;");
        testRewriteOK(mv, "select user_id, count(distinct tag_id) x from user_tags group by user_id;");
    }

    @Test
    public void testCountDistinctToBitmapCount6() {
        String mv = "select user_id, count(tag_id) from user_tags group by user_id;";
        testRewriteFail(mv, "select user_id, bitmap_union(to_bitmap(tag_id)) x from user_tags where user_id > 10 group by user_id ;");
        testRewriteFail(mv, "select user_id, bitmap_count(bitmap_union(to_bitmap(tag_id))) x from user_tags where user_id > 10 group by user_id;");
        testRewriteFail(mv, "select user_id, count(distinct tag_id) x from user_tags group by user_id;");
    }

    @Test
    public void testCountDistinctToBitmapCount7() {
        String mv = "select user_id, time, bitmap_agg(tag_id) from user_tags group by user_id, time;";
        testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(to_bitmap(tag_id))) x from user_tags group by user_id;");
        // rewrite count distinct to bitmap_count(bitmap_union(to_bitmap(x)));
        testRewriteOK(mv, "select user_id, count(distinct tag_id) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, bitmap_count(bitmap_agg(tag_id)) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, bitmap_agg(tag_id) x from user_tags group by user_id, time;");
    }

    @Test
    public void testCountDistinctToBitmapCount8() {
        String mv = "select user_id, time, bitmap_agg(tag_id % 10) from user_tags group by user_id, time;";
        testRewriteOK(mv, "select user_id, bitmap_union(to_bitmap(tag_id % 10)) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(to_bitmap(tag_id % 10))) x from user_tags group by user_id;");
        // rewrite count distinct to bitmap_count(bitmap_union(to_bitmap(x)));
        testRewriteOK(mv, "select user_id, count(distinct tag_id % 10) x from user_tags group by user_id;");
        // bitmap_agg
        testRewriteOK(mv, "select user_id, bitmap_agg(tag_id % 10) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, bitmap_agg(tag_id % 10) x from user_tags group by user_id;");
    }

    @Test
    public void testStrColumnCountDistinctToBitmapCount1() {
        {
            String mv = "select user_id, bitmap_union(bitmap_hash(user_name)) from user_tags group by user_id;";
            testRewriteOK(mv, "select user_id, bitmap_union(bitmap_hash(user_name)) x from user_tags group by user_id;");
            testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(bitmap_hash(user_name))) x from user_tags group by user_id;");
            testRewriteOK(mv, "select user_id, count(distinct user_name) x from user_tags group by user_id;");
        }

        {
            String mv = "select user_id, time, bitmap_union(bitmap_hash(user_name)) from user_tags group by user_id, time;";
            testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(bitmap_hash(user_name))) x from user_tags group by user_id;");
            testRewriteOK(mv, "select user_id, count(distinct user_name) x from user_tags group by user_id;");
        }
        {
            String mv = "select user_id, time, bitmap_union(bitmap_hash(concat(user_name, \"|\"))) from user_tags group by user_id, time;";
            testRewriteOK(mv, "select user_id, bitmap_union(bitmap_hash(concat(user_name, \"|\"))) x from user_tags group by user_id;");
            testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(bitmap_hash(concat(user_name, \"|\")))) x from user_tags group by user_id;");
            // rewrite count distinct to bitmap_count(bitmap_union(bitmap_hash(x)));
            testRewriteOK(mv, "select user_id, count(distinct concat(user_name, \"|\")) x from user_tags group by user_id;");
        }
        {
            String mv = "select user_id, tag_id, user_name from user_tags where user_id > 10;";
            testRewriteOK(mv, "select user_id, bitmap_union(bitmap_hash(user_name)) x from user_tags where user_id > 10 group by user_id ;");
            testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(bitmap_hash(user_name))) x from user_tags where user_id > 10 group by user_id;");
            testRewriteOK(mv, "select user_id, count(distinct user_name) x from user_tags  where user_id > 10 group by user_id;");
        }
        {
            String mv = "select user_id, tag_id, user_name from user_tags;";
            testRewriteOK(mv, "select user_id, bitmap_union(bitmap_hash(user_name)) x from user_tags where user_id > 10 group by user_id ;");
            testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(bitmap_hash(user_name))) x from user_tags where user_id > 10 group by user_id;");
            testRewriteOK(mv, "select user_id, count(distinct user_name) x from user_tags group by user_id;");
        }
        {
            String mv = "select user_id, count(user_name) from user_tags group by user_id;";
            testRewriteFail(mv, "select user_id, bitmap_union(bitmap_hash(user_name)) x from user_tags where user_id > 10 group by user_id ;");
            testRewriteFail(mv, "select user_id, bitmap_count(bitmap_union(bitmap_hash(user_name))) x from user_tags where user_id > 10 group by user_id;");
            testRewriteFail(mv, "select user_id, count(distinct user_name) x from user_tags group by user_id;");
        }
    }

    @Test
    public void testStrColumnCountDistinctToBitmapCount2() {
        String mv = "select user_id, time, bitmap_union(bitmap_hash(user_name)), " +
                "bitmap_union(bitmap_hash(concat(user_name, \"a\"))), " +
                "bitmap_union(bitmap_hash(concat(user_name, \"b\"))), " +
                "bitmap_union(bitmap_hash(concat(user_name, \"c\"))) from user_tags group by user_id, time;";
        connectContext.getSessionVariable().setMaterializedViewRewriteMode("force");
        connectContext.getSessionVariable().setCboCteReuse(false);
        testRewriteOK(mv, "select user_id, bitmap_union(bitmap_hash(user_name)) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(bitmap_hash(user_name))) x " +
                "from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, count(distinct user_name), " +
                " count(distinct concat(user_name, \"a\")), count(distinct concat(user_name, \"b\"))," +
                " count(distinct concat(user_name, \"c\"))  from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, count(distinct user_name), " +
                " count(distinct concat(user_name, \"a\")), count(distinct concat(user_name, \"b\"))," +
                " count(distinct concat(user_name, \"c\"))  from user_tags group by user_id, time;");
        testRewriteOK(mv, "select user_id, time, count(distinct user_name), " +
                " count(distinct concat(user_name, \"a\")), count(distinct concat(user_name, \"b\"))," +
                " count(distinct concat(user_name, \"c\"))  from user_tags group by user_id, time;");
        connectContext.getSessionVariable().setCboCteReuse(true);
    }

    @Test
    public void testStrColumnCountDistinctToBitmapCount3() {
        String mv = "select user_id, time, bitmap_union(bitmap_hash(user_name)), " +
                "bitmap_union(bitmap_hash(concat(user_name, \"a\"))), " +
                "bitmap_union(bitmap_hash(concat(user_name, \"b\"))), " +
                "bitmap_union(bitmap_hash(concat(user_name, \"c\"))) from user_tags group by user_id, time;";
        connectContext.getSessionVariable().setEnableCountDistinctRewriteByHllBitmap(false);
        connectContext.getSessionVariable().setMaterializedViewRewriteMode("force");
        connectContext.getSessionVariable().setCboCteReuse(false);
        testRewriteOK(mv, "select user_id, bitmap_union(bitmap_hash(user_name)) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, bitmap_count(bitmap_union(bitmap_hash(user_name))) x " +
                "from user_tags group by user_id;");
        testRewriteFail(mv, "select user_id, count(distinct user_name), " +
                " count(distinct concat(user_name, \"a\")), count(distinct concat(user_name, \"b\"))," +
                " count(distinct concat(user_name, \"c\"))  from user_tags group by user_id;");
        testRewriteFail(mv, "select user_id, count(distinct user_name), " +
                " count(distinct concat(user_name, \"a\")), count(distinct concat(user_name, \"b\"))," +
                " count(distinct concat(user_name, \"c\"))  from user_tags group by user_id, time;");
        testRewriteFail(mv, "select user_id, time, count(distinct user_name), " +
                " count(distinct concat(user_name, \"a\")), count(distinct concat(user_name, \"b\"))," +
                " count(distinct concat(user_name, \"c\"))  from user_tags group by user_id, time;");
        connectContext.getSessionVariable().setCboCteReuse(true);
        connectContext.getSessionVariable().setEnableCountDistinctRewriteByHllBitmap(true);
    }


    @Test
    public void testBitmapUnionCountToBitmapCount1() {
        String mv = "select user_id, bitmap_union(to_bitmap(tag_id)) from user_tags group by user_id;";
        testRewriteOK(mv, "select user_id, bitmap_union_count(to_bitmap(tag_id)) x from user_tags group by user_id;");
    }

    @Test
    public void testBitmapUnionCountToBitmapCount2() {
        String mv = "select user_id, time, bitmap_union(to_bitmap(tag_id)) from user_tags group by user_id, time;";
        testRewriteOK(mv, "select user_id, bitmap_union_count(to_bitmap(tag_id)) x from user_tags group by user_id;");
    }

    @Test
    public void testApproxCountToHLL1() {
        String mv = "select user_id, time, hll_union(hll_hash(tag_id)) from user_tags group by user_id, time;";
        testRewriteOK(mv, "select user_id, approx_count_distinct(tag_id) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, ndv(tag_id) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, hll_union(hll_hash(tag_id)) x from user_tags group by user_id;");
    }

    @Test
    public void testApproxCountToHLL2() {
        String mv = "select user_id, hll_union(hll_hash(tag_id)) from user_tags group by user_id;";
        testRewriteOK(mv, "select user_id, approx_count_distinct(tag_id) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, ndv(tag_id) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, hll_union(hll_hash(tag_id)) x from user_tags group by user_id;");
    }

    @Test
    public void testApproxCountToHLL3() {
        connectContext.getSessionVariable().setCountDistinctImplementation("ndv");
        String mv = "select user_id, time, hll_union(hll_hash(tag_id)) from user_tags group by user_id, time;";
        testRewriteOK(mv, "select user_id, count(distinct tag_id) x, count(distinct tag_id) y " +
                "from user_tags group by user_id;");
        connectContext.getSessionVariable().setCountDistinctImplementation("default");
    }

    @Test
    public void testApproxCountToHLL4() {
        connectContext.getSessionVariable().setEnableCountDistinctRewriteByHllBitmap(false);
        String mv = "select user_id, time, hll_union(hll_hash(tag_id)) from user_tags group by user_id, time;";
        testRewriteFail(mv, "select user_id, count(distinct tag_id) x, count(distinct tag_id) y " +
                "from user_tags group by user_id;");
        connectContext.getSessionVariable().setEnableCountDistinctRewriteByHllBitmap(true);
        testRewriteOK(mv, "select user_id, count(distinct tag_id) x, count(distinct tag_id) y " +
                "from user_tags group by user_id;");
        testRewriteFail(mv, "select user_id, count(distinct tag_id, user_id) y " +
                "from user_tags group by user_id;");
    }

    @Test
    public void testPercentile1() {
        String mv = "select user_id, percentile_union(percentile_hash(tag_id)) from user_tags group by user_id;";
        testRewriteOK(mv, "select user_id, percentile_approx(tag_id, 1) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, percentile_approx(tag_id, 0) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, round(percentile_approx(tag_id, 0)) x from user_tags group by user_id;");
    }

    @Test
    public void testPercentile2() {
        String mv = "select user_id, time, percentile_union(percentile_hash(tag_id)) from user_tags group by user_id, time;";
        testRewriteOK(mv, "select user_id, percentile_approx(tag_id, 1) x from user_tags group by user_id;");
        testRewriteOK(mv, "select user_id, round(percentile_approx(tag_id, 0)) x from user_tags group by user_id;");
    }

    @Test
    public void testBitmapRewriteBasedOnAggregateTable() throws Exception {
        starRocksAssert.withTable("CREATE TABLE test_bitmap_rewrite_agg_tbl (\n" +
                "ds date,\n" +
                "sum1 bigint sum,\n" +
                "bitmap1 bitmap bitmap_union,\n" +
                "bitmap2 bitmap bitmap_union\n" +
                ") ENGINE = OLAP \n" +
                "aggregate key(ds)\n" +
                "DISTRIBUTED BY HASH(`ds`)\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");\n");

        String mv = "SELECT ds, sum(sum1), bitmap_union(bitmap1), bitmap_union(bitmap2) from test_bitmap_rewrite_agg_tbl " +
                "group by ds";
        testRewriteOK(mv, "select cd1 / cd2 from (select ds, bitmap_union_count(bitmap1) as cd1, " +
                "bitmap_count(bitmap_and(bitmap_union(bitmap1), bitmap_union(bitmap2))) as cd2 " +
                "from test_bitmap_rewrite_agg_tbl group by ds) t");
    }

    @Test
    public void testQueryWithLimitRewrite() throws Exception {
        starRocksAssert.withTable("CREATE TABLE `aggregate_table_with_null` (\n" +
                "  `k1` date NULL,\n" +
                "  `k2` datetime NULL,\n" +
                "  `k3` char(20) NULL,\n" +
                "  `k4` varchar(20) NULL,\n" +
                "  `k5` boolean NULL,\n" +
                "  `v1` bigint(20) SUM NULL,\n" +
                "  `v2` bigint(20) SUM NULL,\n" +
                "  `v3` bigint(20) SUM NULL,\n" +
                "  `v4` bigint(20) MAX NULL,\n" +
                "  `v5` largeint(40) MAX NULL,\n" +
                "  `v6` float MIN NULL,\n" +
                "  `v7` double MIN NULL,\n" +
                "  `v8` decimal128(38, 9) SUM NULL\n" +
                ") ENGINE=OLAP\n" +
                "AGGREGATE KEY(`k1`, `k2`, `k3`, `k4`, `k5`)\n" +
                "DISTRIBUTED BY HASH(`k1`, `k2`, `k3`, `k4`, `k5`) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"in_memory\" = \"false\",\n" +
                "\"storage_format\" = \"V2\",\n" +
                "\"enable_persistent_index\" = \"true\",\n" +
                "\"replicated_storage\" = \"true\",\n" +
                "\"compression\" = \"LZ4\"\n" +
                ");");

        starRocksAssert.withTable("CREATE TABLE `duplicate_table_with_null_partition` (\n" +
                "  `k1` date NULL,\n" +
                "  `k2` datetime NULL,\n" +
                "  `k3` char(20) NULL,\n" +
                "  `k4` varchar(20) NULL,\n" +
                "  `k5` boolean NULL,\n" +
                "  `k6` tinyint(4) NULL,\n" +
                "  `k7` smallint(6) NULL,\n" +
                "  `k8` int(11) NULL,\n" +
                "  `k9` bigint(20) NULL,\n" +
                "  `k10` largeint(40) NULL,\n" +
                "  `k11` float NULL,\n" +
                "  `k12` double NULL,\n" +
                "  `k13` decimal128(27, 9) NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`k1`, `k2`, `k3`, `k4`, `k5`)\n" +
                "PARTITION BY RANGE(`k1`)\n" +
                "(PARTITION p202006 VALUES [(\"0000-01-01\"), (\"2020-07-01\")),\n" +
                "PARTITION p202007 VALUES [(\"2020-07-01\"), (\"2020-08-01\")),\n" +
                "PARTITION p202008 VALUES [(\"2020-08-01\"), (\"2020-09-01\")))\n" +
                "DISTRIBUTED BY HASH(`k1`, `k2`, `k3`, `k4`, `k5`) BUCKETS 3\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"in_memory\" = \"false\",\n" +
                "\"storage_format\" = \"V2\",\n" +
                "\"enable_persistent_index\" = \"true\",\n" +
                "\"replicated_storage\" = \"true\",\n" +
                "\"compression\" = \"LZ4\"\n" +
                ");");
        {
            String query = "SELECT t1.k1 k1, t2.k2 k2" +
                    " from duplicate_table_with_null_partition t1 join aggregate_table_with_null t2" +
                    " on t1.k1 = t2.k1 limit 10";
            String mv = "SELECT t1.k1 k1, t2.k2 k2" +
                    " from duplicate_table_with_null_partition t1 join aggregate_table_with_null t2" +
                    " on t1.k1 = t2.k1";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("limit: 10");
        }

        {
            String query = "SELECT t1.k1 k1, sum(t2.v1) as s" +
                    " from duplicate_table_with_null_partition t1 join aggregate_table_with_null t2" +
                    " on t1.k1 = t2.k1 group by t1.k1 limit 10";
            String mv = "SELECT t1.k1 k1, sum(t2.v1) as s" +
                    " from duplicate_table_with_null_partition t1 join aggregate_table_with_null t2" +
                    " on t1.k1 = t2.k1 group by t1.k1";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("limit: 10");
        }
        starRocksAssert.dropTable("duplicate_table_with_null_partition");
        starRocksAssert.dropTable("aggregate_table_with_null");
    }

    @Test
    public void testUnionRewrite() {
        {
            String mv = "SELECT `customer`.`c_custkey`, `customer`.`c_name`, `customer`.`c_address`, `customer`.`c_city`," +
                    " `customer`.`c_nation`, `customer`.`c_region`, `customer`.`c_phone`, `customer`.`c_mktsegment`\n" +
                    "FROM `customer`\n" +
                    "WHERE `customer`.`c_city` = 'ETHIOPIA 9'";
            String query = "select * from lineorder, customer";
            testRewriteOK(mv, query);
        }

        {
            String mv = "SELECT `customer`.`c_custkey`, `customer`.`c_name`, `customer`.`c_address`, `customer`.`c_city`," +
                    " `customer`.`c_nation`, `customer`.`c_region`, `customer`.`c_phone`, `customer`.`c_mktsegment`\n" +
                    "FROM `customer`\n" +
                    "WHERE `customer`.`c_city` = 'ETHIOPIA 9'";
            String query = "select * from customer, lineorder";
            testRewriteOK(mv, query);
        }

        {
            // test compensation predicates are not in output of query
            String mv = "SELECT `event_id`, `event_type`, `event_time`\n" +
                    "FROM `event1`\n" +
                    "WHERE `event_type` = 'click'; ";
            String query = "select count(event_id) from event1";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("UNION");
        }

        {
            String mv = "SELECT count(lo_linenumber)\n" +
                    "FROM lineorder inner join customer on lo_custkey = c_custkey\n" +
                    "WHERE `c_name` != 'name'; ";

            String query = "SELECT count(lo_linenumber)\n" +
                    "FROM lineorder inner join customer on lo_custkey = c_custkey";
            Table table1 = getTable(MATERIALIZED_DB_NAME, "lineorder");
            PlanTestBase.setTableStatistics((OlapTable) table1, 1000000);
            Table table2 = getTable(MATERIALIZED_DB_NAME, "customer");
            PlanTestBase.setTableStatistics((OlapTable) table2, 1000000);
            // For enforce-columns changed which also changed mv's cost model, use force rewrite to force the result.
            connectContext.getSessionVariable()
                    .setMaterializedViewRewriteMode(SessionVariable.MaterializedViewRewriteMode.FORCE.toString());
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("UNION");
            connectContext.getSessionVariable()
                    .setMaterializedViewRewriteMode(SessionVariable.MaterializedViewRewriteMode.DEFAULT.toString());
        }
    }

    // Single Predicates
    @Test
    public void testRangePredicates1() {
        // =
        {
            String mv = "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                    " left join locations on emps.locationid = locations.locationid where emps.deptno = 10";
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno = 10");
            testRewriteFail(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno = 20");
        }

        // >
        {
            String mv = "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                    " left join locations on emps.locationid = locations.locationid where emps.deptno > 10";
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno > 10");
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno > 20");
            testRewriteFail(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno < 10");
        }

        // >=
        {
            String mv = "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                    " left join locations on emps.locationid = locations.locationid where emps.deptno >= 10";
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno >= 10");
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno = 10");
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno >= 20");
            testRewriteFail(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno <= 10");
        }

        // <
        {
            String mv = "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                    " left join locations on emps.locationid = locations.locationid where emps.deptno < 10";
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno < 10");
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno < 5");
            testRewriteFail(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno > 10");
        }

        // <=
        {
            String mv = "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                    " left join locations on emps.locationid = locations.locationid where emps.deptno <= 10";
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno <= 10");
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno = 10");
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno <= 5");
            testRewriteFail(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno >= 10");
        }

        // !=
        {
            String mv = "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                    " left join locations on emps.locationid = locations.locationid where emps.deptno != 10";

            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno != 10");

            testRewriteFail(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno = 10");
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno > 10");
            testRewriteOK(mv, "select count(*) from " +
                    "emps  left join locations on emps.locationid = locations.locationid where emps.deptno < 5");
        }
    }

    // Multi Predicates
    @Test
    public void testRangePredicates2() {
        // !=
        {
            String mv = "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                    " left join locations on emps.locationid = locations.locationid where emps.deptno < 10 or emps.deptno > 10";
            testRewriteOK(mv, "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                    " left join locations on emps.locationid = locations.locationid where emps.deptno < 10 or emps.deptno > 10");
            testRewriteOK(mv, "select deptno as col1, empid as col2, emps.locationid as col3 from emps " +
                    " left join locations on emps.locationid = locations.locationid where emps.deptno < 5 or emps.deptno > 20");
        }
    }

    @Test
    public void testRangePredicates3() {
        {
            String mv = "select c1, c2, c3 from t1 where c1 > 0 or c2 > 0 or c3 > 0";
            String query = "select c1, c2, c3 from t1 where c1 > 0 or c2 > 0";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select c1, c2, c3 from t1 where c1 > 0 or c2 > 0 or c3 > 0";
            String query = "select c1, c2, c3 from t1 where c1 > 0 or c2 > 5";
            // TODO multi column OR predicate as range
            testRewriteOK(mv, query);
        }

        // multi range same column
        {
            String mv = "select c1, c2 from t1 where ((c1 >= 0 AND c1 <= 10) OR (c1 >= 20 AND c1 <= 30)) AND c3 = 1";
            String query = "select c1, c2 from t1 where ((c1 > 0 AND c1 < 10) OR (c1 > 20 AND c1 < 30)) AND c3 = 1";
            testRewriteOK(mv, query);
        }

        // test IN
        {
            String mv = "select c1, c2 from t1 where c1 IN (1, 2, 3)";
            String query = "select c1, c2 from t1 where c1 IN (1, 2)";
            testRewriteOK(mv, query);
        }

    }

    @Test
    public void testTimeSliceRewrite() throws Exception {
        starRocksAssert.withTable(" CREATE TABLE IF NOT EXISTS `t_time_slice` (\n" +
                "    `dt` datetime NOT NULL,\n" +
                "    `c1` int(11) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`dt`)\n" +
                "PARTITION BY RANGE(`dt`)\n" +
                "(\n" +
                "    PARTITION p1 VALUES [(\"2023-06-01\"), (\"2023-06-02\")),\n" +
                "    PARTITION p2 VALUES [(\"2023-06-02\"), (\"2023-06-03\")),\n" +
                "    PARTITION p3 VALUES [(\"2023-06-03\"), (\"2023-06-04\"))\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(`c1`) BUCKETS 1\n" +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ")");

        String mv = "SELECT time_slice(dt, interval 5 minute) as t, sum(c1) FROM t_time_slice GROUP BY t";
        testRewriteOK(mv, "SELECT time_slice(dt, interval 5 minute) as t FROM t_time_slice " +
                "WHERE dt BETWEEN '2023-06-01' AND '2023-06-02' GROUP BY t");
        starRocksAssert.dropTable("t_time_slice");
    }

    @Test
    public void testCountDistinctRollupAgg() throws Exception {
        {
            String mv = "select empid, deptno, locationid, count(distinct name) as s\n"
                    + "from emps group by empid, deptno, locationid";
            String query = "select empid, count(distinct name) as s\n"
                    + "from emps where deptno=1 and locationid=1 "
                    + "group by empid ";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select empid, deptno, locationid, count(distinct name) as s\n"
                    + "from emps group by empid, deptno, locationid";
            String query = "select empid, count(distinct name) as s\n"
                    + "from emps where deptno=1 \n"
                    + "group by empid ";
            testRewriteFail(mv, query);
        }
    }

    @Test
    public void testJoinDeriveRewrite() {
        // left outer join
        {
            // Equivalence class should not be used. because c_custkey is nullable in mv, but lo_custkey is
            // not nullable
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey";
            // left outer join will be converted to inner join because query has null-rejecting predicate: c_name = 'name'
            // c_name = 'name' is a null-rejecting predicate, so do not add the compensated predicate
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey where c_name = 'name'";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select lo_orderkey, c_name, sum(lo_revenue) as total_revenue" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey" +
                    " group by lo_orderkey, c_name";
            // left outer join will be converted to inner join because query has null-rejecting predicate: c_name = 'name'
            // c_name = 'name' is a null-rejecting predicate, so do not add the compensated predicate
            String query = "select lo_orderkey, c_name, sum(lo_revenue) as total_revenue" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey where c_name = 'name'" +
                    " group by lo_orderkey, c_name";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select lo_orderkey, c_custkey, sum(lo_revenue) as total_revenue" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey" +
                    " group by lo_orderkey, c_custkey";
            String query = "select lo_orderkey, c_custkey, sum(lo_revenue) as total_revenue" +
                    " from lineorder inner join customer" +
                    " on lo_custkey = c_custkey" +
                    " group by lo_orderkey, c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 28: c_custkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }

        {
            // the compensation predicate is c_custkey is not null, c_custkey should be in the output of mv
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey where c_name = 'name'";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 31: c_name = 'name'\n" +
                    "     partitions=1/1");
        }

        {
            // the compensation predicate is c_custkey is not null, c_custkey should be in the output of mv
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey where c_custkey = 1";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 30: c_custkey = 1\n" +
                    "     partitions=1/1");
        }

        {
            // the compensation predicate is c_custkey is not null, c_custkey should be in the output of mv
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder inner join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            // should contain c_custkey IS NOT NULL predicate
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 30: c_custkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey and lo_shipmode = c_name";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, lo_shipmode, c_name" +
                    " from lineorder inner join customer" +
                    " on lo_custkey = c_custkey and lo_shipmode = c_name";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 30: c_custkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey where c_name = 'name' and c_custkey = 100";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("0:OlapScanNode\n" +
                    "     TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 30: c_custkey = 100, 31: c_name = 'name'\n" +
                    "     partitions=1/1");
        }

        {
            // left anti join rewritten into left outer join with compensation predicate:
            // c_custkey is null
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue" +
                    " from lineorder left anti join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 30: c_custkey IS NULL\n" +
                    "     partitions=1/1");
        }

        // right outer join
        {
            // Equivalence class should not be used. because c_custkey is nullable in mv, but lo_custkey is
            // not nullable
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey";
            // left outer join will be converted to inner join because query has null-rejecting predicate: c_name = 'name'
            // c_name = 'name' is a null-rejecting predicate, so do not add the compensated predicate
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey where lo_orderkey = 10";
            testRewriteOK(mv, query);
        }

        {
            // the compensation predicate is c_custkey is not null, c_custkey should be in the output of mv
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey where lo_linenumber = 10";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 27: lo_linenumber = 10\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_custkey, sum(lo_revenue) as total_revenue" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey" +
                    " group by lo_orderkey, lo_custkey";
            // left outer join will be converted to inner join because query has null-rejecting predicate: c_name = 'name'
            // c_name = 'name' is a null-rejecting predicate, so do not add the compensated predicate
            String query = "select lo_orderkey, lo_custkey, sum(lo_revenue) as total_revenue" +
                    " from lineorder inner join customer" +
                    " on lo_custkey = c_custkey where lo_orderkey = 10" +
                    " group by lo_orderkey, lo_custkey";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select lo_custkey, c_name, sum(lo_revenue) as total_revenue" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey" +
                    " group by lo_custkey, c_name";
            String query = "select lo_custkey, c_name, sum(lo_revenue) as total_revenue" +
                    " from lineorder inner join customer" +
                    " on lo_custkey = c_custkey" +
                    " group by lo_custkey, c_name";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 27: lo_custkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey where lo_orderkey = 1";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 26: lo_orderkey = 1\n" +
                    "     partitions=1/1");
        }

        {
            // the compensation predicate is lo_custkey is not null, lo_custkey should be in the output of mv
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, c_name" +
                    " from lineorder inner join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            // should contain c_custkey IS NOT NULL predicate
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 30: lo_custkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, lo_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey and lo_shipmode = c_name";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, lo_shipmode, c_name" +
                    " from lineorder inner join customer" +
                    " on lo_custkey = c_custkey and lo_shipmode = c_name";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 31: lo_custkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }

        {
            // the compensation predicate is c_custkey is not null, c_custkey should be in the output of mv
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey where lo_linenumber = 10 and lo_quantity = 100";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 27: lo_linenumber = 10, 28: lo_quantity = 100\n" +
                    "     partitions=1/1");
        }

        {
            // right anti join rewritten into left outer join, with compensation predicate:
            // lo_custkey is null.
            // and lo_custkey should be in the output of mv
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_custkey, c_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select c_name" +
                    " from lineorder right anti join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 29: lo_custkey IS NULL\n" +
                    "     partitions=1/1");
        }

        // test inner join
        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, lo_shipmode, c_name" +
                    " from lineorder inner join customer_primary" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, lo_shipmode" +
                    " from lineorder left semi join customer_primary" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, lo_shipmode, c_name" +
                    " from lineorder inner join customer_unique" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, lo_shipmode" +
                    " from lineorder left semi join customer_unique" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, lo_shipmode, c_name" +
                    " from lineorder inner join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, lo_custkey, lo_shipmode" +
                    " from lineorder left semi join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, lo_shipmode, c_name" +
                    " from lineorder_primary inner join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select c_name" +
                    " from lineorder_primary right semi join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, lo_shipmode, c_name" +
                    " from lineorder_unique inner join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select c_name" +
                    " from lineorder_unique right semi join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, lo_shipmode, c_name" +
                    " from lineorder inner join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select c_name" +
                    " from lineorder right semi join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     partitions=1/1");
        }

        // test full outer join
        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder full outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder left outer join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 26: lo_orderkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder_null full outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder_null left outer join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 26: lo_orderkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder full outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder right outer join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 30: c_custkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder full outer join customer_null" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder right outer join customer_null" +
                    " on lo_custkey = c_custkey";
            testRewriteFail(mv, query);
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder full outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder inner join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 30: c_custkey IS NOT NULL, 26: lo_orderkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder full outer join customer_null" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder inner join customer_null" +
                    " on lo_custkey = c_custkey";
            testRewriteFail(mv, query);
        }

        {
            String mv = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder_null full outer join customer" +
                    " on lo_custkey = c_custkey";
            String query = "select lo_orderkey, lo_linenumber, lo_quantity, lo_revenue, c_custkey, c_name" +
                    " from lineorder_null inner join customer" +
                    " on lo_custkey = c_custkey";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("TABLE: mv0\n" +
                    "     PREAGGREGATION: ON\n" +
                    "     PREDICATES: 30: c_custkey IS NOT NULL, 26: lo_orderkey IS NOT NULL\n" +
                    "     partitions=1/1");
        }
    }

    @Test
    public void testRightOuterJoin() {
        {
            String q = "select empid, depts.deptno from emps\n"
                    + "right outer join depts using (deptno)";
            String m = "select empid, depts.deptno from emps\n"
                    + "right outer join depts using (deptno)";
            testRewriteOK(m, q);
        }

        {
            String q = "select empid, depts.deptno from emps\n"
                    + "right outer join depts using (deptno) where empid = 1";
            String m = "select empid, depts.deptno from emps\n"
                    + "right outer join depts using (deptno)";
            testRewriteOK(m, q);
        }
    }

    @Test
    public void testFullOuterJoin() {
        {
            String q = "select empid, depts.deptno from emps\n"
                    + "full outer join depts using (deptno)";
            String m = "select empid, depts.deptno from emps\n"
                    + "full outer join depts using (deptno)";
            testRewriteOK(m, q);
        }

        {
            String q = "select empid, depts.deptno from emps\n"
                    + "full outer join depts using (deptno) where empid = 1";
            String m = "select empid, depts.deptno from emps\n"
                    + "full outer join depts using (deptno)";
            testRewriteOK(m, q);
        }
    }

    @Test
    public void testLeftSemiJoinJoin() {
        {
            String q = "select empid from emps\n"
                    + "left semi join depts using (deptno)";
            String m = "select empid from emps\n"
                    + "left semi join depts using (deptno)";
            testRewriteOK(m, q);
        }

        {
            String q = "select empid from emps\n"
                    + "left semi join depts using (deptno) where empid = 1";
            String m = "select empid from emps\n"
                    + "left semi join depts using (deptno)";
            testRewriteOK(m, q);
        }
    }

    @Test
    public void testLeftAntiJoinJoin() {
        {
            String q = "select empid from emps\n"
                    + "left anti join depts using (deptno)";
            String m = "select empid from emps\n"
                    + "left anti join depts using (deptno)";
            testRewriteOK(m, q);
        }

        {
            String q = "select empid from emps\n"
                    + "left anti join depts using (deptno) where empid = 1";
            String m = "select empid from emps\n"
                    + "left anti join depts using (deptno)";
            testRewriteOK(m, q);
        }
    }

    @Test
    public void testRightSemiJoinJoin() {
        {
            String q = "select deptno from emps\n"
                    + "right semi join depts using (deptno)";
            String m = "select deptno from emps\n"
                    + "right semi join depts using (deptno)";
            testRewriteOK(m, q);
        }

        {
            String q = "select deptno from emps\n"
                    + "right semi join depts using (deptno) where deptno = 1";
            String m = "select deptno from emps\n"
                    + "right semi join depts using (deptno)";
            testRewriteOK(m, q);
        }
    }

    @Test
    public void testRightAntiJoinJoin() {
        {
            String q = "select deptno from emps\n"
                    + "left anti join depts using (deptno)";
            String m = "select deptno from emps\n"
                    + "left anti join depts using (deptno)";
            testRewriteOK(m, q);
        }

        {
            String q = "select deptno from emps\n"
                    + "right anti join depts using (deptno) where deptno = 1";
            String m = "select deptno from emps\n"
                    + "right anti join depts using (deptno)";
            testRewriteOK(m, q);
        }
    }

    @Test
    public void testMultiJoinTypes() {
        {
            testRewriteOK("select depts.deptno, dependents.empid\n"
                            + "from depts\n"
                            + "left outer join dependents on (depts.name = dependents.name)\n"
                            + "right outer join emps on (emps.deptno = depts.deptno)\n",
                    "select dependents.empid\n"
                            + "from depts\n"
                            + "left outer join dependents on (depts.name = dependents.name)\n"
                            + "right outer join emps on (emps.deptno = depts.deptno)\n"
                            + "where depts.deptno > 10");
        }

        {
            testRewriteOK("select depts.deptno, dependents.empid\n"
                            + "from depts\n"
                            + "left outer join dependents on (depts.name = dependents.name)\n"
                            + "right outer join emps on (emps.deptno = depts.deptno)\n"
                            + "where depts.deptno > 10",
                    "select dependents.empid\n"
                            + "from depts\n"
                            + "left outer join dependents on (depts.name = dependents.name)\n"
                            + "right outer join emps on (emps.deptno = depts.deptno)\n"
                            + "where depts.deptno > 10");
        }

        {
            testRewriteOK("select depts.deptno, dependents.empid\n"
                            + "from depts\n"
                            + "full outer join dependents on (depts.name = dependents.name)\n"
                            + "right outer join emps on (emps.deptno = depts.deptno)\n",
                    "select dependents.empid\n"
                            + "from depts\n"
                            + "full outer join dependents on (depts.name = dependents.name)\n"
                            + "right outer join emps on (emps.deptno = depts.deptno)\n"
                            + "where depts.deptno > 10");
        }

        {
            testRewriteOK("select depts.deptno\n"
                            + "from depts\n"
                            + "left semi join dependents on (depts.name = dependents.name)\n"
                            + "left anti join emps on (emps.deptno = depts.deptno)\n",
                    "select depts.deptno\n"
                            + "from depts\n"
                            + "left semi join dependents on (depts.name = dependents.name)\n"
                            + "left anti join emps on (emps.deptno = depts.deptno)\n"
                            + "where depts.deptno > 10");
        }

        {
            testRewriteOK("select emps.empid\n"
                            + "from depts\n"
                            + "left semi join dependents on (depts.name = dependents.name)\n"
                            + "right anti join emps on (emps.deptno = depts.deptno)\n",
                    "select emps.empid\n"
                            + "from depts\n"
                            + "left semi join dependents on (depts.name = dependents.name)\n"
                            + "right anti join emps on (emps.deptno = depts.deptno)\n"
                            + "where emps.empid > 10");
        }
    }

    @Test
    public void testNestedAggregateBelowJoin2() throws Exception {
        String mv1 = "create materialized view mv1 \n" +
                "distributed by random \n" +
                "refresh async\n" +
                "as select empid, deptno, locationid, \n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid, deptno, locationid ";
        String mv2 = "create materialized view mv2 \n" +
                "distributed by random \n" +
                "refresh async\n" +
                "as select sum(total) as sum, t2.locationid, t2.empid, t2.deptno  from \n" +
                "(select empid, deptno, t.locationid, total, cnt from mv1 t join locations \n" +
                "on t.locationid = locations.locationid) t2\n" +
                "group by t2.locationid, t2.empid, t2.deptno";
        starRocksAssert.withMaterializedView(mv1);
        starRocksAssert.withMaterializedView(mv2);

        sql("select sum(total) as sum, t.locationid  from \n" +
                "(select locationid, \n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps where empid = 2 and deptno = 10 group by locationid) t join locations \n" +
                "on t.locationid = locations.locationid\n" +
                "group by t.locationid")
                .match("mv2");
        starRocksAssert.dropMaterializedView("mv1");
        starRocksAssert.dropMaterializedView("mv2");
    }

    @Test
    public void testOrPredicates() {
        {
            String mv = "select * from lineorder where not (lo_orderkey > 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey <= 10000 and lo_linenumber <= 5000";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where not (lo_orderkey > 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey <= 1000 and lo_linenumber <= 500";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("PREDICATES: 18: lo_orderkey <= 1000, 19: lo_linenumber <= 500");
        }

        {
            String mv = "select * from lineorder where lo_orderkey > 10000 or lo_linenumber > 5000";
            String query = "select * from lineorder where not( lo_orderkey <= 10000 and lo_linenumber <= 5000)";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where lo_orderkey > 10000 or lo_linenumber > 5000";
            String query = "select * from lineorder where not( lo_orderkey <= 20000 and lo_linenumber <= 10000)";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("PREDICATES: (18: lo_orderkey > 20000) OR (19: lo_linenumber > 10000)");
        }

        {
            String mv = "select * from lineorder where (lo_orderkey > 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey > 10000";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("PREDICATES: 18: lo_orderkey > 10000");
        }

        {
            String mv = "select * from lineorder where (lo_orderkey > 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey > 10000 or lo_linenumber > 5000";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.notContain("PREDICATES");
        }

        {
            String mv = "select * from lineorder where (lo_orderkey > 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey > 15000";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where (lo_orderkey > 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey > 10001";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where (lo_orderkey > 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey > 9999";
            testRewriteFail(mv, query);
        }

        {
            String mv = "select * from lineorder where (lo_orderkey >= 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey >= 10000";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where (lo_orderkey >= 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey > 10000";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where (lo_orderkey >= 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey = 10000";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where (lo_orderkey > 10000 or lo_linenumber > 5000)";
            String query = "select * from lineorder where lo_orderkey > 15000 and lo_linenumber > 6000";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where lo_orderkey > 10000";
            String query = "select * from lineorder where lo_orderkey > 15000 and lo_linenumber > 6000";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where (lo_orderkey > 10000 and lo_linenumber > 5000) or (lo_orderkey < 1000 and lo_linenumber < 2000)";
            String query = "select * from lineorder where lo_orderkey > 15000 and lo_linenumber > 6000";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where (lo_orderkey > 10000 and lo_linenumber > 5000) or (lo_orderkey < 1000 and lo_linenumber < 2000)";
            String query = "select * from lineorder where lo_orderkey < 1000 and lo_linenumber < 2000";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("PREDICATES: 18: lo_orderkey < 1000, 19: lo_linenumber < 2000");
        }

        {
            String mv = "select * from lineorder where (lo_orderkey > 10000 or lo_linenumber > 5000) and (lo_orderkey < 1000 or lo_linenumber < 2000)";
            String query = "select * from lineorder where lo_orderkey > 15000 and lo_linenumber < 1000";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("PREDICATES: 18: lo_orderkey > 15000, 19: lo_linenumber < 1000");
        }

        {
            String mv = "select * from lineorder where (lo_orderkey > 10000 or lo_orderkey < 5000) and (lo_linenumber > 10000 or lo_linenumber < 2000)";
            String query = "select * from lineorder where lo_orderkey > 15000 and lo_linenumber < 1000";
            testRewriteOK(mv, query);
        }
    }

    @Test
    public void testJoinDerive() throws Exception {
        starRocksAssert.withTable("CREATE TABLE t5 (\n" +
                "                k1 int,\n" +
                "                k2 int not null\n" +
                "            )\n" +
                "            DUPLICATE KEY(k1) " +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ")\n");
        starRocksAssert.withTable("CREATE TABLE t4 (\n" +
                "                a int,\n" +
                "                b int not null\n" +
                "            )\n" +
                "            DUPLICATE KEY(a) " +
                "PROPERTIES (\n" +
                "    \"replication_num\" = \"1\",\n" +
                "    \"in_memory\" = \"false\"\n" +
                ")\n");
        testRewriteOK("select * from t5 full outer join t4 on k1=a",
                "select * from t5 left outer join t4 on k1=a where k1=3;");
    }

    @Test
    public void testComplexExpressionRewrite() {
        {
            String mv = "select" +
                    " case" +
                    "    when lo_custkey is not null then lo_custkey" +
                    "    when lo_partkey is not null then lo_partkey" +
                    "    else null" +
                    " end as f1," +
                    " lo_linenumber is null as f2," +
                    " lo_shipmode like 'mode' as f3," +
                    " not lo_revenue > 0 as f4," +
                    " lo_revenue between 100 and 200 as f5," +
                    " lo_orderdate in (20230101, 20230102) as f6" +
                    " from lineorder_null";
            String query = "select" +
                    " case" +
                    "    when lo_custkey is not null then lo_custkey" +
                    "    when lo_partkey is not null then lo_partkey" +
                    "    else null" +
                    " end as f1," +
                    " lo_linenumber is null as f2," +
                    " lo_shipmode like 'mode' as f3," +
                    " not lo_revenue > 0 as f4," +
                    " lo_revenue between 100 and 200 as f5," +
                    " lo_orderdate in (20230101, 20230102) as f6" +
                    " from lineorder_null";
            testRewriteOK(mv, query);
        }
        {
            String mv = "select * from lineorder";
            String query = "select * from lineorder where lo_custkey is not null";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder";
            String query = "select * from lineorder where lo_shipmode like 'mode'";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder";
            String query = "select * from lineorder where lo_orderdate in (20230101, 20230102)";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder";
            String query = "select * from lineorder where lo_revenue between 100 and 200";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder";
            String query = "select * from lineorder where not lo_revenue > 0";
            testRewriteOK(mv, query);
        }
    }

    @Test
    public void testEmptyPartitionPrune() throws Exception {
        starRocksAssert.withTable("\n" +
                "CREATE TABLE test_empty_partition_tbl(\n" +
                "  `dt` datetime DEFAULT NULL,\n" +
                "  `col1` bigint(20) DEFAULT NULL,\n" +
                "  `col2` bigint(20) DEFAULT NULL,\n" +
                "  `col3` bigint(20) DEFAULT NULL,\n" +
                "  `error_code` varchar(1048576) DEFAULT NULL\n" +
                ")\n" +
                "DUPLICATE KEY (dt, col1)\n" +
                "PARTITION BY date_trunc('day', dt)\n" +
                "--DISTRIBUTED BY RANDOM BUCKETS 32\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");");
        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW  test_empty_partition_mv1 \n" +
                "DISTRIBUTED BY HASH(col1, dt) BUCKETS 32\n" +
                "--DISTRIBUTED BY RANDOM BUCKETS 32\n" +
                "partition by date_trunc('day', dt)\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ")\n" +
                "AS select\n" +
                "      col1,\n" +
                "        dt,\n" +
                "        sum(col2) AS sum_col2,\n" +
                "        sum(if(error_code = 'TIMEOUT', col3, 0)) AS sum_col3\n" +
                "    FROM\n" +
                "        test_empty_partition_tbl AS f\n" +
                "    GROUP BY\n" +
                "        col1,\n" +
                "        dt;");
        String sql = "select\n" +
                "      col1,\n" +
                "        sum(col2) AS sum_col2,\n" +
                "        sum(if(error_code = 'TIMEOUT', col3, 0)) AS sum_col3\n" +
                "    FROM\n" +
                "        test_empty_partition_tbl AS f\n" +
                "    WHERE (dt >= STR_TO_DATE('2023-08-15 00:00:00', '%Y-%m-%d %H:%i:%s'))\n" +
                "        AND (dt <= STR_TO_DATE('2023-08-15 00:00:00', '%Y-%m-%d %H:%i:%s'))\n" +
                "    GROUP BY col1;";
        String plan = getFragmentPlan(sql);
        PlanTestBase.assertContains(plan, "test_empty_partition_mv1");
    }

    @Test
    public void testAggWithoutRollup() throws Exception {
        try {
            starRocksAssert.withTable("create table dim_test_sr_table (\n" +
                    "fplat_form_itg2 bigint,\n" +
                    "fplat_form_itg2_name string\n" +
                    ")DISTRIBUTED BY HASH(fplat_form_itg2)\n" +
                    "PROPERTIES (\n" +
                    "\"replication_num\" = \"1\"\n" +
                    ");\n" +
                    "\n");

            starRocksAssert.withTable("CREATE TABLE test_sr_table_join(\n" +
                    "fdate int,\n" +
                    "fetl_time BIGINT ,\n" +
                    "facct_type BIGINT ,\n" +
                    "fqqid STRING ,\n" +
                    "fplat_form_itg2 BIGINT ,\n" +
                    "funit BIGINT ,\n" +
                    "flcnt BIGINT\n" +
                    ")PARTITION BY range(fdate) (\n" +
                    "PARTITION p1 VALUES [ (\"20230702\"),(\"20230703\")),\n" +
                    "PARTITION p2 VALUES [ (\"20230703\"),(\"20230704\")),\n" +
                    "PARTITION p3 VALUES [ (\"20230704\"),(\"20230705\")),\n" +
                    "PARTITION p4 VALUES [ (\"20230705\"),(\"20230706\"))\n" +
                    ")\n" +
                    "DISTRIBUTED BY HASH(fqqid)\n" +
                    "PROPERTIES (\n" +
                    "\"replication_num\" = \"1\"\n" +
                    ");");

            String mv = "select" +
                    " t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                    "FROM test_sr_table_join t1\n" +
                    "LEFT JOIN dim_test_sr_table t2\n" +
                    "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                    "WHERE t1.fdate >= 20230702 and t1.fdate <= 20230705\n" +
                    "GROUP BY fdate, fplat_form_itg2_name;";

            String query = "select" +
                    " t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                    "FROM test_sr_table_join t1\n" +
                    "LEFT JOIN dim_test_sr_table t2\n" +
                    "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                    "WHERE t1.fdate = 20230705\n" +
                    "GROUP BY fplat_form_itg2_name;";
            testRewriteOK(mv, query);
        } finally {
            starRocksAssert.dropTable("test_sr_table_join");
            starRocksAssert.dropTable("dim_test_sr_table");
        }
    }

    @Test
    public void testRangePredicate() {
        // integer
        {
            String mv = "select * from lineorder where lo_orderkey < 10001";
            String query = "select * from lineorder where lo_orderkey <= 10000";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where lo_orderkey < 2147483647";
            String query = "select * from lineorder where lo_orderkey < 2147483647";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where lo_orderkey <= 2147483646";
            String query = "select * from lineorder where lo_orderkey < 2147483647";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where lo_orderkey < 2147483647";
            String query = "select * from lineorder where lo_orderkey <= 2147483646";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where lo_orderkey >= 2147483647";
            String query = "select * from lineorder where lo_orderkey > 2147483646";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where lo_orderkey > 2147483646";
            String query = "select * from lineorder where lo_orderkey >= 2147483647";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where lo_orderkey > -2147483648";
            String query = "select * from lineorder where lo_orderkey >= -2147483647";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where lo_orderkey >= -2147483647";
            String query = "select * from lineorder where lo_orderkey > -2147483648";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from lineorder where lo_orderkey < -2147483647";
            String query = "select * from lineorder where lo_orderkey <= -2147483648";
            testRewriteOK(mv, query);
        }

        // small int
        {
            String mv = "select * from test.test_all_type where t1b < 100";
            String query = "select * from test.test_all_type where t1b <= 99";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where t1b <= 99";
            String query = "select * from test.test_all_type where t1b < 100";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where t1b < 32767";
            String query = "select * from test.test_all_type where t1b < 32767";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where t1b <= 32766";
            String query = "select * from test.test_all_type where t1b < 32767";
            testRewriteOK(mv, query);
        }

        {
            String query = "select * from test.test_all_type where t1b <= 32766";
            String mv = "select * from test.test_all_type where t1b < 32767";
            testRewriteOK(mv, query);
        }

        {
            String query = "select * from test.test_all_type where t1b >= 32767";
            String mv = "select * from test.test_all_type where t1b > 32766";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where t1b >= 32767";
            String query = "select * from test.test_all_type where t1b > 32766";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where t1b > -32768";
            String query = "select * from test.test_all_type where t1b >= -32767";
            testRewriteOK(mv, query);
        }

        {
            String query = "select * from test.test_all_type where t1b > -32768";
            String mv = "select * from test.test_all_type where t1b >= -32767";
            testRewriteOK(mv, query);
        }

        {
            String query = "select * from test.test_all_type where t1b < -32767";
            String mv = "select * from test.test_all_type where t1b <= -32768";
            testRewriteOK(mv, query);
        }

        // bigint
        {
            String mv = "select * from test.test_all_type where t1d < 100";
            String query = "select * from test.test_all_type where t1d <= 99";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where t1d <= 99";
            String query = "select * from test.test_all_type where t1d < 100";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where t1d < 9223372036854775807";
            String query = "select * from test.test_all_type where t1d < 9223372036854775807";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where t1d <= 9223372036854775806";
            String query = "select * from test.test_all_type where t1d < 9223372036854775807";
            testRewriteOK(mv, query);
        }

        {
            String query = "select * from test.test_all_type where t1d <= 9223372036854775806";
            String mv = "select * from test.test_all_type where t1d < 9223372036854775807";
            testRewriteOK(mv, query);
        }

        {
            String query = "select * from test.test_all_type where t1d >= 9223372036854775807";
            String mv = "select * from test.test_all_type where t1d > 9223372036854775806";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where t1d >= 9223372036854775807";
            String query = "select * from test.test_all_type where t1d > 9223372036854775806";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where t1d > -9223372036854775808";
            String query = "select * from test.test_all_type where t1d >= -9223372036854775807";
            testRewriteOK(mv, query);
        }

        {
            String query = "select * from test.test_all_type where t1d > -9223372036854775808";
            String mv = "select * from test.test_all_type where t1d >= -9223372036854775807";
            testRewriteOK(mv, query);
        }

        {
            String query = "select * from test.test_all_type where t1d <= -9223372036854775808";
            String mv = "select * from test.test_all_type where t1d < -9223372036854775807";
            testRewriteOK(mv, query);
        }

        // date
        {
            String mv = "select * from test.test_all_type where id_date < '2023-08-10'";
            String query = "select * from test.test_all_type where id_date <= '2023-08-09'";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where id_date <= '2023-08-09'";
            String query = "select * from test.test_all_type where id_date < '2023-08-10'";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where id_date > '2023-08-10'";
            String query = "select * from test.test_all_type where id_date >= '2023-08-11'";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where id_date >= '2023-08-10'";
            String query = "select * from test.test_all_type where id_date > '2023-08-11'";
            testRewriteOK(mv, query);
        }

        // datetime
        {
            String mv = "select * from test.test_all_type where id_datetime < '2023-08-10 12:00:01'";
            String query = "select * from test.test_all_type where id_datetime <= '2023-08-10 12:00:00'";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where id_datetime <= '2023-08-10 12:00:00'";
            String query = "select * from test.test_all_type where id_datetime < '2023-08-10 12:00:01'";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where id_datetime > '2023-08-10 12:00:01'";
            String query = "select * from test.test_all_type where id_datetime >= '2023-08-10 12:00:02'";
            testRewriteOK(mv, query);
        }

        {
            String mv = "select * from test.test_all_type where id_datetime >= '2023-08-10 12:00:01'";
            String query = "select * from test.test_all_type where id_datetime > '2023-08-10 12:00:00'";
            testRewriteOK(mv, query);
        }
    }

    @Test
    public void testJoinWithToBitmapRewrite() throws Exception {
        try {
            String table1 = "CREATE TABLE test_sr_table_join(\n" +
                    "fdate int,\n" +
                    "fetl_time BIGINT ,\n" +
                    "facct_type BIGINT ,\n" +
                    "userid STRING ,\n" +
                    "fplat_form_itg2 BIGINT ,\n" +
                    "funit BIGINT ,\n" +
                    "flcnt BIGINT\n" +
                    ")PARTITION BY range(fdate) (\n" +
                    "PARTITION p1 VALUES [ (\"20230702\"),(\"20230703\")),\n" +
                    "PARTITION p2 VALUES [ (\"20230703\"),(\"20230704\")),\n" +
                    "PARTITION p3 VALUES [ (\"20230704\"),(\"20230705\")),\n" +
                    "PARTITION p4 VALUES [ (\"20230705\"),(\"20230706\"))\n" +
                    ")\n" +
                    "DISTRIBUTED BY HASH(userid)\n" +
                    "PROPERTIES (\n" +
                    "\"replication_num\" = \"1\"\n" +
                    ");";
            starRocksAssert.withTable(table1);
            String table2 = "create table dim_test_sr_table (\n" +
                    "fplat_form_itg2 bigint,\n" +
                    "fplat_form_itg2_name string\n" +
                    ")DISTRIBUTED BY HASH(fplat_form_itg2)\n" +
                    "PROPERTIES (\n" +
                    "\"replication_num\" = \"1\"\n" +
                    ");";
            starRocksAssert.withTable(table2);

            OlapTable t4 = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(MATERIALIZED_DB_NAME)
                    .getTable("test_sr_table_join");
            setTableStatistics(t4, 150000);

            String mv = "select t1.fdate, t2.fplat_form_itg2_name," +
                    " BITMAP_UNION(to_bitmap(abs(MURMUR_HASH3_32(t1.userid)))) AS index_0_8228," +
                    " sum(t1.flcnt)as index_xxx\n" +
                    "FROM test_sr_table_join t1\n" +
                    "LEFT JOIN dim_test_sr_table t2\n" +
                    "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                    "WHERE t1.fdate >= 20230702 and t1.fdate < 20230706\n" +
                    "GROUP BY fdate, fplat_form_itg2_name;";
            String query = "select t2.fplat_form_itg2_name," +
                    " BITMAP_UNION_COUNT(to_bitmap(abs(MURMUR_HASH3_32(t1.userid)))) AS index_0_8228\n" +
                    "FROM test_sr_table_join t1\n" +
                    "LEFT JOIN dim_test_sr_table t2\n" +
                    "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                    "WHERE t1.fdate >= 20230703 and t1.fdate < 20230706\n" +
                    "GROUP BY fplat_form_itg2_name;";
            testRewriteOK(mv, query);
        } finally {
            starRocksAssert.dropTable("test_sr_table_join");
            starRocksAssert.dropTable("dim_test_sr_table");
        }
    }

    @Test
    public void testCountRewrite() throws Exception {
        starRocksAssert.withTable("CREATE TABLE count_tbl_1 (\n" +
                "k1 int,\n" +
                "k2 int\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(k1)" +
                "PROPERTIES (" +
                "\"replication_num\" = \"1\")\n");
        testRewriteNonmatch("SELECT count(distinct k1) FROM count_tbl_1", "select count(*) from count_tbl_1");
        starRocksAssert.dropTable("count_tbl_1");
    }

    @Test
    public void testRangePredicateRewrite() throws Exception {
        // range predicate with partition prune
        {
            // test int type
            starRocksAssert.withTable("create table dim_test_sr_table (\n" +
                    "fplat_form_itg2 bigint,\n" +
                    "fplat_form_itg2_name string\n" +
                    ")DISTRIBUTED BY HASH(fplat_form_itg2)\n" +
                    "PROPERTIES (\n" +
                    "\"replication_num\" = \"1\"\n" +
                    ");\n" +
                    "\n");

            starRocksAssert.withTable("CREATE TABLE test_sr_table_join(\n" +
                    "fdate int,\n" +
                    "fetl_time BIGINT ,\n" +
                    "facct_type BIGINT ,\n" +
                    "fqqid STRING ,\n" +
                    "fplat_form_itg2 BIGINT ,\n" +
                    "funit BIGINT ,\n" +
                    "flcnt BIGINT\n" +
                    ")PARTITION BY range(fdate) (\n" +
                    "PARTITION p1 VALUES [ (\"20230702\"),(\"20230703\")),\n" +
                    "PARTITION p2 VALUES [ (\"20230703\"),(\"20230704\")),\n" +
                    "PARTITION p3 VALUES [ (\"20230704\"),(\"20230705\")),\n" +
                    "PARTITION p4 VALUES [ (\"20230705\"),(\"20230706\"))\n" +
                    ")\n" +
                    "DISTRIBUTED BY HASH(fqqid)\n" +
                    "PROPERTIES (\n" +
                    "\"replication_num\" = \"1\"\n" +
                    ");\n" +
                    "\n");
            {
                String mv = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230702 and t1.fdate <= 20230705\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                String query = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230703 and t1.fdate <= 20230705\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                testRewriteOK(mv, query);
            }

            {
                String mv = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230702 and t1.fdate < 20230706\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                String query = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230703 and t1.fdate <= 20230705\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                testRewriteOK(mv, query);
            }

            {
                String mv = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230702 and t1.fdate < 20230706\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                String query = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230703 and t1.fdate < 20230706\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                testRewriteOK(mv, query);
            }

            {
                String mv = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230702 and t1.fdate <= 20230705\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                String query = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230703 and t1.fdate < 20230706\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                testRewriteOK(mv, query);
            }
            starRocksAssert.dropTable("dim_test_sr_table");
            starRocksAssert.dropTable("test_sr_table_join");
        }

        {
            // test date type
            starRocksAssert.withTable("create table dim_test_sr_table (\n" +
                    "fplat_form_itg2 bigint,\n" +
                    "fplat_form_itg2_name string\n" +
                    ")DISTRIBUTED BY HASH(fplat_form_itg2)\n" +
                    "PROPERTIES (\n" +
                    "\"replication_num\" = \"1\"\n" +
                    ");\n" +
                    "\n");

            starRocksAssert.withTable("CREATE TABLE test_sr_table_join(\n" +
                    "fdate date,\n" +
                    "fetl_time BIGINT ,\n" +
                    "facct_type BIGINT ,\n" +
                    "fqqid STRING ,\n" +
                    "fplat_form_itg2 BIGINT ,\n" +
                    "funit BIGINT ,\n" +
                    "flcnt BIGINT\n" +
                    ")PARTITION BY range(fdate) (\n" +
                    "PARTITION p1 VALUES [ (\"20230702\"),(\"20230703\")),\n" +
                    "PARTITION p2 VALUES [ (\"20230703\"),(\"20230704\")),\n" +
                    "PARTITION p3 VALUES [ (\"20230704\"),(\"20230705\")),\n" +
                    "PARTITION p4 VALUES [ (\"20230705\"),(\"20230706\"))\n" +
                    ")\n" +
                    "DISTRIBUTED BY HASH(fqqid)\n" +
                    "PROPERTIES (\n" +
                    "\"replication_num\" = \"1\"\n" +
                    ");\n" +
                    "\n");
            {
                String mv = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230702 and t1.fdate <= 20230705\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                String query = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230703 and t1.fdate <= 20230705\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                testRewriteOK(mv, query);
            }

            {
                String mv = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230702 and t1.fdate < 20230706\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                String query = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230703 and t1.fdate <= 20230705\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                testRewriteOK(mv, query);
            }

            {
                String mv = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230702 and t1.fdate < 20230706\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                String query = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230703 and t1.fdate < 20230706\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                testRewriteOK(mv, query);
            }

            {
                String mv = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230702 and t1.fdate <= 20230705\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                String query = "select t1.fdate, t2.fplat_form_itg2_name, count(DISTINCT t1.fqqid) AS index_0_8228, sum(t1.flcnt)as index_xxx\n" +
                        "FROM test_sr_table_join t1\n" +
                        "LEFT JOIN dim_test_sr_table t2\n" +
                        "ON t1.fplat_form_itg2 = t2.fplat_form_itg2\n" +
                        "WHERE t1.fdate >= 20230703 and t1.fdate < 20230706\n" +
                        "GROUP BY fdate, fplat_form_itg2_name";
                testRewriteOK(mv, query);
            }
            starRocksAssert.dropTable("dim_test_sr_table");
            starRocksAssert.dropTable("test_sr_table_join");
        }
    }

    @Test
    public void testMvOnHiveView() throws Exception {
        starRocksAssert.withMaterializedView("create materialized view mv_on_hive_view_1 " +
                "DISTRIBUTED by hash(c_custkey) buckets 10" +
                " properties (" +
                "\"replication_num\" = \"1\",\n" +
                "\"force_external_table_query_rewrite\" = \"TRUE\"\n" +
                ")\n" +
                "As select * from hive0.tpch.customer_view;");
        String query = "select * from hive0.tpch.customer_view";
        String plan = getFragmentPlan(query);
        PlanTestBase.assertContains(plan, "mv_on_hive_view_1");
        starRocksAssert.dropMaterializedView("mv_on_hive_view_1");
    }

    @Test
    public void testHiveViewDeltaJoinUKFK_Constraint_CaseInsensitive1() {
        // constraints are upper case.
        String mv = "select a.c1, a.c2 from\n"
                + "(select * from hive0.partitioned_db.t1 where c1 = 1) a\n"
                + "join hive0.partitioned_db2.t2 using (c2)";
        String query = "select c2 from hive0.partitioned_db.t1 where c1 = 1";
        // catalog name is case-sensitive.
        String constraint = "\"unique_constraints\" = \"hive0.PARTITIONED_DB2.T2.C2\"," +
                "\"foreign_key_constraints\" = \"hive0.PARTITIONED_DB.T1(C2) references hive0.PARTITIONED_DB2.T2(C2)\" ";
        testRewriteOK(mv, query, constraint)
                .contains("0:OlapScanNode\n" +
                        "     TABLE: mv0\n" +
                        "     PREAGGREGATION: ON\n");
    }

    @Test
    public void testHiveViewDeltaJoinUKFK_Constraint_CaseInsensitive2() {
        // MV and sql are both upper case.
        String mv = "SELECT T1.C1, L.C2 AS C2_1, R.C2 AS C2_2, R.C3 FROM hive0.PARTITIONED_DB.T1 " +
                "JOIN hive0.PARTITIONED_DB2.T2 L ON T1.C2= L.C2 " +
                "JOIN hive0.PARTITIONED_DB2.T2 R ON T1.C3 = R.C2";
        String query = "SELECT T1.C1, T2.C2 FROM hive0.PARTITIONED_DB.T1 JOIN hive0.PARTITIONED_DB2.T2 ON T1.C2 = T2.C2";
        String constraint = "\"unique_constraints\" = \"hive0.PARTITIONED_DB2.T2.C2\"," +
                "\"foreign_key_constraints\" = \"hive0.PARTITIONED_DB.T1(C2) references hive0.PARTITIONED_DB2.T2(C2); " +
                "hive0.PARTITIONED_DB.T1(C3) references hive0.PARTITIONED_DB2.T2(C2)\" ";
        testRewriteOK(mv, query, constraint)
                .contains("0:OlapScanNode\n" +
                        "     TABLE: mv0\n" +
                        "     PREAGGREGATION: ON\n");
    }

    @Test
    public void testHiveViewDeltaJoinUKFK_Constraint_CaseInsensitive3() {
        // MV is upper case, but sql is lower case.
        String mv = "SELECT T1.C1, L.C2 AS C2_1, R.C2 AS C2_2, R.C3 FROM hive0.PARTITIONED_DB.T1 " +
                "JOIN hive0.PARTITIONED_DB2.T2 L ON T1.C2= L.C2 " +
                "JOIN hive0.PARTITIONED_DB2.T2 R ON T1.C3 = R.C2";
        String query = "select t1.c1, t2.c2 from hive0.partitioned_db.t1 join hive0.partitioned_db2.t2 on t1.c2 = t2.c2";
        String constraint = "\"unique_constraints\" = \"hive0.PARTITIONED_DB2.T2.C2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2); " +
                "hive0.partitioned_db.t1(c3) references hive0.partitioned_db2.t2(c2)\" ";
        testRewriteOK(mv, query, constraint)
                .contains("0:OlapScanNode\n" +
                        "     TABLE: mv0\n" +
                        "     PREAGGREGATION: ON\n");
    }

    @Test
    public void testHiveViewDeltaJoinUKFK_Constraint_CaseInsensitive4() {
        // sql is upper case, but MV is lower case.
        String mv = "select t1.c1, l.c2 as c2_1, r.c2 as c2_2, r.c3 from hive0.partitioned_db.t1 " +
                "join hive0.partitioned_db2.t2 l on t1.c2= l.c2 " +
                "join hive0.partitioned_db2.t2 r on t1.c3 = r.c2";
        String query = "SELECT T1.C1, T2.C2 FROM hive0.PARTITIONED_DB.T1 JOIN hive0.PARTITIONED_DB2.T2 ON T1.C2 = T2.C2";
        String constraint = "\"unique_constraints\" = \"hive0.PARTITIONED_DB2.T2.C2\"," +
                "\"foreign_key_constraints\" = \"hive0.partitioned_db.t1(c2) references hive0.partitioned_db2.t2(c2); " +
                "hive0.partitioned_db.t1(c3) references hive0.partitioned_db2.t2(c2)\" ";
        testRewriteOK(mv, query, constraint)
                .contains("0:OlapScanNode\n" +
                        "     TABLE: mv0\n" +
                        "     PREAGGREGATION: ON\n");
    }

    @Test
    public void testOlapTable_ViewDeltaJoin_CaseSensitive1() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps_no_constraint emps\n"
                + "join dependents using (empid)"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "where emps.empid = 1";
        String query = "select empid, emps.deptno from emps_no_constraint emps join depts b on (emps.deptno=b.deptno) \n"
                + "where empid = 1";
        // Olap Table doesn't support case-insensitive.
        String constraint = "\"unique_constraints\" = \"DEPENDENTS.empid\"," +
                "\"foreign_key_constraints\" = \"emps_no_constraint(empid) references dependents(empid)\" ";
        try {
            rewrite(mv, query, constraint);
            Assertions.fail();
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("test_mv.DEPENDENTS does not exist."));
        }
    }

    @Test
    public void testOlapTable_ViewDeltaJoin_CaseSensitive2() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps_no_constraint emps\n"
                + "join dependents using (empid)"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "where emps.empid = 1";
        String query = "select empid, emps.deptno from emps_no_constraint emps join depts b on (emps.deptno=b.deptno) \n"
                + "where empid = 1";
        // Olap Table doesn't support case-insensitive.
        String constraint = "\"unique_constraints\" = \"dependents.empid\"," +
                "\"foreign_key_constraints\" = \"EMPS_NO_CONSTRAINT(empid) references DEPENDENTS(empid)\" ";
        try {
            rewrite(mv, query, constraint);
            Assertions.fail();
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("table:DEPENDENTS do not exist."));
        }
    }

    @Test
    public void testOlapTable_ViewDeltaJoin_CaseSensitive3() {
        String mv = "select emps.empid, emps.deptno, dependents.name from emps_no_constraint emps\n"
                + "join dependents using (empid)"
                + "inner join depts b on (emps.deptno=b.deptno)\n"
                + "where emps.empid = 1";
        String query = "SELECT EMPID, EMPS.DEPTNO FROM EMPS_NO_CONSTRAINT EMPS JOIN DEPTS B ON (EMPS.DEPTNO=B.DEPTNO) \n"
                + "WHERE EMPID = 1";
        // Olap Table doesn't support case-insensitive.
        String constraint = "\"unique_constraints\" = \"dependents.empid\"," +
                "\"foreign_key_constraints\" = \"emps_no_constraint(empid) references dependents(empid)\" ";
        try {
            rewrite(mv, query, constraint);
            Assertions.fail();
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Unknown table 'test_mv.EMPS_NO_CONSTRAINT'."));
        }
    }

    @Test
    public void testOlapTable_ViewDeltaJoin_CaseSensitive4() {
        try {
            String mv = "SELECT EMPS.EMPID, EMPS.DEPTNO, DEPENDENTS.NAME FROM EMPS_NO_CONSTRAINT EMPS\n"
                    + "JOIN DEPENDENTS USING (EMPID)"
                    + "INNER JOIN DEPTS B ON (EMPS.DEPTNO=B.DEPTNO)\n"
                    + "WHERE EMPS.EMPID = 1";
            String query = "SELECT EMPID, EMPS.DEPTNO FROM EMPS_NO_CONSTRAINT EMPS JOIN DEPTS B ON (EMPS.DEPTNO=B.DEPTNO) \n"
                    + "WHERE EMPID = 1";
            // Olap Table doesn't support case-insensitive.
            String constraint = "\"unique_constraints\" = \"dependents.empid\"," +
                    "\"foreign_key_constraints\" = \"emps_no_constraint(empid) references dependents(empid)\" ";
            rewrite(mv, query, constraint);
            Assertions.fail();
        } catch (Exception e) {
            Assertions.assertTrue(e.getMessage().contains("Unknown table 'test_mv.EMPS_NO_CONSTRAINT'."));
        }
    }

    @Test
    public void testViewDeltaJoinOnSubquery() throws Exception {
        // test bushy join view delta join rewrite
        starRocksAssert.withTable("CREATE TABLE `table_a` (\n" +
                "  `strategy_id` varchar(65533) DEFAULT NULL,\n" +
                "  `trace_id` varchar(65533) DEFAULT NULL,\n" +
                "  `becif_no` varchar(65533) DEFAULT NULL,\n" +
                "  `pv` int(11) DEFAULT NULL,\n" +
                "  `dt` varchar(65533) DEFAULT NULL\n" +
                ")");
        starRocksAssert.withTable("CREATE TABLE `table_b` (\n" +
                "  `becif_no` varchar(65533) DEFAULT NULL,\n" +
                "  `payroll_flag` varchar(65533) DEFAULT NULL,\n" +
                "  `dt` varchar(65533) DEFAULT NULL\n" +
                ")");
        starRocksAssert.withTable("CREATE TABLE `table_c` (\n" +
                "  `strategy_id` varchar(65533) DEFAULT NULL,\n" +
                "  `cust_group_name` varchar(65533) DEFAULT NULL,\n" +
                "  `strategy_type` varchar(65533) DEFAULT NULL,\n" +
                "  `dt` varchar(65533) DEFAULT NULL\n" +
                ")");
        starRocksAssert.withTable("CREATE TABLE `table_d` (\n" +
                "  `cust_group_name` varchar(65533) DEFAULT NULL,\n" +
                "  `custgroup_title` varchar(65533) DEFAULT NULL,\n" +
                "  `dt` varchar(65533) DEFAULT NULL\n" +
                ")");
        starRocksAssert.withTable("CREATE TABLE `table_e` (\n" +
                "  `trace_id` varchar(65533) DEFAULT NULL,\n" +
                "  `strategy_id_pdl` varchar(65533) DEFAULT NULL,\n" +
                "  `dt` varchar(65533) DEFAULT NULL\n" +
                ")");

        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW `mv_view_delta_1`\n" +
                "DISTRIBUTED BY HASH(`PAYROLL_FLAG`)\n" +
                "REFRESH ASYNC START(\"2023-10-29 17:30:00\") EVERY(INTERVAL 60 MINUTE)\n" +
                "PROPERTIES (\n" +
                "\"force_external_table_query_rewrite\" = \"true\",\n" +
                "\"unique_constraints\" = \"table_e.trace_id;\n" +
                "table_b.becif_no;\n" +
                "table_c.strategy_id;\n" +
                "table_d.cust_group_name\n" +
                "\",\n" +
                "\"foreign_key_constraints\" = \"\n" +
                "table_a(trace_id) REFERENCES table_e(trace_id);\n" +
                "table_a(becif_no) REFERENCES table_b(becif_no);\n" +
                "table_a(strategy_id) REFERENCES table_c(strategy_id);\n" +
                "table_c(cust_group_name) REFERENCES table_d(cust_group_name)\n" +
                "\"\n" +
                ")\n" +
                "AS\n" +
                "SELECT\n" +
                "            f1.PAYROLL_FLAG,\n" +
                "            f2.STRATEGY_TYPE,\n" +
                "            f2.CUSTGROUP_TITLE,\n" +
                "            sum(f.pv) AS pv\n" +
                "   FROM\n" +
                "            table_a f\n" +
                "   LEFT JOIN table_e dim1 ON f.TRACE_ID = dim1.TRACE_ID AND f.dt = dim1.dt\n" +
                "   left join (select dt,BECIF_NO,PAYROLL_FLAG from  table_b ) f1 on f.BECIF_NO=f1.BECIF_NO and f.dt=f1.dt\n" +
                "    left join (select dim0.dt,dim0.STRATEGY_ID,dim0.STRATEGY_TYPE,dim1.CUSTGROUP_TITLE from  table_c dim0\n" +
                "           left join table_d dim1 on dim0.CUST_GROUP_NAME=dim1.CUST_GROUP_NAME and dim0.dt=dim1.dt ) f2\n" +
                "       on  f.STRATEGY_ID=f2.STRATEGY_ID AND f.dt = f2.dt\n" +
                "        WHERE\n" +
                "            f.dt IN ('20230924')\n" +
                "        GROUP BY\n" +
                "            f1.PAYROLL_FLAG,\n" +
                "            f2.STRATEGY_TYPE,\n" +
                "            f2.CUSTGROUP_TITLE\n" +
                "            ;");

        String query = "explain logical SELECT\n" +
                "            f1.PAYROLL_FLAG,\n" +
                "            sum(f.pv) AS pv\n" +
                "   from table_a f\n" +
                "   left join table_e dim1 ON f.TRACE_ID = dim1.TRACE_ID AND f.dt = dim1.dt\n" +
                "   left join (select dt,BECIF_NO,PAYROLL_FLAG from  table_b ) f1 on f.BECIF_NO=f1.BECIF_NO and f.dt=f1.dt\n" +
                "   where f.dt='20230924'\n" +
                "group by f1.PAYROLL_FLAG;";
        String plan = getFragmentPlan(query);
        PlanTestBase.assertContains(plan, "mv_view_delta_1");
        starRocksAssert.dropTable("table_a");
        starRocksAssert.dropTable("table_b");
        starRocksAssert.dropTable("table_c");
        starRocksAssert.dropTable("table_d");
        starRocksAssert.dropTable("table_e");
        starRocksAssert.dropMaterializedView("mv_view_delta_1");
    }

    @Test
    public void testJoinDeriveRewriteOnNestedMv() throws Exception {
        starRocksAssert.withTable("CREATE TABLE `IDX_BASE_DIM_TRACEID` (\n" +
                "  `trace_id` varchar(65533) DEFAULT NULL,\n" +
                "  `strategy_id_pdl` varchar(65533) DEFAULT NULL,\n" +
                "  `dt` date DEFAULT NULL\n" +
                ") PARTITION BY range(dt) (\n" +
                "PARTITION p1 VALUES [ (\"20230702\"),(\"20230703\")),\n" +
                "PARTITION p2 VALUES [ (\"20230703\"),(\"20230704\")),\n" +
                "PARTITION p3 VALUES [ (\"20230704\"),(\"20230705\")),\n" +
                "PARTITION p4 VALUES [ (\"20230705\"),(\"20230706\"))\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(trace_id)\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");\n" +
                "\n");

        starRocksAssert.withTable("CREATE TABLE `IDX_BASE_OBJ_STRATEGY` (\n" +
                "  `strategy_id` varchar(65533) DEFAULT NULL,\n" +
                "  `strategy_type` varchar(65533) DEFAULT NULL,\n" +
                "  `cust_group_name` varchar(65533) DEFAULT NULL,\n" +
                "  `dt` date DEFAULT NULL\n" +
                ") PARTITION BY range(dt) (\n" +
                "PARTITION p1 VALUES [ (\"20230702\"),(\"20230703\")),\n" +
                "PARTITION p2 VALUES [ (\"20230703\"),(\"20230704\")),\n" +
                "PARTITION p3 VALUES [ (\"20230704\"),(\"20230705\")),\n" +
                "PARTITION p4 VALUES [ (\"20230705\"),(\"20230706\"))\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(strategy_id)\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");\n" +
                "\n");

        starRocksAssert.withTable("CREATE TABLE `IDX_COMMON_EV_BANKAPP_EXP_CLICK_INFO` (\n" +
                "  `strategy_id_pdl` varchar(65533) DEFAULT NULL,\n" +
                "  `becif_no` varchar(65533) DEFAULT NULL,\n" +
                "  `trace_id` varchar(65533) DEFAULT NULL,\n" +
                "  `pv` int(11) DEFAULT NULL,\n" +
                "  `dt` date DEFAULT NULL\n" +
                ") PARTITION BY range(dt) (\n" +
                "PARTITION p1 VALUES [ (\"20230702\"),(\"20230703\")),\n" +
                "PARTITION p2 VALUES [ (\"20230703\"),(\"20230704\")),\n" +
                "PARTITION p3 VALUES [ (\"20230704\"),(\"20230705\")),\n" +
                "PARTITION p4 VALUES [ (\"20230705\"),(\"20230706\"))\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(trace_id)\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");\n" +
                "\n");

        starRocksAssert.withTable("CREATE TABLE `IDX_COMMON_EV_BANKAPP_CLICK_INFO` (\n" +
                "  `strategy_id_pdl` varchar(65533) DEFAULT NULL,\n" +
                "  `becif_no` varchar(65533) DEFAULT NULL,\n" +
                "  `trace_id` varchar(65533) DEFAULT NULL,\n" +
                "  `pv` int(11) DEFAULT NULL,\n" +
                "  `dt` date DEFAULT NULL\n" +
                ") PARTITION BY range(dt) (\n" +
                "PARTITION p1 VALUES [ (\"20230702\"),(\"20230703\")),\n" +
                "PARTITION p2 VALUES [ (\"20230703\"),(\"20230704\")),\n" +
                "PARTITION p3 VALUES [ (\"20230704\"),(\"20230705\")),\n" +
                "PARTITION p4 VALUES [ (\"20230705\"),(\"20230706\"))\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(trace_id)\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");\n" +
                "\n");

        starRocksAssert.withTable("CREATE TABLE `IDX_CUST_COMMON_DIM` (\n" +
                "  `sales_new_cust` varchar(65533) DEFAULT NULL,\n" +
                "  `payroll_flag` varchar(65533) DEFAULT NULL,\n" +
                "  `becif_no` varchar(65533) DEFAULT NULL,\n" +
                "  `dt` date DEFAULT NULL\n" +
                ") PARTITION BY range(dt) (\n" +
                "PARTITION p1 VALUES [ (\"20230702\"),(\"20230703\")),\n" +
                "PARTITION p2 VALUES [ (\"20230703\"),(\"20230704\")),\n" +
                "PARTITION p3 VALUES [ (\"20230704\"),(\"20230705\")),\n" +
                "PARTITION p4 VALUES [ (\"20230705\"),(\"20230706\"))\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(becif_no)\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");\n" +
                "\n");

        starRocksAssert.withTable("CREATE TABLE `D_OPERATION_ORDER_CUST_DETAIL_PDL_ID3` (\n" +
                "  `strategy_id_pdl` varchar(65533) DEFAULT NULL,\n" +
                "  `trace_id` varchar(65533) DEFAULT NULL,\n" +
                "  `page_name_gy` varchar(65533) DEFAULT NULL,\n" +
                "  `becif_no` varchar(65533) DEFAULT NULL,\n" +
                "  `dt` date DEFAULT NULL\n" +
                ") PARTITION BY range(dt) (\n" +
                "PARTITION p1 VALUES [ (\"20230702\"),(\"20230703\")),\n" +
                "PARTITION p2 VALUES [ (\"20230703\"),(\"20230704\")),\n" +
                "PARTITION p3 VALUES [ (\"20230704\"),(\"20230705\")),\n" +
                "PARTITION p4 VALUES [ (\"20230705\"),(\"20230706\"))\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(trace_id)\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");\n" +
                "\n");

        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW `test_perf_mv_pv1`\n" +
                "PARTITION BY dt\n" +
                "DISTRIBUTED BY HASH(`TRACE_ID`) BUCKETS 8\n" +
                "REFRESH DEFERRED MANUAL\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"force_external_table_query_rewrite\" = \"true\"\n" +
                ")\n" +
                "AS\n" +
                " SELECT\n" +
                "                 f.DT,\n" +
                "                f.TRACE_ID AS TRACE_ID,\n" +
                "                f.BECIF_NO AS BECIF_NO,\n" +
                "                SUM(f.PV) AS pv1\n" +
                "            FROM\n" +
                "                IDX_COMMON_EV_BANKAPP_CLICK_INFO f\n" +
                "            GROUP BY\n" +
                "                f.DT,\n" +
                "                f.BECIF_NO,\n" +
                "                f.TRACE_ID;");

        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW `test_perf_mv_pv2`\n" +
                "PARTITION BY dt\n" +
                "DISTRIBUTED BY HASH(`BECIF_NO`) BUCKETS 8\n" +
                "REFRESH DEFERRED MANUAL\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"force_external_table_query_rewrite\" = \"true\"\n" +
                ")\n" +
                "AS\n" +
                " SELECT\n" +
                "                 f.DT,\n" +
                "                dim0.STRATEGY_ID_PDL AS STRATEGY_ID_PDL,\n" +
                "                f.BECIF_NO AS BECIF_NO,\n" +
                "                SUM(f.PV) AS pv2\n" +
                "            FROM\n" +
                "                IDX_COMMON_EV_BANKAPP_EXP_CLICK_INFO f\n" +
                "            LEFT JOIN IDX_BASE_DIM_TRACEID dim0 ON\n" +
                "                f.TRACE_ID = dim0.TRACE_ID\n" +
                "                AND f.DT = dim0.DT\n" +
                "            GROUP BY\n" +
                "                f.DT,\n" +
                "                f.BECIF_NO,\n" +
                "                dim0.STRATEGY_ID_PDL;");
        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW `test_perf_mv_pv3`\n" +
                "PARTITION BY dt \n" +
                "DISTRIBUTED BY HASH(`BECIF_NO`) BUCKETS 8\n" +
                "REFRESH DEFERRED MANUAL\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"force_external_table_query_rewrite\" = \"true\"\n" +
                ")\n" +
                "AS\n" +
                " SELECT\n" +
                "                 f.DT,\n" +
                "                dim0.STRATEGY_ID_PDL AS STRATEGY_ID_PDL,\n" +
                "                f.BECIF_NO AS BECIF_NO,\n" +
                "                                COUNT(f.PAGE_NAME_GY) AS cnt1\n" +
                "            FROM\n" +
                "                D_OPERATION_ORDER_CUST_DETAIL_PDL_ID3 f\n" +
                "            LEFT JOIN IDX_BASE_DIM_TRACEID dim0 ON\n" +
                "                f.TRACE_ID = dim0.TRACE_ID\n" +
                "                AND f.DT = dim0.DT\n" +
                "            GROUP BY\n" +
                "                f.DT,\n" +
                "                f.BECIF_NO,\n" +
                "                dim0.STRATEGY_ID_PDL;");
        starRocksAssert.withMaterializedView("\n" +
                "CREATE MATERIALIZED VIEW `test_perf_mv_pv4`\n" +
                "PARTITION BY dt\n" +
                "DISTRIBUTED BY HASH(DT, SALES_NEW_CUST, PAYROLL_FLAG, STRATEGY_TYPE, CUST_GROUP_NAME)\n" +
                "REFRESH DEFERRED MANUAL\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\",\n" +
                "\"force_external_table_query_rewrite\" = \"true\"\n" +
                ")\n" +
                "AS\n" +
                "SELECT q.DT, f1.SALES_NEW_CUST AS SALES_NEW_CUST, f1.PAYROLL_FLAG AS PAYROLL_FLAG," +
                " f7.STRATEGY_TYPE AS STRATEGY_TYPE, f7.CUST_GROUP_NAME AS CUST_GROUP_NAME\n" +
                "        , SUM(q.cnt1) AS cnt1, SUM(q.pv1) AS pv1\n" +
                "        , SUM(q.pv2) AS pv2\n" +
                "FROM (\n" +
                "        SELECT t0.pv1 AS pv1, t1.pv2 AS pv2, t2.cnt1 AS cnt1\n" +
                "                , t0.DT AS DT\n" +
                "                , COALESCE(t0.BECIF_NO, t1.BECIF_NO, t2.BECIF_NO) AS pk1\n" +
                "                , COALESCE(t0.TRACE_ID, t1.STRATEGY_ID_PDL, t2.STRATEGY_ID_PDL) AS pk7\n" +
                "        FROM (\n" +
                "                SELECT f.pv1, f.TRACE_ID, f.BECIF_NO, f.DT\n" +
                "                FROM test_perf_mv_pv1 f\n" +
                "        ) t0\n" +
                "                FULL JOIN (\n" +
                "                        SELECT f.STRATEGY_ID_PDL, f.BECIF_NO, f.pv2, f.DT\n" +
                "                        FROM test_perf_mv_pv2 f\n" +
                "                ) t1\n" +
                "                ON t1.DT = t0.DT\n" +
                "                        AND t1.BECIF_NO = t0.BECIF_NO\n" +
                "                        AND t1.STRATEGY_ID_PDL = t0.TRACE_ID\n" +
                "                FULL JOIN (\n" +
                "                        SELECT f.STRATEGY_ID_PDL, f.cnt1, f.BECIF_NO, f.DT\n" +
                "                        FROM test_perf_mv_pv3 f\n" +
                "                ) t2\n" +
                "                ON t2.DT = COALESCE(t0.DT , t1.DT)\n" +
                "                        AND t2.BECIF_NO = COALESCE(t0.BECIF_NO, t1.BECIF_NO)\n" +
                "                        AND t2.STRATEGY_ID_PDL = COALESCE(t0.TRACE_ID, t1.STRATEGY_ID_PDL)\n" +
                ") q\n" +
                "        LEFT JOIN (\n" +
                "                SELECT f.SALES_NEW_CUST AS SALES_NEW_CUST," +
                " f.PAYROLL_FLAG AS PAYROLL_FLAG, f.BECIF_NO AS BECIF_NO, f.DT\n" +
                "                FROM IDX_CUST_COMMON_DIM f\n" +
                "        ) f1\n" +
                "        ON f1.BECIF_NO = q.pk1\n" +
                "                AND f1.DT = q.DT\n" +
                "        LEFT JOIN (\n" +
                "                SELECT f.STRATEGY_ID AS STRATEGY_ID, f.STRATEGY_TYPE AS STRATEGY_TYPE," +
                " f.CUST_GROUP_NAME AS CUST_GROUP_NAME, f.DT\n" +
                "                FROM IDX_BASE_OBJ_STRATEGY f\n" +
                "        ) f7\n" +
                "        ON f7.STRATEGY_ID = q.pk7\n" +
                "                AND f7.DT = q.DT\n" +
                "GROUP BY q.DT, f1.PAYROLL_FLAG, f7.CUST_GROUP_NAME, f7.STRATEGY_TYPE, f1.SALES_NEW_CUST;");
        String query = "SELECT SUM(q.pv1) AS pv1,\n" +
                "       f1.SALES_NEW_CUST AS SALES_NEW_CUST,\n" +
                "       SUM(q.cnt1) AS cnt1,\n" +
                "       f1.PAYROLL_FLAG AS PAYROLL_FLAG,\n" +
                "       f7.STRATEGY_TYPE AS STRATEGY_TYPE,\n" +
                "       f7.CUST_GROUP_NAME AS CUST_GROUP_NAME,\n" +
                "       SUM(q.pv2) AS pv2\n" +
                "FROM\n" +
                "  ( SELECT t0.pv1 AS pv1,\n" +
                "           t1.pv2 AS pv2,\n" +
                "           t2.cnt1 AS cnt1,\n" +
                "           t0.DT AS DT ,\n" +
                "           COALESCE(t0.BECIF_NO , t1.BECIF_NO , t2.BECIF_NO) AS pk1 ,\n" +
                "           COALESCE(t0.TRACE_ID , t1.STRATEGY_ID_PDL , t2.STRATEGY_ID_PDL) AS pk7\n" +
                "   FROM\n" +
                "     ( SELECT SUM(f.PV) AS pv1,\n" +
                "              f.TRACE_ID AS TRACE_ID,\n" +
                "              f.BECIF_NO AS BECIF_NO,\n" +
                "              f.DT\n" +
                "      FROM IDX_COMMON_EV_BANKAPP_CLICK_INFO f\n" +
                "      GROUP BY f.DT,\n" +
                "               f.BECIF_NO,\n" +
                "               f.TRACE_ID) AS t0\n" +
                "   FULL JOIN\n" +
                "     ( SELECT dim0.STRATEGY_ID_PDL AS STRATEGY_ID_PDL,\n" +
                "              f.BECIF_NO AS BECIF_NO,\n" +
                "              SUM(f.PV) AS pv2,\n" +
                "              f.DT\n" +
                "      FROM IDX_COMMON_EV_BANKAPP_EXP_CLICK_INFO f\n" +
                "      LEFT JOIN IDX_BASE_DIM_TRACEID dim0 ON f.TRACE_ID = dim0.TRACE_ID\n" +
                "      AND f.DT = dim0.DT\n" +
                "      GROUP BY f.DT,\n" +
                "               f.BECIF_NO,\n" +
                "               dim0.STRATEGY_ID_PDL) AS t1 \n" +
                "   ON t1.DT = t0.DT\n" +
                "   AND t1.BECIF_NO = t0.BECIF_NO\n" +
                "   AND t1.STRATEGY_ID_PDL = t0.TRACE_ID\n" +
                "   FULL JOIN\n" +
                "     ( SELECT dim0.STRATEGY_ID_PDL AS STRATEGY_ID_PDL,\n" +
                "              COUNT(f.PAGE_NAME_GY) AS cnt1,\n" +
                "              f.BECIF_NO AS BECIF_NO,\n" +
                "              f.DT\n" +
                "      FROM D_OPERATION_ORDER_CUST_DETAIL_PDL_ID3 f\n" +
                "      LEFT JOIN IDX_BASE_DIM_TRACEID dim0 ON f.TRACE_ID = dim0.TRACE_ID\n" +
                "      AND f.DT = dim0.DT\n" +
                "      GROUP BY f.DT,\n" +
                "               f.BECIF_NO,\n" +
                "               dim0.STRATEGY_ID_PDL) AS t2\n" +
                "   ON t2.DT = COALESCE(t0.DT , t1.DT)\n" +
                "   AND t2.BECIF_NO = COALESCE(t0.BECIF_NO , t1.BECIF_NO)\n" +
                "   AND t2.STRATEGY_ID_PDL = COALESCE(t0.TRACE_ID , t1.STRATEGY_ID_PDL)) AS q\n" +
                "LEFT JOIN\n" +
                "  ( SELECT f.SALES_NEW_CUST AS SALES_NEW_CUST,\n" +
                "           f.PAYROLL_FLAG AS PAYROLL_FLAG,\n" +
                "           f.BECIF_NO AS BECIF_NO,\n" +
                "           f.DT\n" +
                "   FROM IDX_CUST_COMMON_DIM f) AS f1 ON f1.BECIF_NO = q.pk1\n" +
                "AND f1.DT = q.DT\n" +
                "LEFT JOIN\n" +
                "  ( SELECT f.STRATEGY_ID AS STRATEGY_ID,\n" +
                "           f.STRATEGY_TYPE AS STRATEGY_TYPE,\n" +
                "           f.CUST_GROUP_NAME AS CUST_GROUP_NAME,\n" +
                "           f.DT\n" +
                "   FROM IDX_BASE_OBJ_STRATEGY f) AS f7 ON f7.STRATEGY_ID = q.pk7\n" +
                "AND f7.DT = q.DT\n" +
                "WHERE q.DT IN ('20230924')\n" +
                "  AND f1.PAYROLL_FLAG IN ('Y')\n" +
                "GROUP BY f1.PAYROLL_FLAG,\n" +
                "         f7.CUST_GROUP_NAME,\n" +
                "         f7.STRATEGY_TYPE,\n" +
                "         f1.SALES_NEW_CUST";
        String plan = getFragmentPlan(query);
        PlanTestBase.assertContains(plan, "test_perf_mv_pv4");
        starRocksAssert.dropMaterializedView("test_perf_mv_pv1");
        starRocksAssert.dropMaterializedView("test_perf_mv_pv2");
        starRocksAssert.dropMaterializedView("test_perf_mv_pv3");
        starRocksAssert.dropMaterializedView("test_perf_mv_pv4");
        starRocksAssert.dropTable("IDX_BASE_DIM_TRACEID");
        starRocksAssert.dropTable("IDX_BASE_OBJ_STRATEGY");
        starRocksAssert.dropTable("IDX_COMMON_EV_BANKAPP_EXP_CLICK_INFO");
        starRocksAssert.dropTable("IDX_COMMON_EV_BANKAPP_CLICK_INFO");
        starRocksAssert.dropTable("IDX_CUST_COMMON_DIM");
        starRocksAssert.dropTable("D_OPERATION_ORDER_CUST_DETAIL_PDL_ID3");
    }

    @Test
    public void testForceRewriteWithRandomInput() {
        String mv = "SELECT count(lo_linenumber)\n" +
                "FROM lineorder inner join customer on lo_custkey = c_custkey\n" +
                "WHERE `c_name` != 'name'; ";
        String query = "SELECT count(lo_linenumber)\n" +
                "FROM lineorder inner join customer on lo_custkey = c_custkey";
        Table table1 = getTable(MATERIALIZED_DB_NAME, "lineorder");
        Table table2 = getTable(MATERIALIZED_DB_NAME, "customer");

        PlanTestBase.setTableStatistics((OlapTable) table1, 1000000);
        PlanTestBase.setTableStatistics((OlapTable) table2, 1000000);

        // For enforce-columns changed which also changed mv's cost model, use force rewrite to force the result.
        connectContext.getSessionVariable()
                .setMaterializedViewRewriteMode(SessionVariable.MaterializedViewRewriteMode.FORCE.toString());
        testRewriteOK(mv, query);

        {
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("UNION");
        }

        {
            Random rand = new Random();
            for (int i = 0; i < 10; i++) {
                long rowCount1 = rand.nextLong();
                long rowCount2 = rand.nextLong();

                PlanTestBase.setTableStatistics((OlapTable) table1, rowCount1);
                PlanTestBase.setTableStatistics((OlapTable) table2, rowCount2);
                MVRewriteChecker checker = testRewriteOK(mv, query);
                checker.contains("UNION");
            }
        }

        connectContext.getSessionVariable()
                .setMaterializedViewRewriteMode(SessionVariable.MaterializedViewRewriteMode.DEFAULT.toString());
    }

    private void createMaterializedViewWithInput(String mvName, long mvRowCount) throws Exception {
        String mv = String.format("CREATE MATERIALIZED VIEW %s" +
                " DISTRIBUTED BY HASH(c1) " +
                " REFRESH DEFERRED MANUAL " +
                " AS SELECT count(lo_linenumber) as c1\n" +
                " FROM lineorder inner join customer on lo_custkey = c_custkey\n" +
                " WHERE `c_name` != 'name'; ", mvName);
        starRocksAssert.withMaterializedView(mv);
        Table mvTable = getTable(MATERIALIZED_DB_NAME, mvName);
        PlanTestBase.setTableStatistics((OlapTable) mvTable, mvRowCount);
    }

    @Test
    public void testForceRewriteForBestCost() throws Exception {
        String query = "SELECT count(lo_linenumber)\n" +
                "FROM lineorder inner join customer on lo_custkey = c_custkey";
        Table table1 = getTable(MATERIALIZED_DB_NAME, "lineorder");
        Table table2 = getTable(MATERIALIZED_DB_NAME, "customer");

        PlanTestBase.setTableStatistics((OlapTable) table1, 1000000);
        PlanTestBase.setTableStatistics((OlapTable) table2, 1000000);

        connectContext.getSessionVariable()
                .setMaterializedViewRewriteMode(SessionVariable.MaterializedViewRewriteMode.FORCE.toString());

        // prepare mvs
        List<String> mvNames = Lists.newArrayList();
        for (int i = 0; i < 10; i++) {
            long rowCount = 1000 * (i + 1);
            String mvName = String.format("test_mv_%s", i);
            createMaterializedViewWithInput(mvName, rowCount);
            mvNames.add(mvName);
        }
        MVRewriteChecker checker = sql(query);
        checker.contains("UNION");
        // must contain the lowest cost mv.
        checker.contains("test_mv_0");
        connectContext.getSessionVariable()
                .setMaterializedViewRewriteMode(SessionVariable.MaterializedViewRewriteMode.DEFAULT.toString());
        mvNames.stream().forEach(x -> {
            try {
                starRocksAssert.dropMaterializedView(x);
            } catch (Exception e) {
                // ignore
            }
        });
    }

    @Test
    public void testMvRestoreUpdater1() {
        String mvName = "test0";
        String mv = String.format("CREATE MATERIALIZED VIEW %s" +
                " REFRESH DEFERRED MANUAL " +
                " AS SELECT *  FROM lineorder", mvName);
        starRocksAssert.withMaterializedView(mv,
                () -> {
                    MaterializedView mvTable = (MaterializedView) getTable(MATERIALIZED_DB_NAME, mvName);
                    Pair<Status, Boolean> result = MVRestoreUpdater.checkMvDefinedQuery(mvTable, Maps.newHashMap(), Pair.create("", ""));
                    Assertions.assertTrue(result.first.ok());
                });

    }

    @Test
    public void testMvRestoreUpdater2() {
        String mvName = "test0";
        String mv = String.format("CREATE MATERIALIZED VIEW %s" +
                " REFRESH DEFERRED MANUAL " +
                " AS SELECT * FROM lineorder", mvName);

        starRocksAssert.withMaterializedView(mv,
                () -> {
                    MaterializedView mvTable = (MaterializedView) getTable(MATERIALIZED_DB_NAME, mvName);
                    Pair<String, String> newDefinedQueries = Pair.create("", "");
                    Map<TableName, TableName> remoteToLocalTableName = Maps.newHashMap();
                    remoteToLocalTableName.put(TableName.fromString("test.lineorder"),
                            TableName.fromString("test.lineorder"));
                    boolean result = MVRestoreUpdater.renewMvBaseTableNames(mvTable,
                            remoteToLocalTableName, connectContext, newDefinedQueries);
                    Assertions.assertTrue(result);
                    Assertions.assertTrue(!Strings.isNullOrEmpty(newDefinedQueries.first));
                    Assertions.assertTrue(!Strings.isNullOrEmpty(newDefinedQueries.second));
                });

    }

    @Test
    public void testAggregateWithHave() {
        String mv = "select empid, deptno,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid, deptno having sum(salary) > 1";
        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid, deptno having sum(salary) > 1");
        testRewriteOK(mv, "select empid, deptno,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid, deptno having sum(salary) > 10");
        testRewriteOK(mv, "select empid,\n" +
                " sum(salary) as total, count(salary)  as cnt\n" +
                " from emps group by empid having sum(salary) > 10");
    }

    @Test
    public void testMultiLeftOuterJoin() {
        {
            String mv = "select t1a, t1b, v5, v8 " +
                    "from test.test_all_type left outer join test.t1 on t1d = v4 " +
                    "left outer join test.t2 on v5 = v7 where v9 = 10";
            String query = "select t1a, t1b, v5, v8 " +
                    "from test.test_all_type left outer join test.t1 on t1d = v4 " +
                    "left outer join test.t2 on v5 = v7 where v9 = 10 and t1a != 'xxx'";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("t1a != 'xxx'");
        }

        {
            String mv = "select v1, v2, v3, v5, v8 " +
                    "from test.t0 left outer join test.t1 on v1 = v4 " +
                    "left outer join test.t2 on v5 = v7 where v9 = 10";
            String query = "select v1, v2, v3, v5, v8 " +
                    "from test.t0 left outer join test.t1 on v1 = v4 " +
                    "left outer join test.t2 on v5 = v7 where v9 = 10 and v3 = 1";
            testRewriteOK(mv, query);
        }
    }

    @Test
    public void testColumnPruningWithPredicates() {
        {
            String mv = "select lo_orderkey, lo_orderdate, lo_linenumber," +
                    " sum(lo_quantity) total_quantity," +
                    " sum(lo_revenue) as total_revenue," +
                    " sum(lo_tax) as total_tax" +
                    " from lineorder" +
                    " group by lo_orderkey, lo_orderdate, lo_linenumber";
            String query = "select lo_orderdate," +
                    " sum(lo_quantity) total_quantity," +
                    " sum(lo_tax) as total_tax" +
                    " from lineorder" +
                    " group by lo_orderdate" +
                    " having sum(lo_tax) > 100";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("  4:Project\n" +
                    "  |  <slot 6> : 21: lo_orderdate\n" +
                    "  |  <slot 18> : 26: sum\n" +
                    "  |  <slot 19> : 27: sum\n" +
                    "  |  \n" +
                    "  3:AGGREGATE (merge finalize)");
        }

        {
            String mv = "select lo_orderdate, lo_linenumber, p_name, p_partkey" +
                    " from lineorder join part on lo_partkey = p_partkey";
            String query = "select lo_orderdate, lo_linenumber, p_name" +
                    " from lineorder join part on lo_partkey = p_partkey" +
                    " where p_partkey = 1";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("1:Project\n" +
                    "  |  <slot 2> : 28: lo_linenumber\n" +
                    "  |  <slot 6> : 27: lo_orderdate\n" +
                    "  |  <slot 19> : 29: p_name\n" +
                    "  |  \n" +
                    "  0:OlapScanNode\n" +
                    "     TABLE: mv0");
        }

        {
            String mv = "select lo_orderkey, lo_orderdate, lo_linenumber," +
                    " sum(lo_quantity) total_quantity," +
                    " sum(lo_revenue) as total_revenue," +
                    " sum(lo_tax) as total_tax" +
                    " from lineorder" +
                    " group by lo_orderkey, lo_orderdate, lo_linenumber";
            String query = "select lo_orderdate," +
                    " sum(lo_quantity) total_quantity," +
                    " sum(lo_tax) as total_tax" +
                    " from lineorder" +
                    " where lo_orderkey = 100" +
                    " group by lo_orderdate";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("1:Project\n" +
                    "|  <slot 21> : col$: lo_orderdate\n" +
                    "|  <slot 23> : col$: total_quantity\n" +
                    "|  <slot 25> : col$: total_tax");
        }

        {
            String mv = "select lo_orderkey, lo_orderdate, lo_linenumber," +
                    " sum(lo_quantity) total_quantity," +
                    " sum(lo_revenue) as total_revenue," +
                    " sum(lo_tax) as total_tax" +
                    " from lineorder" +
                    " group by lo_orderkey, lo_orderdate, lo_linenumber";
            String query = "select lo_orderdate," +
                    " sum(lo_quantity) total_quantity," +
                    " sum(lo_tax) as total_tax" +
                    " from lineorder" +
                    " where lo_orderkey = 100 and lo_linenumber = 1" +
                    " group by lo_orderdate";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("1:Project\n" +
                    "|  <slot 6> : col$: lo_orderdate\n" +
                    "|  <slot 18> : col$: total_quantity\n" +
                    "|  <slot 19> : col$: total_tax");
        }

        {
            String mv = "select lo_orderkey, lo_orderdate, lo_linenumber," +
                    " sum(lo_quantity) total_quantity," +
                    " sum(lo_revenue) as total_revenue," +
                    " sum(lo_tax) as total_tax" +
                    " from lineorder" +
                    " group by lo_orderkey, lo_orderdate, lo_linenumber";
            String query = "select lo_orderdate," +
                    " sum(lo_quantity) total_quantity," +
                    " sum(lo_tax) as total_tax" +
                    " from lineorder" +
                    " group by lo_orderdate";
            MVRewriteChecker checker = testRewriteOK(mv, query);
            checker.contains("1:AGGREGATE (update serialize)\n" +
                    "|  STREAMING\n" +
                    "|  output: sum(col$: total_quantity), sum(col$: total_tax)\n" +
                    "|  group by: col$: lo_orderdate\n" +
                    "|\n" +
                    "0:OlapScanNode\n" +
                    "TABLE: mv0");
        }
    }

    @Test
    public void testAggRollupWithTimeUnit1() throws Exception {
        String sql = "CREATE TABLE `t0` (\n" +
                "  `date_col` date NOT NULL,\n" +
                "  `id` int(11) NOT NULL,\n" +
                "  `int_col` int(11) NOT NULL,\n" +
                "  `float_col_1` float NOT NULL,\n" +
                "  `float_col_2` float NOT NULL,\n" +
                "  `varchar_col` varchar(255) NOT NULL,\n" +
                "  `tinyint_col` tinyint(4) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`date_col`, `id`)\n" +
                "DISTRIBUTED BY HASH(`id`)\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");\n";
        starRocksAssert.withTable(sql);
        String mv1 = "create MATERIALIZED VIEW date_mv\n" +
                "DISTRIBUTED BY RANDOM\n" +
                "REFRESH MANUAL\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ")   as select tinyint_col, date_col , sum(float_col_1 * int_col) as sum_value " +
                "from t0 group by tinyint_col, date_col;";
        starRocksAssert.withMaterializedView(mv1);

        // date column should be the same with date_trunc('day', ct)
        String[] rollupTimeUnits = new String[]{ "day", "week", "month", "quarter", "year" };
        for (String rollupTimeUnit : rollupTimeUnits) {
            String query = "select tinyint_col, date_trunc('" + rollupTimeUnit + "', date_col) as date_col, " +
                    "   sum(float_col_1 * int_col) as sum_value from t0 " +
                    "group by tinyint_col, date_trunc('" + rollupTimeUnit + "', date_col);";
            String plan = sql(query).getExecPlan();
            PlanTestBase.assertContains(plan, "date_mv");
        }
        starRocksAssert.dropTable("t0");
        starRocksAssert.dropMaterializedView("date_mv");
    }

    @Test
    public void testAggRollupWithTimeUnit2() throws Exception {
        String sql = "CREATE TABLE `t0` (\n" +
                "  `date_col` datetime NOT NULL,\n" +
                "  `id` int(11) NOT NULL,\n" +
                "  `int_col` int(11) NOT NULL,\n" +
                "  `float_col_1` float NOT NULL,\n" +
                "  `float_col_2` float NOT NULL,\n" +
                "  `varchar_col` varchar(255) NOT NULL,\n" +
                "  `tinyint_col` tinyint(4) NOT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`date_col`, `id`)\n" +
                "DISTRIBUTED BY HASH(`id`)\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ");\n";
        starRocksAssert.withTable(sql);
        String mv1 = "create MATERIALIZED VIEW date_mv\n" +
                "DISTRIBUTED BY RANDOM\n" +
                "REFRESH DEFERRED MANUAL\n" +
                "PROPERTIES (\n" +
                "\"replication_num\" = \"1\"\n" +
                ")   as select tinyint_col, date_trunc('day', date_col), sum(float_col_1 * int_col) as sum_value " +
                "from t0 group by tinyint_col, date_trunc('day', date_col);";
        starRocksAssert.withMaterializedView(mv1);

        // date column should be the same with date_trunc('day', ct)
        //String[] timeUnits = new String[]{"day", "week",  "month", "quarter", "year"};
        String[] timeUnits = new String[]{ "week", "month", "quarter", "year" };
        for (String timeUnit : timeUnits) {
            {
                String query = "select tinyint_col, date_trunc('" + timeUnit + "', date_col) as date_col, " +
                        "   sum(float_col_1 * int_col) as sum_value from t0 " +
                        "group by tinyint_col, date_trunc('" + timeUnit + "', date_col);";
                String plan = sql(query).getExecPlan();
                PlanTestBase.assertContains(plan, "date_mv");
            }

            {
                String query = "select tinyint_col,  " +
                        "   sum(float_col_1 * int_col) as sum_value from t0 " +
                        "where date_col >= '2024-01-01 00:00:00' group by tinyint_col ";
                String plan = sql(query).getExecPlan();
                PlanTestBase.assertContains(plan, "date_mv");
            }

            {
                String query = "select tinyint_col,  " +
                        "   sum(float_col_1 * int_col) as sum_value from t0 " +
                        "where date_trunc('" + timeUnit + "',  date_col) >= '2024-01-01' group by tinyint_col ";
                String plan = sql(query).getExecPlan();
                PlanTestBase.assertContains(plan, "date_mv");
            }

            {
                // TODO: we can support this later.
                String query = "select tinyint_col,  " +
                        "   sum(float_col_1 * int_col) as sum_value from t0 " +
                        "where date_trunc('" + timeUnit + "',  date_col) = '2024-01-01' group by tinyint_col ";
                String plan = sql(query).getExecPlan();
                PlanTestBase.assertNotContains(plan, "date_mv");
            }

            {
                String query = "select tinyint_col,  " +
                        "   sum(float_col_1 * int_col) as sum_value from t0 " +
                        "where date_col >= '2024-09-18 01:00:01' group by tinyint_col ";
                String plan = sql(query).getExecPlan();
                PlanTestBase.assertNotContains(plan, "date_mv");
            }
        }
        starRocksAssert.dropTable("t0");
        starRocksAssert.dropMaterializedView("date_mv");
    }

    @Test
    public void testCreateMVWithAggStateRewrite() throws Exception {
        starRocksAssert.withTable("\n" +
                "CREATE TABLE s1 (\n" +
                "    k1 string NOT NULL,\n" +
                "    k2 string,\n" +
                "    k3 DECIMAL(34,0),\n" +
                "    k4 DATE NOT NULL,\n" +
                "    v1 BIGINT DEFAULT \"0\"\n" +
                ")\n" +
                "DUPLICATE KEY(k1,  k2, k3,  k4)\n" +
                "DISTRIBUTED BY HASH(k4);");
        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW test_mv1 " +
                " REFRESH DEFERRED MANUAL " +
                "as \n" +
                "SELECT k1, k2, avg_union(avg_state(k3 * 4)) as v1 from s1 where k1 != 'a' group by k1, k2;");
        {
            String query = "select k1, k2, avg_merge(v1) from (" +
                    "SELECT k1, k2, avg_union(avg_state(k3 * 4)) as v1 from s1 where k1 != 'a' group by k1,k2) t " +
                    "group by k1, k2;";
            String plan = UtFrameUtils.getFragmentPlan(connectContext, query);
            PlanTestBase.assertContains(plan, "test_mv1");
        }
        {
            String query = "select k1, k2, avg_merge(v1) from (" +
                    " SELECT k1, k2, avg_union(avg_state(k3 * 4)) as v1 from s1 where k1 != 'a' group by k1,k2 " +
                    " UNION ALL" +
                    " SELECT k1, k2, avg_union(avg_state(k3 * 4)) as v1 from s1 where k1 != 'a' group by k1,k2 " +
                    ") t " +
                    "group by k1, k2;";
            String plan = UtFrameUtils.getFragmentPlan(connectContext, query);
            PlanTestBase.assertContains(plan, "test_mv1");
        }


        {
            String query = "SELECT k1, k2, avg_union(avg_state(k3 * 4)) as v1 from s1 where k1 != 'a' group by k1, k2;";
            String plan = UtFrameUtils.getFragmentPlan(connectContext, query);
            PlanTestBase.assertContains(plan, "test_mv1");
        }
        {
            String query = "SELECT k1, k2, avg_merge(avg_state(k3 * 4)) as v1 from s1 where k1 != 'a' group by k1, k2;";
            String plan = UtFrameUtils.getFragmentPlan(connectContext, query);
            PlanTestBase.assertContains(plan, "test_mv1");
        }
        {
            String query = "SELECT k2, avg_merge(avg_state(k3 * 4)) as v1 from s1 where k1 != 'a' group by k2;";
            String plan = UtFrameUtils.getFragmentPlan(connectContext, query);
            PlanTestBase.assertContains(plan, "test_mv1");
        }
        {
            String query = "SELECT k1, k2, avg(k3 * 4) as v1 from s1 where k1 != 'a' group by k1, k2;";
            String plan = UtFrameUtils.getFragmentPlan(connectContext, query);
            PlanTestBase.assertContains(plan, "test_mv1");
        }
        {
            String query = "SELECT k2, avg(k3 * 4) as v1 from s1 where k1 != 'a' group by k2;";
            String plan = UtFrameUtils.getFragmentPlan(connectContext, query);
            PlanTestBase.assertContains(plan, "test_mv1");
        }
        starRocksAssert.dropMaterializedView("test_mv1");
        starRocksAssert.dropTable("s1");
    }

    @Test
    public void testRangePredicateRewriteCase1() {
        String mv = "select lo_orderkey, lo_orderdate, lo_linenumber, lo_shipmode from lineorder";
        String query = "select distinct lo_orderkey from lineorder where lo_shipmode in (upper('a'), lower('a')) and " +
                "lo_linenumber = 1";
        String plan = testRewriteOK(mv, query)
                .getExecPlan();
        PlanTestBase.assertContains(plan, "   TABLE: mv0\n" +
                "     PREAGGREGATION: ON\n" +
                "     PREDICATES: 20: lo_linenumber = 1, 21: lo_shipmode IN ('A', 'a')");
    }

    @Test
    public void testAggregateToProjection() {
        // If agg push down is open, cannot rewrite.
        String mv = "select lo_orderkey from lineorder group by lo_orderkey";
        String sql = "select count(distinct lo_orderkey) from lineorder where lo_orderkey = 1";
        testRewriteOK(mv, sql);
    }
}
