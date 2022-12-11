<!--
    Licensed to the Apache Software Foundation (ASF) under one or more 
    contributor license agreements.  See the NOTICE file distributed with
    this work for additional information regarding copyright ownership. 
    The ASF licenses this file to you under the Apache License, Version 2.0
    (the "License"); you may not use this file except in compliance with 
    the License.  You may obtain a copy of the License at

```
  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software 
distributed under the License is distributed on an "AS IS" BASIS, 
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and 
limitations under the License.
```

-->

# CarbonData table structure

CarbonData files contain groups of data called blocklets, along with all required information like schema, offsets and indices etc, in a file header and footer, co-located in HDFS.

The file footer can be read once to build the indices in memory, which can be utilized for optimizing the scans and processing for all subsequent queries.

This document describes the what a CarbonData table looks like in a HDFS directory, files written and content of each file.

- [File Directory Structure](#file-directory-structure)

- [File Content details](#file-content-details)
  - [Schema file format](#schema-file-format)
  - [CarbonData file format](#carbondata-file-format)
    - [Blocklet format](#blocklet-format)
      - [V1](#v1)
      - [V2](#v2)
      - [V3](#v3)
    - [Footer format](#footer-format)
  - [carbonindex file format](#carbonindex-file-format)
  - [Dictionary file format](#dictionary-file-format)
  - [tablestatus file format](#tablestatus-file-format)

## File Directory Structure

The CarbonData files are stored in the location specified by the ***spark.sql.warehouse.dir*** configuration (configured in carbon.properties; if not configured, the default is ../carbon.store).

  The file directory structure is as below: 

![File Directory Structure](../docs/images/2-1_1_latest.PNG?raw=true)

1. The **default** is the database name and contains the user tables.default is used when user doesn't specify any database name;else user configured database name will be the directory name. user_table is the table name.
2. Metadata directory stores schema files, tablestatus and segment details (includes .segment file for each segment). There are three types of metadata data information files.
3. data and index files are stored under directory named **Fact**. The Fact directory has a Part0 partition directory, where 0 is the partition number.
4. There is a Segment_0 directory under the Part0 directory, where 0 is the segment number.
5. There are two types of files, carbondata and carbonmergeindex, in the Segment_0 directory.



## File Content details

When the table is created, the user_table directory is generated, and a schema file is generated in the Metadata directory for recording the table structure.

When loading data in batches, each batch loading generates a new segment directory. The scheduling tries to control a task processing data loading task on each node. Each task will generate multiple carbondata files and one carbonindex file.

The following sections use the Java object generated by the thrift file describing the carbondata file format to explain the contents of each file one by one (you can also directly read the format defined in the [thrift file](https://github.com/apache/carbondata/tree/master/format/src/main/thrift))

### Schema file format

The contents of the schema file is as shown below

![Schema file format](../docs/images/2-2_1.png?raw=true)

1. TableSchema class
    The TableSchema class does not store the table name, it is infered from the directory name(user_table).
    tableProperties is used to record table-related properties, such as: table_blocksize.
2. ColumnSchema class
    Encoders are used to record the encoding used in column storage.
    columnProperties is used to record column related properties.
3. BucketingInfo class
    When creating a bucket table, you can specify the number of buckets in the table and the column to splitbuckets.
4. DataType class
    Describes the data types supported by CarbonData.
5. Encoding class
    Several encodings that may be used in CarbonData files.

### CarbonData file format

#### File Header

It contains CarbonData file version number, list of column schema and schema updation timestamp.

![File Header](../docs/images/carbon_data_file_structure_new.png?raw=true)

The carbondata file consists of multiple blocklets and footer parts. The blocklet is the dataset inside the carbondata file (the latest V3 format, the default configuration is 64MB), each blocklet contains a ColumnChunk for each column, and a ColumnChunk may contain one or more Column Pages.

The carbondata file currently supports V1, V2 and V3 versions. The main difference is the change of the blocklet part, which is introduced one by one.

#### Blocklet format

#####  V1

 Blocket consists of all column data pages, RLE pages, and rowID pages. Since the pages in the blocklet are grouped according to the page type, the three pieces of data of each column are distributed and stored in the blocklet, and the offset and length information of all the pages need to be recorded in the footer part.

![V1](../docs/images/2-3_1.png?raw=true)

##### V2

The blocklet consists of ColumnChunk for all columns. The ColumnChunk for a column consists of a ColumnPage, which includes the data chunk header, data page, RLE page, and rowID page. Since ColumnChunk aggregates the three types of Page data of the column together, it can read the column data using fewer readers. Since the header part records the length information of all the pages, the footer part only needs to record the offset and length of the ColumnChunk, and also reduces the amount of footer data.

![V2](../docs/images/2-3_2.png?raw=true)

##### V3

The blocklet is also composed of ColumnChunks of all columns. What is changed is that a ColumnChunk consists of one or more Column Pages, and Column Page adds a new BlockletMinMaxIndex.

Compared with V2: The blocklet data volume of V2 format defaults to 120,000 lines, and the blocklet data volume of V3 format defaults to 64MB. For the same size data file, the information of the footer part index metadata may be further reduced; meanwhile, the V3 format adds a new page. Level data filtering, and the amount of data per page is only 32,000 lines by default, which is much less than the 120,000 lines of V2 format. The accuracy of data filtering hits further, and more data can be filtered out before decompressing data.

![V3](../docs/images/2-3_3.png?raw=true)

#### Footer format

Footer records each carbondata, all blocklet data distribution information and statistical related metadata information (minmax, startkey/endkey) inside the file.

![Footer format](../docs/images/2-3_4.png?raw=true)

1.  BlockletInfo3 is used to record the offset and length of all ColumnChunk3.
2.  SegmentInfo is used to record the number of columns and the cardinality of each column.
3.  BlockletIndex includes BlockletMinMaxIndex and BlockletBTreeIndex.

BlockletBTreeIndex is used to record the startkey/endkey of all blocklets in the block. When querying, the startkey/endkey of the query is generated by filtering conditions combined with mdkey. With BlocketBtreeIndex, the range of blocklets satisfying the conditions in each block can be delineated.

BlockletMinMaxIndex is used to record the min/max value of all columns in the blocklet. By using the min/max check on the filter condition, you can skip the block/blocklet that does not satisfy the condition.

### carbonindex file format

Extract the BlockletIndex part of the footer part to generate the carbonindex file. Load data in batches, schedule as much as possible to control a node to start a task, each task generates multiple carbondata files and a carbonindex file. The carbonindex file records the index information of all the blocklets in all the carbondata files generated by the task.

As shown in the figure, the index information corresponding to a block is recorded by a BlockIndex object, including carbondata filename, footer offset and BlockletIndex. The BlockIndex data volume is less than the footer. The file is directly used to build the index on the driver side when querying, without having to skip the footer part of the data volume of multiple data files.

![carbonindex file format](../docs/images/2-4_1.png?raw=true)

### Dictionary file format


For each dictionary encoded column, a dictionary file is used to store the dictionary metadata for that column.

1. dict file records the distinct value list of a column

For the first time dataloading, the file is generated using a distinct value list of a column. The value in the file is unordered; the subsequent append is used. In the second step of dataloading (Data Convert Step), the dictionary code column will replace the true value of the data with the dictionary key.

![Dictionary file format](../docs/images/2-5_1.png?raw=true)


2.  dictmeta records the metadata description of the new distinct value of each dataloading

The dictionary cache uses this information to incrementally flush the cache.

![Dictionary Chunk](../docs/images/2-5_2.png?raw=true)
	

3.  sortindex records the result set of the key code of the dictionary code sorted by value.

In dataLoading, if there is a new dictionary value, the sortindex file will be regenerated using all the dictionary codes.

Filtering queries based on dictionary code columns need to convert the value filter filter to the key filter condition. Using the sortindex file, you can quickly construct an ordered value sequence to quickly find the key value corresponding to the value, thus speeding up the conversion process.

![sortindex file format](../docs/images/2-5_3.png?raw=true)

### tablestatus file format

Tablestatus records the segment-related information (in gson format) for each load and merge, including load time, load status, segment name, whether it was deleted, and the segment name incorporated. Regenerate the tablestatusfile after each load or merge.

![tablestatus file format](../docs/images/2-6_1.png?raw=true)
