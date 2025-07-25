---
displayed_sidebar: docs
---

# 通过 INSERT 语句导入数据

import InsertPrivNote from '../_assets/commonMarkdown/insertPrivNote.md'

本文介绍如何使用 INSERT 语句向 StarRocks 导入数据。

与 MySQL 等数据库系统类似，StarRocks 支持通过 INSERT 语句导入数据。您可以使用 INSERT INTO VALUES 语句直接向表中插入数据，您还可以通过 INSERT INTO SELECT 语句将其他 StarRocks 表中的数据导入到新的 StarRocks 表中，或者将其他数据源的数据通过[外部表功能](../data_source/External_table.md)导入至 StarRocks 内部表中。自 v3.1 起，您可以使用 INSERT 语句和 [FILES()](../sql-reference/sql-functions/table-functions/files.md) 函数直接导入云存储或 HDFS 中的文件。

2.4 版本中，StarRocks 进一步支持通过 INSERT OVERWRITE 语句批量**覆盖写入**目标表。INSERT OVERWRITE 语句通过整合以下三部分操作来实现覆盖写入：

1. 为目标分区[创建临时分区](../table_design/data_distribution/Temporary_partition.md#创建临时分区)
2. [写入数据至临时分区](../table_design/data_distribution/Temporary_partition.md#导入数据至临时分区)
3. [使用临时分区原子替换目标分区](../table_design/data_distribution/Temporary_partition.md#使用临时分区进行替换)

如果您希望在替换前验证数据，可以根据以上步骤自行实现覆盖写入数据。

从 v3.4.0 开始，StarRocks 支持分区表的 INSERT OVERWRITE 操作的新语义 — Dynamic Overwrite。更多信息，参考 [Dynamic Overwrite](#dynamic-overwrite)。

## 注意事项

- 您只能在 MySQL 客户端通过 `Ctrl` + `C` 按键强制取消同步 INSERT 导入任务。
- 您可以通过 [SUBMIT TASK](../sql-reference/sql-statements/loading_unloading/ETL/SUBMIT_TASK.md) 创建异步 INSERT 导入任务。
- 当前版本中，StarRocks 在执行 INSERT 语句时，如果有数据不符合目标表格式（例如字符串超长等情况），INSERT 操作默认执行失败。您可以通过设置会话变量 `enable_insert_strict` 为 `false` 以确保 INSERT 操作过滤不符合目标表格式的数据，并继续执行。
- 频繁使用 INSERT 语句导入小批量数据会产生过多的数据版本，从而影响查询性能，因此不建议您频繁使用 INSERT 语句导入数据或将其作为生产环境的日常例行导入作业。如果您的业务场景需要流式导入或者小批量多次导入数据，建议使用 Apache Kafka® 作为数据源并通过 [Routine Load](../loading/RoutineLoad.md) 方式进行导入作业。
- 执行 INSERT OVERWRITE 语句后，系统将为目标分区创建相应的临时分区，并将数据写入临时分区，最后使用临时分区[原子替换](../sql-reference/sql-statements/table_bucket_part_index/ALTER_TABLE.md#使用临时分区替换原分区)目标分区来实现覆盖写入。其所有过程均在 Leader FE 节点执行。因此，如果 Leader FE 节点在覆盖写入过程中发生宕机，将会导致该次 INSERT OVERWRITE 导入失败，其过程中所创建的临时分区也会被删除。

## 准备工作

### 查看权限

<InsertPrivNote />

### 创建对象

在 StarRocks 中创建数据库 `load_test`，并在其中创建导入目标表 `insert_wiki_edit` 以及数据源表 `source_wiki_edit`。

> **说明**
>
> 本文中演示的操作示例均基于表 `insert_wiki_edit` 和数据源表 `source_wiki_edit`。如果您选择使用自己的表以及数据，请跳过当前步骤，并根据使用场景修改需要导入的数据。

```SQL
CREATE DATABASE IF NOT EXISTS load_test;
USE load_test;
CREATE TABLE insert_wiki_edit
(
    event_time      DATETIME,
    channel         VARCHAR(32)      DEFAULT '',
    user            VARCHAR(128)     DEFAULT '',
    is_anonymous    TINYINT          DEFAULT '0',
    is_minor        TINYINT          DEFAULT '0',
    is_new          TINYINT          DEFAULT '0',
    is_robot        TINYINT          DEFAULT '0',
    is_unpatrolled  TINYINT          DEFAULT '0',
    delta           INT              DEFAULT '0',
    added           INT              DEFAULT '0',
    deleted         INT              DEFAULT '0'
)
DUPLICATE KEY(
    event_time,
    channel,
    user,
    is_anonymous,
    is_minor,
    is_new,
    is_robot,
    is_unpatrolled
)
PARTITION BY RANGE(event_time)(
    PARTITION p06 VALUES LESS THAN ('2015-09-12 06:00:00'),
    PARTITION p12 VALUES LESS THAN ('2015-09-12 12:00:00'),
    PARTITION p18 VALUES LESS THAN ('2015-09-12 18:00:00'),
    PARTITION p24 VALUES LESS THAN ('2015-09-13 00:00:00')
)
DISTRIBUTED BY HASH(user);

CREATE TABLE source_wiki_edit
(
    event_time      DATETIME,
    channel         VARCHAR(32)      DEFAULT '',
    user            VARCHAR(128)     DEFAULT '',
    is_anonymous    TINYINT          DEFAULT '0',
    is_minor        TINYINT          DEFAULT '0',
    is_new          TINYINT          DEFAULT '0',
    is_robot        TINYINT          DEFAULT '0',
    is_unpatrolled  TINYINT          DEFAULT '0',
    delta           INT              DEFAULT '0',
    added           INT              DEFAULT '0',
    deleted         INT              DEFAULT '0'
)
DUPLICATE KEY(
    event_time,
    channel,user,
    is_anonymous,
    is_minor,
    is_new,
    is_robot,
    is_unpatrolled
)
PARTITION BY RANGE(event_time)(
    PARTITION p06 VALUES LESS THAN ('2015-09-12 06:00:00'),
    PARTITION p12 VALUES LESS THAN ('2015-09-12 12:00:00'),
    PARTITION p18 VALUES LESS THAN ('2015-09-12 18:00:00'),
    PARTITION p24 VALUES LESS THAN ('2015-09-13 00:00:00')
)
DISTRIBUTED BY HASH(user);
```

> **注意**
>
> 自 2.5.7 版本起，StarRocks 支持在建表和新增分区时自动设置分桶数量 (BUCKETS)，您无需手动设置分桶数量。更多信息，请参见 [设置分桶数量](../table_design/data_distribution/Data_distribution.md#设置分桶数量)。

## 通过 INSERT INTO VALUES 语句导入数据

您可以通过 INSERT INTO VALUES 语句向指定的表中直接导入数据。此导入方式中，多条数据用逗号（,）分隔。详细使用方式，参考 [SQL 参考 - INSERT](../sql-reference/sql-statements/loading_unloading/INSERT.md)。详细参数信息，参考 [INSERT 参数说明](../sql-reference/sql-statements/loading_unloading/INSERT.md#参数说明)。

> **注意**
>
> INSERT INTO VALUES 语句导入方式仅适用于导入少量数据作为验证 DEMO 用途，不适用于大规模测试或生产环境。如需大规模导入数据，请选择其他导入方式。

以下示例以 `insert_load_wikipedia` 为 Label 向源表 `source_wiki_edit` 中导入两条数据。Label 是导入作业的标识，数据库内唯一。

```SQL
INSERT INTO source_wiki_edit
WITH LABEL insert_load_wikipedia
VALUES
    ("2015-09-12 00:00:00","#en.wikipedia","AustinFF",0,0,0,0,0,21,5,0),
    ("2015-09-12 00:00:00","#ca.wikipedia","helloSR",0,1,0,1,0,3,23,0);
```

| 参数       | 说明                                                         |
| ---------- | ------------------------------------------------------------ |
| table_name | 导入数据的目标表。可以使用 `db_name.table_name` 形式。       |
| label      | 导入作业的标识，数据库内唯一。如果未指定，StarRocks 会自动为作业生成一个 Label。建议您指定 Label。否则，如果当前导入作业因网络错误无法返回结果，您将无法得知该导入操作是否成功。如果指定了 Label，可以通过 SQL 命令 `SHOW LOAD WHERE label="label";` 查看作业结果。 |
| values     | 通过 VALUES 语法插入一条或者多条数据，多条数据用逗号（,）分隔。 |

## 通过 INSERT INTO SELECT 语句导入数据

您可以通过 INSERT INTO SELECT 语句将源表中的数据导入至目标表中。INSERT INTO SELECT 将源表中的数据进行 ETL 转换之后，导入到 StarRocks 内表中。源表可以是一张或多张内部表或者外部表，甚至云存储或 HDFS 中的数据文件。目标表必须是 StarRocks 的内表。执行该语句之后，系统将 SELECT 语句结果导入目标表。详细使用方式，参考 [INSERT](../sql-reference/sql-statements/loading_unloading/INSERT.md)。详细参数信息，参考 [INSERT 参数](../sql-reference/sql-statements/loading_unloading/INSERT.md#参数说明)。

### 通过 INSERT INTO SELECT 将内外表数据导入内表

> 说明
>
> 以下示例仅展示导入内部表数据，其操作过程与导入外部表数据相同，故不重复演示导入外部表数据过程。

- 以下示例以 `insert_load_wikipedia_1` 为 Label 将源表中的数据导入至目标表中。

```SQL
INSERT INTO insert_wiki_edit
WITH LABEL insert_load_wikipedia_1
SELECT * FROM source_wiki_edit;
```

- 以下示例以 `insert_load_wikipedia_2` 为 Label 将源表中的数据导入至目标表的 `p06` 和 `p12` 分区中。如果不指定目标分区，数据将会导入全表；如果指定目标分区，数据只会导入指定的分区。

```SQL
INSERT INTO insert_wiki_edit PARTITION(p06, p12)
WITH LABEL insert_load_wikipedia_2
SELECT * FROM source_wiki_edit;
```

如果清空 `p06` 和 `p12` 分区，则查询不到先前插入至对应分区的数据。

```Plain
MySQL > select * from insert_wiki_edit;
+---------------------+---------------+----------+--------------+----------+--------+----------+----------------+-------+-------+---------+
| event_time          | channel       | user     | is_anonymous | is_minor | is_new | is_robot | is_unpatrolled | delta | added | deleted |
+---------------------+---------------+----------+--------------+----------+--------+----------+----------------+-------+-------+---------+
| 2015-09-12 00:00:00 | #en.wikipedia | AustinFF |            0 |        0 |      0 |        0 |              0 |    21 |     5 |       0 |
| 2015-09-12 00:00:00 | #ca.wikipedia | helloSR  |            0 |        1 |      0 |        1 |              0 |     3 |    23 |       0 |
+---------------------+---------------+----------+--------------+----------+--------+----------+----------------+-------+-------+---------+
2 rows in set (0.00 sec)

MySQL > TRUNCATE TABLE insert_wiki_edit PARTITION(p06, p12);
Query OK, 0 rows affected (0.01 sec)

MySQL > select * from insert_wiki_edit;
Empty set (0.00 sec)
```

- 以下示例以 `insert_load_wikipedia_3` 为 Label 将源表中 `event_time` 和 `channel` 列的数据导入至目标表的对应列中。未被导入的列将被赋予默认值。

```SQL
INSERT INTO insert_wiki_edit
WITH LABEL insert_load_wikipedia_3 
(
    event_time, 
    channel
)
SELECT event_time, channel FROM source_wiki_edit;
```

:::note
自 v3.3.1 起，INSERT INTO 导入主键表时指定 Column List 会执行部分列更新（而在先前版本中，指定 Column List 仍然导致 Full Upsert）。如不指定 Column List，系统执行 Full Upsert。
:::

| 参数        | 说明                                                         |
| ----------- | ------------------------------------------------------------ |
| table_name  | 导入数据的目标表。可以为 `db_name.table_name` 形式。         |
| partitions  | 导入的目标分区。此参数必须是目标表中存在的分区，多个分区名称用逗号（`,`）分隔。如果指定该参数，数据只会被导入相应分区内。如果未指定，则默认将数据导入至目标表的所有分区。 |
| label       | 导入作业的标识，数据库内唯一。如果未指定，StarRocks 会自动为作业生成一个 Label。建议您指定 Label。否则，如果当前导入作业因网络错误无法返回结果，您将无法得知该导入操作是否成功。如果指定了 Label，可以通过 SQL 命令 `SHOW LOAD WHERE label="label"` 查看作业结果。 |
| column_name | 导入的目标列，必须是目标表中存在的列。该参数与导入数据的列的名称可以不同，但顺序需一一对应。如果不指定目标列，默认为目标表中的所有列。如果源表中的某个列在目标列不存在，则写入默认值。如果当前列没有默认值，导入作业会失败。如果查询语句的结果列类型与目标列的类型不一致，会进行隐式转化，如果不能进行转化，那么 INSERT INTO 语句会报语法解析错误。 |
| query       | 查询语句，查询的结果会导入至目标表中。查询语句支持任意 StarRocks 支持的 SQL 查询语法。 |

### 通过 INSERT INTO SELECT 以及表函数 FILES() 导入外部数据文件

自 v3.1 起，StarRocks 支持使用 INSERT 语句和 [FILES()](../sql-reference/sql-functions/table-functions/files.md) 表函数直接导入云存储或 HDFS 中的文件，无需提前创建 External Catalog 或文件外部表。除此之外，FILES() 支持自动推断 Table Schema，大大简化导入过程。

以下示例将 AWS S3 存储桶 `inserttest` 内 Parquet 文件 **parquet/insert_wiki_edit_append.parquet** 中的数据插入至表 `insert_wiki_edit` 中：

```Plain
INSERT INTO insert_wiki_edit
    SELECT * FROM FILES(
        "path" = "s3://inserttest/parquet/insert_wiki_edit_append.parquet",
        "format" = "parquet",
        "aws.s3.access_key" = "XXXXXXXXXX",
        "aws.s3.secret_key" = "YYYYYYYYYY",
        "aws.s3.region" = "us-west-2"
);
```

## 通过 INSERT OVERWRITE VALUES 语句覆盖写入数据

您可以通过 INSERT OVERWRITE VALUES 语句向指定的表中覆盖写入数据。此导入方式中，多条数据用逗号（,）分隔。详细使用方式，参考 [INSERT](../sql-reference/sql-statements/loading_unloading/INSERT.md)。详细参数信息，参考 [INSERT 参数说明](../sql-reference/sql-statements/loading_unloading/INSERT.md#参数说明)。

> **注意**
>
> INSERT OVERWRITE VALUES 语句导入方式仅适用于导入少量数据作为验证 DEMO 用途，不适用于大规模测试或生产环境。如需大规模导入数据，请选择其他导入方式。

查询源表以及目标表确认其中已有数据。

```Plain
MySQL > SELECT * FROM source_wiki_edit;
+---------------------+---------------+----------+--------------+----------+--------+----------+----------------+-------+-------+---------+
| event_time          | channel       | user     | is_anonymous | is_minor | is_new | is_robot | is_unpatrolled | delta | added | deleted |
+---------------------+---------------+----------+--------------+----------+--------+----------+----------------+-------+-------+---------+
| 2015-09-12 00:00:00 | #ca.wikipedia | helloSR  |            0 |        1 |      0 |        1 |              0 |     3 |    23 |       0 |
| 2015-09-12 00:00:00 | #en.wikipedia | AustinFF |            0 |        0 |      0 |        0 |              0 |    21 |     5 |       0 |
+---------------------+---------------+----------+--------------+----------+--------+----------+----------------+-------+-------+---------+
2 rows in set (0.02 sec)
 
MySQL > SELECT * FROM insert_wiki_edit;
+---------------------+---------------+----------+--------------+----------+--------+----------+----------------+-------+-------+---------+
| event_time          | channel       | user     | is_anonymous | is_minor | is_new | is_robot | is_unpatrolled | delta | added | deleted |
+---------------------+---------------+----------+--------------+----------+--------+----------+----------------+-------+-------+---------+
| 2015-09-12 00:00:00 | #ca.wikipedia | helloSR  |            0 |        1 |      0 |        1 |              0 |     3 |    23 |       0 |
| 2015-09-12 00:00:00 | #en.wikipedia | AustinFF |            0 |        0 |      0 |        0 |              0 |    21 |     5 |       0 |
+---------------------+---------------+----------+--------------+----------+--------+----------+----------------+-------+-------+---------+
2 rows in set (0.01 sec)
```

以下示例以 `insert_load_wikipedia_ow` 为 Label 向源表 `source_wiki_edit` 中覆盖写入两条数据。

```SQL
INSERT OVERWRITE source_wiki_edit
WITH LABEL insert_load_wikipedia_ow
VALUES
    ("2015-09-12 00:00:00","#cn.wikipedia","GELongstreet",0,0,0,0,0,36,36,0),
    ("2015-09-12 00:00:00","#fr.wikipedia","PereBot",0,1,0,1,0,17,17,0);
```

## 通过 INSERT OVERWRITE SELECT 语句覆盖写入数据

您可以通过 INSERT OVERWRITE SELECT 语句将源表中的数据覆盖写入至目标表中。INSERT OVERWRITE SELECT 将源表中的数据进行 ETL 转换之后，覆盖写入到 StarRocks 内表中。源表可以是一张或多张内部表或者外部表。目标表必须是 StarRocks 的内表。执行该语句之后，系统使用 SELECT 语句结果覆盖目标表的数据。详细使用方式，参考 [INSERT](../sql-reference/sql-statements/loading_unloading/INSERT.md)。详细参数信息，参考 [INSERT 参数](../sql-reference/sql-statements/loading_unloading/INSERT.md#参数说明)。

> 说明
>
> 以下示例仅展示导入内部表数据，其操作过程与导入外部表数据相同，故不重复演示导入外部表数据过程。

- 以下示例以 `insert_load_wikipedia_ow_1` 为 Label 将源表中的数据覆盖写入至目标表中。

```SQL
INSERT OVERWRITE insert_wiki_edit
WITH LABEL insert_load_wikipedia_ow_1
SELECT * FROM source_wiki_edit;
```

- 以下示例以 `insert_load_wikipedia_2` 为 Label 将源表中的数据覆盖写入至目标表的 `p06` 和 `p12` 分区中。如果不指定目标分区，数据将会覆盖写入全表；如果指定目标分区，数据只会覆盖写入指定的分区。

```SQL
INSERT OVERWRITE insert_wiki_edit PARTITION(p06, p12)
WITH LABEL insert_load_wikipedia_ow_2
SELECT * FROM source_wiki_edit;
```

如果清空 `p06` 和 `p12` 分区，则查询不到先前覆盖写入至对应分区的数据。

```Plain
MySQL > select * from insert_wiki_edit;
+---------------------+---------------+--------------+--------------+----------+--------+----------+----------------+-------+-------+---------+
| event_time          | channel       | user         | is_anonymous | is_minor | is_new | is_robot | is_unpatrolled | delta | added | deleted |
+---------------------+---------------+--------------+--------------+----------+--------+----------+----------------+-------+-------+---------+
| 2015-09-12 00:00:00 | #fr.wikipedia | PereBot      |            0 |        1 |      0 |        1 |              0 |    17 |    17 |       0 |
| 2015-09-12 00:00:00 | #cn.wikipedia | GELongstreet |            0 |        0 |      0 |        0 |              0 |    36 |    36 |       0 |
+---------------------+---------------+--------------+--------------+----------+--------+----------+----------------+-------+-------+---------+
2 rows in set (0.01 sec)

MySQL > TRUNCATE TABLE insert_wiki_edit PARTITION(p06, p12);
Query OK, 0 rows affected (0.01 sec)

MySQL > select * from insert_wiki_edit;
Empty set (0.00 sec)
```

:::note
对于使用列表达式分区方式（`PARTITION BY column`）的表，INSERT OVERWRITE 支持通过指定分区键的值在目标表上创建不存在的分区。对于已有的分区，将正常进行覆盖写。

以下示例创建了分区表 `activity`，向其中导入新数据时自动创建了先前不存在的分区：

```SQL
CREATE TABLE activity (
id INT          NOT NULL,
dt VARCHAR(10)  NOT NULL
) ENGINE=OLAP 
DUPLICATE KEY(`id`)
PARTITION BY (`id`, `dt`)
DISTRIBUTED BY HASH(`id`);

INSERT OVERWRITE activity
PARTITION(id='4', dt='2022-01-01')
WITH LABEL insert_activity_auto_partition
VALUES ('4', '2022-01-01');
```

:::

- 以下示例以 `insert_load_wikipedia_ow_3` 为 Label 将源表中 `event_time` 和 `channel` 列的数据覆盖写入至目标表的对应列中。未被导入的列将被赋予默认值。

```SQL
INSERT OVERWRITE insert_wiki_edit
WITH LABEL insert_load_wikipedia_ow_3 
(
    event_time, 
    channel
)
SELECT event_time, channel FROM source_wiki_edit;
```

### Dynamic Overwrite

从 v3.4.0 开始，StarRocks 支持分区表的 INSERT OVERWRITE 操作的新语义 — Dynamic Overwrite。

当前 INSERT OVERWRITE 默认行为如下：

- 当覆盖整个分区表（即未指定 PARTITION 子句）时，新数据会替换对应分区中的数据。如果存在表中已有分区未涉及覆盖操作，系统会清空该分区数据。
- 当覆盖空的分区表（即其中没有任何分区）但指定了 PARTITION 子句时，系统会报错 `ERROR 1064 (HY000): Getting analyzing error. Detail message: Unknown partition 'xxx' in table 'yyy'`。
- 当覆盖分区表时指定了不存在的分区，系统会报错 `ERROR 1064 (HY000): Getting analyzing error. Detail message: Unknown partition 'xxx' in table 'yyy'`。
- 当覆盖分区表的数据与指定的分区不匹配时，如果开启严格模式，系统会报错 `ERROR 1064 (HY000): Insert has filtered data in strict mode`；如果未开启严格模式，系统会过滤不合格的数据。

新的 Dynamic Overwrite 语义的行为与上述默认行为有很大不同：

当覆盖整个分区表时，新数据会替换对应分区中的数据。但未涉及的分区会保留，而不会被清空或删除。如果新数据对应不存在的分区，系统会自动创建该分区。

Dynamic Overwrite 语义默认禁用。如需启用，需要将系统变量 `dynamic_overwrite` 设置为 `true`。

在当前 Session 中启用 Dynamic Overwrite:

```SQL
SET dynamic_overwrite = true;
```

您也可以在 INSERT OVERWRITE 语句中通过 Hint 启用 Dynamic Overwrite，仅对该语句生效：

示例：

```SQL
INSERT /*+set_var(dynamic_overwrite = true)*/ OVERWRITE insert_wiki_edit
SELECT * FROM source_wiki_edit;
```

## 通过 INSERT 语句导入数据至生成列

生成列（Generated Columns）是一种特殊的列，它的值会根据列定义中的表达式自动计算得出。并且，你不能直接写入或更新生成列的值。当您的查询请求涉及对表达式的计算时，例如查询 JSON 类型的某个字段，或者针对 ARRAY 数据计算，生成列尤其有用。在数据导入时，StarRocks 将计算表达式，然后将结果存储在生成列中，从而避免了在查询过程中计算表达式，进而提高了查询性能。

您可以使用 INSERT 语句将数据导入至包含生成列的表中。

以下示例创建了表 `insert_generated_columns` 并向其中插入一行数据。该表包含两个生成列：`avg_array` 和 `get_string`。`avg_array` 计算 `data_array` 中 ARRAY 类型数据的平均值，`get_string` 从 `data_json` 中提取 JSON 路径为 `a`  的字符串。

```SQL
CREATE TABLE insert_generated_columns (
  id           INT(11)           NOT NULL    COMMENT "ID",
  data_array   ARRAY<INT(11)>    NOT NULL    COMMENT "ARRAY",
  data_json    JSON              NOT NULL    COMMENT "JSON",
  avg_array    DOUBLE            NULL 
      AS array_avg(data_array)               COMMENT "Get the average of ARRAY",
  get_string   VARCHAR(65533)    NULL 
      AS get_json_string(json_string(data_json), '$.a') COMMENT "Extract JSON string"
) ENGINE=OLAP 
PRIMARY KEY(id)
DISTRIBUTED BY HASH(id);

INSERT INTO insert_generated_columns 
VALUES (1, [1,2], parse_json('{"a" : 1, "b" : 2}'));
```

> **说明**
>
> 不支持将数据直接导入至生成列中。

查询该表以查看其中的数据。

```Plain
mysql> SELECT * FROM insert_generated_columns;
+------+------------+------------------+-----------+------------+
| id   | data_array | data_json        | avg_array | get_string |
+------+------------+------------------+-----------+------------+
|    1 | [1,2]      | {"a": 1, "b": 2} |       1.5 | 1          |
+------+------------+------------------+-----------+------------+
1 row in set (0.02 sec)
```

## 插入数据时配置 PROPERTIES

从 v3.4.0 版本开始，INSERT 语句支持配置 PROPERTIES，可用于多种功能。当同时指定 PROPERTIES 和其对应的系统变量时，PROPERTIES 优先生效。

### 启用严格模式

从 v3.4.0 起，您可以为 INSERT from FILES() 启用严格模式并设置 `max_filter_ratio`。INSERT from FILES() 的严格模式与其他导入方法的行为相同。

如果要导入包含某些不合格行的数据集，您可以选择过滤这些不合格行，或导入该数据行并为其中不合格的列赋 NULL 值。您可以使用 `strict_mode` 和 `max_filter_ratio` 属性实现这两种方式。

- 如需过滤不合格行，需将 `strict_mode` 设置为 `true`，并将 `max_filter_ratio` 设置为所需值。
- 如需导入不合格行并赋予 NULL 值，需将 `strict_mode` 设置为 `false`。

以下示例将 AWS S3 存储桶 `inserttest` 内 Parquet 文件 **parquet/insert_wiki_edit_append.parquet** 中的数据插入至表 `insert_wiki_edit` 中，启用严格模式以过滤不合格的数据行，并且设置最大容错比为 10%：

```SQL
INSERT INTO insert_wiki_edit
PROPERTIES(
    "strict_mode" = "true",
    "max_filter_ratio" = "0.1"
)
SELECT * FROM FILES(
    "path" = "s3://inserttest/parquet/insert_wiki_edit_append.parquet",
    "format" = "parquet",
    "aws.s3.access_key" = "XXXXXXXXXX",
    "aws.s3.secret_key" = "YYYYYYYYYY",
    "aws.s3.region" = "us-west-2"
);
```

:::note

`strict_mode` 和 `max_filter_ratio` 仅支持 INSERT from FILES() 导入方式。INSERT from Table 导入方式不支持以上属性。

:::

### 设置超时时间

从 v3.4.0 开始，您可以通过 PROPERTIES 设置 INSERT 语句的超时时间。

以下示例将源表 `source_wiki_edit` 中的数据插入到目标表 `insert_wiki_edit`，并将超时时间设置为 `2` 秒：

```SQL
INSERT INTO insert_wiki_edit
PROPERTIES(
    "timeout" = "2"
)
SELECT * FROM source_wiki_edit;
```

:::note

从 v3.4.0 起，您还可以使用系统变量 `insert_timeout` 来设置 INSERT 超时时间，适用于涉及 INSERT 的操作（例如，UPDATE、DELETE、CTAS、物化视图刷新、统计数据收集和 PIPE）。在 v3.4.0 之前的版本中，相应的变量是 `query_timeout`。

:::

### INSERT 按名称匹配列

默认情况下，INSERT 根据源表和目标表中列的位置（即语句中列的映射关系）来匹配列。

以下示例通过指定位置的方式显式匹配源表和目标表中的列：

```SQL
INSERT INTO insert_wiki_edit (
    event_time,
    channel,
    user
)
SELECT event_time, channel, user FROM source_wiki_edit;
```

如果您在 Column List 或 SELECT 语句中改变了 `channel` 和 `user` 的顺序，列的映射关系将发生变化。

```SQL
INSERT INTO insert_wiki_edit (
    event_time,
    channel,
    user
)
SELECT event_time, user, channel FROM source_wiki_edit;
```

此处，由于目标表 `insert_wiki_edit` 中的 `channel` 列被源表 `source_wiki_edit` 中的 `user` 的数据所填满，导入的数据可能并不是所需的结果。

通过在 INSERT 语句中添加 `BY NAME` 子句，系统将根据检查源表和目标表中的列名，匹配同名的列。

:::note

- 如果指定了 `BY NAME`，则不能指定 Column List。
- 如果未指定 `BY NAME`，系统将根据 Column List 和 SELECT 语句中列的位置来匹配列。

:::

以下示例通过列名匹配源表和目标表中的列：

```SQL
INSERT INTO insert_wiki_edit BY NAME
SELECT event_time, user, channel FROM source_wiki_edit;
```

在这种情况下，改变 `channel` 和 `user` 的顺序不会改变列的映射关系。

## 通过 INSERT 语句异步导入数据

使用 INSERT 语句创建的同步导入任务，可能会因为会话中断或超时而失败。您可以使用 [SUBMIT TASK](../sql-reference/sql-statements/loading_unloading/ETL/SUBMIT_TASK.md) 语句提交异步 INSERT 任务。此功能自 StarRocks v2.5 起支持。

- 以下示例将源表中的数据异步导入至目标表中。

```SQL
SUBMIT TASK AS INSERT INTO insert_wiki_edit
SELECT * FROM source_wiki_edit;
```

- 以下示例将源表中的数据异步覆盖写入至目标表中。

```SQL
SUBMIT TASK AS INSERT OVERWRITE insert_wiki_edit
SELECT * FROM source_wiki_edit;
```

- 以下示例将源表中的数据异步覆盖写入至目标表中，并通过 Hint 将 Query Timeout 设置为 `100000` 秒。

```SQL
SUBMIT /*+set_var(insert_timeout=100000)*/ TASK AS
INSERT OVERWRITE insert_wiki_edit
SELECT * FROM source_wiki_edit;
```

- 以下示例将源表中的数据异步覆盖写入至目标表中，并将任务命名为 `async`。

```SQL
SUBMIT TASK async
AS INSERT OVERWRITE insert_wiki_edit
SELECT * FROM source_wiki_edit;
```

您可以通过查询 Information Schema 中的元数据视图 `task_runs` 来查看异步 INSERT 任务的状态。

以下示例查看异步 INSERT 任务 `async` 的状态。

```SQL
SELECT * FROM information_schema.task_runs WHERE task_name = 'async';
```

## 查看导入作业状态

### 通过返回结果查看

同步 INSERT 导入作业会根据执行结果的不同，返回以下两种作业状态：

- **执行成功**

如果导入执行成功，StarRocks 的返回如下：

```Plain
Query OK, 2 rows affected, 2 warnings (0.05 sec)
{'label':'insert_load_wikipedia', 'status':'VISIBLE', 'txnId':'1006'}
```

| 返回          | 说明                                                         |
| ------------- | ------------------------------------------------------------ |
| rows affected | 表示总共有多少行数据被导入。`warnings` 表示被过滤的行数。    |
| label         | 用户指定或自动生成的 Label。Label 是该 INSERT 导入作业的标识，当前数据库内唯一。 |
| status        | 表示导入数据是否可见。VISIBLE 表示可见，COMMITTED 表示已提交但暂不可见。 |
| txnId         | 该 INSERT 导入对应的导入事务 ID。                            |

- **执行失败**

如果所有数据都无法被导入，则导入执行失败，StarRocks 将返回相应错误以及 `tracking_url`。您可以通过 `tracking_url` 查看错误相关的日志信息并排查问题。

```Plain
ERROR 1064 (HY000): Insert has filtered data in strict mode, tracking_url=http://x.x.x.x:yyyy/api/_load_error_log?file=error_log_9f0a4fd0b64e11ec_906bbede076e9d08
```

### 通过 Information Schema 查看

您可以通过 [SELECT](../sql-reference/sql-statements/table_bucket_part_index/SELECT.md) 语句从 `information_schema` 数据库中的 `loads` 表来查看 INSERT INTO 作业的结果。该功能自 3.1 版本起支持。

示例一：查看 `load_test` 数据库中导入作业的执行情况，同时指定查询结果根据作业创建时间 (`CREATE_TIME`) 按降序排列，并且最多显示一条结果数据：

```SQL
SELECT * FROM information_schema.loads
WHERE database_name = 'load_test'
ORDER BY create_time DESC
LIMIT 1\G
```

示例二：查看 `load_test` 数据库中 Label 为 `insert_load_wikipedia` 的导入作业的执行情况：

```SQL
SELECT * FROM information_schema.loads
WHERE database_name = 'load_test' and label = 'insert_load_wikipedia'\G
```

以上示例返回如下：

```Plain
*************************** 1. row ***************************
              JOB_ID: 21319
               LABEL: insert_load_wikipedia
       DATABASE_NAME: load_test
               STATE: FINISHED
            PROGRESS: ETL:100%; LOAD:100%
                TYPE: INSERT
            PRIORITY: NORMAL
           SCAN_ROWS: 0
       FILTERED_ROWS: 0
     UNSELECTED_ROWS: 0
           SINK_ROWS: 2
            ETL_INFO: 
           TASK_INFO: resource:N/A; timeout(s):300; max_filter_ratio:0.0
         CREATE_TIME: 2023-08-09 10:42:23
      ETL_START_TIME: 2023-08-09 10:42:23
     ETL_FINISH_TIME: 2023-08-09 10:42:23
     LOAD_START_TIME: 2023-08-09 10:42:23
    LOAD_FINISH_TIME: 2023-08-09 10:42:24
         JOB_DETAILS: {"All backends":{"5ebf11b5-365e-11ee-9e4a-7a563fb695da":[10006]},"FileNumber":0,"FileSize":0,"InternalTableLoadBytes":175,"InternalTableLoadRows":2,"ScanBytes":0,"ScanRows":0,"TaskNumber":1,"Unfinished backends":{"5ebf11b5-365e-11ee-9e4a-7a563fb695da":[]}}
           ERROR_MSG: NULL
        TRACKING_URL: NULL
        TRACKING_SQL: NULL
REJECTED_RECORD_PATH: NULL
1 row in set (0.01 sec)
```

有关返回字段的说明，参见 [`information_schema.loads`](../sql-reference/information_schema/loads.md)。

## 相关配置项

你可以为 INSERT 导入作业设定以下配置项：

- **FE 配置项**

| FE 配置项                          | 说明                                                         |
| ---------------------------------- | ------------------------------------------------------------ |
| insert_load_default_timeout_second | INSERT 导入作业的超时时间，单位为秒。如果当前 INSERT 导入作业在该参数设定的时间内未完成则会被系统取消，状态为 CANCELLED。目前仅支持通过该参数为所有 INSERT 导入作业统一设定超时时间，不支持为单独的导入作业设置超时时间。默认为 3600 秒（1 小时）。如果导入作业无法在规定时间内完成，您可以通过调整该参数延长超时时间。 |

- **Session 变量**

| Session 变量         | 说明                                                         |
| -------------------- | ------------------------------------------------------------ |
| enable_insert_strict | INSERT 导入是否容忍错误数据行。设置为 `true` 时，如果有一条数据错误，则返回导入失败。设置为 `false` 时，如果至少有一条数据被正确导入，则返回导入成功，并会返回一个 Label。该参数默认为 `true`。您可以通过 `SET enable_insert_strict = {true or false};` 命令来设定该参数。 |
| insert_timeout        | INSERT 命令的超时时间，单位为秒。您可以通过 `SET insert_timeout = xxx;` 命令来设定该参数。 |
