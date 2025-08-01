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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/catalog/FunctionSet.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.catalog;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.ArithmeticExpr;
import com.starrocks.analysis.FunctionName;
import com.starrocks.builtins.VectorizedBuiltinFunctions;
import com.starrocks.catalog.combinator.AggStateCombinator;
import com.starrocks.catalog.combinator.AggStateIf;
import com.starrocks.catalog.combinator.AggStateMergeCombinator;
import com.starrocks.catalog.combinator.AggStateUnionCombinator;
import com.starrocks.catalog.combinator.AggStateUtils;
import com.starrocks.sql.analyzer.PolymorphicFunctionAnalyzer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FunctionSet {
    // For most build-in functions, it will return NullLiteral when params contain NullLiteral.
    // But a few functions need to handle NullLiteral differently, such as "if". It need to add
    // an attribute to LiteralExpr to mark null and check the attribute to decide whether to
    // replace the result with NullLiteral when function finished. It leaves to be realized.
    // Functions in this set is defined in `gensrc/script/starrocks_builtins_functions.py`,
    // and will be built automatically.

    // Date functions:
    public static final String CONVERT_TZ = "convert_tz";
    public static final String CURDATE = "curdate";
    public static final String CURRENT_DATE = "current_date";
    public static final String CURRENT_TIMESTAMP = "current_timestamp";
    public static final String CURTIME = "curtime";
    public static final String CURRENT_TIME = "current_time";
    public static final String DATEDIFF = "datediff";
    public static final String DATE_ADD = "date_add";
    public static final String DATE_FORMAT = "date_format";
    public static final String DATE_SUB = "date_sub";
    public static final String DATE_TRUNC = "date_trunc";
    public static final String DAY = "day";
    public static final String DAYNAME = "dayname";
    public static final String DAYOFMONTH = "dayofmonth";
    public static final String DAYOFWEEK = "dayofweek";
    public static final String DAYOFYEAR = "dayofyear";
    public static final String FROM_DAYS = "from_days";
    public static final String FROM_UNIXTIME = "from_unixtime";
    public static final String FROM_UNIXTIME_MS = "from_unixtime_ms";
    public static final String HOUR = "hour";
    public static final String HOUR_FROM_UNIXTIME = "hour_from_unixtime";
    public static final String MINUTE = "minute";
    public static final String MONTH = "month";
    public static final String MONTHNAME = "monthname";
    public static final String NOW = "now";
    public static final String SECOND = "second";
    public static final String STR_TO_DATE = "str_to_date";
    public static final String TIMEDIFF = "timediff";
    public static final String TIMESTAMPADD = "timestampadd";
    public static final String TIMESTAMPDIFF = "timestampdiff";
    public static final String TO_DATE = "to_date";
    public static final String DATE = "date";
    public static final String LAST_DAY = "last_day";
    public static final String MAKEDATE = "makedate";
    public static final String NEXT_DAY = "next_day";
    public static final String PREVIOUS_DAY = "previous_day";
    public static final String TO_TERA_DATE = "to_tera_date";
    public static final String TO_DAYS = "to_days";
    public static final String UNIX_TIMESTAMP = "unix_timestamp";
    public static final String UTC_TIMESTAMP = "utc_timestamp";
    public static final String UTC_TIME = "utc_time";
    public static final String LOCALTIME = "localtime";
    public static final String LOCALTIMESTAMP = "localtimestamp";

    public static final String WEEK = "week";
    public static final String WEEKOFYEAR = "weekofyear";
    public static final String YEAR = "year";
    public static final String MINUTES_DIFF = "minutes_diff";
    public static final String HOURS_DIFF = "hours_diff";
    public static final String DAYS_DIFF = "days_diff";
    public static final String MONTHS_DIFF = "months_diff";
    public static final String SECONDS_DIFF = "seconds_diff";
    public static final String WEEKS_DIFF = "weeks_diff";
    public static final String YEARS_DIFF = "years_diff";
    public static final String QUARTER = "quarter";
    public static final String TIMESTAMP = "timestamp";
    public static final String TIME_TO_SEC = "time_to_sec";
    public static final String STR2DATE = "str2date";
    public static final String MICROSECONDS_ADD = "microseconds_add";
    public static final String MICROSECONDS_SUB = "microseconds_sub";
    public static final String YEARS_ADD = "years_add";
    public static final String YEARS_SUB = "years_sub";
    public static final String MONTHS_ADD = "months_add";
    public static final String MONTHS_SUB = "months_sub";
    public static final String ADD_MONTHS = "add_months";
    public static final String DAYS_ADD = "days_add";
    public static final String DAYS_SUB = "days_sub";
    public static final String ADDDATE = "adddate";
    public static final String SUBDATE = "subdate";
    public static final String TIME_SLICE = "time_slice";
    public static final String DATE_SLICE = "date_slice";
    public static final String DATE_FLOOR = "date_floor";
    public static final String STRFTIME = "strftime";
    public static final String TIME_FORMAT = "time_format";
    public static final String ALIGNMENT_TIMESTAMP = "alignment_timestamp";
    public static final String SUBSTITUTE = "substitute";

    // Encryption functions:
    public static final String AES_DECRYPT = "aes_decrypt";
    public static final String AES_ENCRYPT = "aes_encrypt";
    public static final String FROM_BASE64 = "from_base64";
    public static final String TO_BASE64 = "to_base64";
    public static final String MD5 = "md5";
    public static final String MD5_SUM = "md5sum";
    public static final String MD5_SUM_NUMERIC = "md5sum_numeric";
    public static final String SHA2 = "sha2";
    public static final String SM3 = "sm3";

    // Vector Index functions:
    public static final String APPROX_COSINE_SIMILARITY = "approx_cosine_similarity";
    public static final String APPROX_L2_DISTANCE = "approx_l2_distance";

    // Geo functions:
    public static final String ST_ASTEXT = "st_astext";
    public static final String ST_ASWKT = "st_aswkt";
    public static final String ST_CIRCLE = "st_circle";
    public static final String ST_CONTAINS = "st_contains";
    public static final String ST_DISTANCE_SPHERE = "st_distance_sphere";
    public static final String ST_GEOMETRYFROMTEXT = "st_geometryfromtext";
    public static final String ST_GEOMFROMTEXT = "st_geomfromtext";
    public static final String ST_LINEFROMTEXT = "st_linefromtext";
    public static final String ST_LINESTRINGFROMTEXT = "st_linestringfromtext";
    public static final String ST_POINT = "st_point";
    public static final String ST_POLYGON = "st_polygon";
    public static final String ST_POLYFROMTEXT = "st_polyfromtext";
    public static final String ST_POLYGONFROMTEXT = "st_polygonfromtext";
    public static final String ST_X = "st_x";
    public static final String ST_Y = "st_y";

    // String functions
    public static final String APPEND_TRAILING_CHAR_IF_ABSENT = "append_trailing_char_if_absent";
    public static final String ASCII = "ascii";
    public static final String CHAR_LENGTH = "char_length";
    public static final String CONCAT = "concat";
    public static final String CONCAT_WS = "concat_ws";
    public static final String ENDS_WITH = "ends_with";
    public static final String FIND_IN_SET = "find_in_set";
    public static final String GROUP_CONCAT = "group_concat";
    public static final String INSTR = "instr";
    public static final String LCASE = "lcase";
    public static final String LEFT = "left";
    public static final String LENGTH = "length";
    public static final String LOCATE = "locate";
    public static final String LOWER = "lower";
    public static final String LPAD = "lpad";
    public static final String LTRIM = "ltrim";
    public static final String RTRIM = "rtrim";
    public static final String MONEY_FORMAT = "money_format";
    public static final String NULL_OR_EMPTY = "null_or_empty";
    public static final String REGEXP_EXTRACT = "regexp_extract";
    public static final String REGEXP_REPLACE = "regexp_replace";
    public static final String REPEAT = "repeat";
    public static final String REPLACE = "replace";
    public static final String REVERSE = "reverse";
    public static final String RIGHT = "right";
    public static final String RPAD = "rpad";
    public static final String SPLIT = "split";
    public static final String SPLIT_PART = "split_part";
    public static final String STR_TO_MAP = "str_to_map";
    public static final String STARTS_WITH = "starts_with";
    public static final String STRLEFT = "strleft";
    public static final String STRRIGHT = "strright";
    public static final String HEX = "hex";
    public static final String UNHEX = "unhex";
    public static final String SUBSTR = "substr";
    public static final String SUBSTRING = "substring";
    public static final String SPACE = "space";
    public static final String PARSE_URL = "parse_url";
    public static final String TRIM = "trim";
    public static final String UPPER = "upper";
    public static final String SUBSTRING_INDEX = "substring_index";
    public static final String FIELD = "field";

    // Json functions:
    public static final String JSON_ARRAY = "json_array";
    public static final String JSON_OBJECT = "json_object";
    public static final String PARSE_JSON = "parse_json";
    public static final String JSON_QUERY = "json_query";
    public static final String JSON_EXISTS = "json_exists";
    public static final String JSON_EACH = "json_each";
    public static final String GET_JSON_BOOL = "get_json_bool";
    public static final String GET_JSON_DOUBLE = "get_json_double";
    public static final String GET_JSON_INT = "get_json_int";
    public static final String GET_JSON_STRING = "get_json_string";
    public static final String GET_JSON_OBJECT = "get_json_object";
    public static final String JSON_LENGTH = "json_length";

    // Matching functions:
    public static final String ILIKE = "ilike";
    public static final String LIKE = "like";
    public static final String REGEXP = "regexp";

    // Utility functions:
    public static final String CURRENT_VERSION = "current_version";
    public static final String LAST_QUERY_ID = "last_query_id";
    public static final String UUID = "uuid";
    public static final String UUID_NUMERIC = "uuid_numeric";
    public static final String SLEEP = "sleep";
    public static final String ISNULL = "isnull";
    public static final String ISNOTNULL = "isnotnull";
    public static final String ASSERT_TRUE = "assert_true";
    public static final String HOST_NAME = "host_name";
    // Aggregate functions:
    public static final String APPROX_COUNT_DISTINCT = "approx_count_distinct";
    public static final String APPROX_COUNT_DISTINCT_HLL_SKETCH = "approx_count_distinct_hll_sketch";
    public static final String DS_HLL_COUNT_DISTINCT = "ds_hll_count_distinct";
    public static final String DS_THETA_COUNT_DISTINCT = "ds_theta_count_distinct";
    public static final String APPROX_TOP_K = "approx_top_k";
    public static final String AVG = "avg";
    public static final String COUNT = "count";
    public static final String COUNT_IF = "count_if";
    public static final String HLL_UNION_AGG = "hll_union_agg";
    public static final String MAX = "max";
    public static final String MAX_BY = "max_by";
    public static final String MAX_BY_V2 = "max_by_v2";
    public static final String MIN_BY = "min_by";
    public static final String MIN_BY_V2 = "min_by_v2";
    public static final String MIN = "min";
    public static final String PERCENTILE_APPROX = "percentile_approx";
    public static final String PERCENTILE_APPROX_WEIGHTED = "percentile_approx_weighted";
    public static final String PERCENTILE_CONT = "percentile_cont";
    public static final String PERCENTILE_DISC = "percentile_disc";
    public static final String LC_PERCENTILE_DISC = "percentile_disc_lc";
    public static final String RETENTION = "retention";
    public static final String STDDEV = "stddev";
    public static final String STDDEV_POP = "stddev_pop";
    public static final String STDDEV_SAMP = "stddev_samp";
    public static final String SUM = "sum";
    public static final String VARIANCE = "variance";
    public static final String VAR_POP = "var_pop";
    public static final String VARIANCE_POP = "variance_pop";
    public static final String VAR_SAMP = "var_samp";
    public static final String VARIANCE_SAMP = "variance_samp";
    public static final String COVAR_POP = "covar_pop";
    public static final String COVAR_SAMP = "covar_samp";
    public static final String CORR = "corr";
    public static final String ANY_VALUE = "any_value";
    public static final String STD = "std";
    public static final String HLL_UNION = "hll_union";
    public static final String HLL_RAW_AGG = "hll_raw_agg";
    public static final String HLL_RAW = "hll_raw";
    public static final String HLL_EMPTY = "hll_empty";
    public static final String NDV = "ndv";
    public static final String MULTI_DISTINCT_COUNT = "multi_distinct_count";
    public static final String MULTI_DISTINCT_SUM = "multi_distinct_sum";
    public static final String DICT_MERGE = "dict_merge";
    public static final String WINDOW_FUNNEL = "window_funnel";
    public static final String DISTINCT_PC = "distinct_pc";
    public static final String DISTINCT_PCSA = "distinct_pcsa";
    public static final String HISTOGRAM = "histogram";
    public static final String FLAT_JSON_META = "flat_json_meta";
    public static final String MANN_WHITNEY_U_TEST = "mann_whitney_u_test";

    // Bitmap functions:
    public static final String BITMAP_AND = "bitmap_and";
    public static final String BITMAP_ANDNOT = "bitmap_andnot";
    public static final String BITMAP_CONTAINS = "bitmap_contains";
    public static final String BITMAP_EMPTY = "bitmap_empty";
    public static final String BITMAP_FROM_STRING = "bitmap_from_string";
    public static final String BITMAP_HASH = "bitmap_hash";
    public static final String BITMAP_HASH64 = "bitmap_hash64";
    public static final String BITMAP_HAS_ANY = "bitmap_has_any";
    public static final String BITMAP_INTERSECT = "bitmap_intersect";
    public static final String BITMAP_MAX = "bitmap_max";
    public static final String BITMAP_MIN = "bitmap_min";
    public static final String BITMAP_OR = "bitmap_or";
    public static final String BITMAP_REMOVE = "bitmap_remove";
    public static final String BITMAP_TO_STRING = "bitmap_to_string";
    public static final String BITMAP_TO_ARRAY = "bitmap_to_array";
    public static final String BITMAP_UNION = "bitmap_union";
    public static final String BITMAP_AGG = "bitmap_agg";
    public static final String BITMAP_XOR = "bitmap_xor";
    public static final String TO_BITMAP = "to_bitmap";
    public static final String BITMAP_COUNT = "bitmap_count";
    public static final String BITMAP_UNION_COUNT = "bitmap_union_count";
    public static final String BITMAP_UNION_INT = "bitmap_union_int";
    public static final String INTERSECT_COUNT = "intersect_count";
    public static final String BITMAP_DICT = "bitmap_dict";
    public static final String BITMAP_FROM_BINARY = "bitmap_from_binary";
    public static final String EXCHANGE_BYTES = "exchange_bytes";
    public static final String EXCHANGE_SPEED = "exchange_speed";
    // Array functions:
    public static final String ARRAY_AGG = "array_agg";
    public static final String ARRAY_AGG_DISTINCT = "array_agg_distinct";
    public static final String ARRAY_UNIQUE_AGG = "array_unique_agg";
    public static final String ARRAY_CONCAT = "array_concat";
    public static final String ARRAY_DIFFERENCE = "array_difference";
    public static final String ARRAY_INTERSECT = "array_intersect";
    public static final String ARRAY_SLICE = "array_slice";
    public static final String ARRAYS_OVERLAP = "arrays_overlap";
    public static final String ARRAY_APPEND = "array_append";
    public static final String ARRAY_AVG = "array_avg";
    public static final String ARRAY_CONTAINS = "array_contains";
    public static final String ARRAY_CONTAINS_ALL = "array_contains_all";
    public static final String ARRAY_CONTAINS_SEQ = "array_contains_seq";
    public static final String ARRAY_CUM_SUM = "array_cum_sum";

    public static final String ARRAY_JOIN = "array_join";
    public static final String ARRAY_DISTINCT = "array_distinct";
    public static final String ARRAY_LENGTH = "array_length";
    public static final String ARRAY_MAX = "array_max";
    public static final String ARRAY_MIN = "array_min";
    public static final String ARRAY_POSITION = "array_position";
    public static final String ARRAY_SORT = "array_sort";
    public static final String ARRAY_SUM = "array_sum";
    public static final String ARRAY_REMOVE = "array_remove";
    public static final String ARRAY_FILTER = "array_filter";
    public static final String ARRAY_SORTBY = "array_sortby";
    public static final String ANY_MATCH = "any_match";
    public static final String ALL_MATCH = "all_match";

    public static final String ARRAY_GENERATE = "array_generate";

    public static final String ARRAY_TO_BITMAP = "array_to_bitmap";
    public static final String ARRAY_FLATTEN = "array_flatten";

    // Bit functions:
    public static final String BITAND = "bitand";
    public static final String BITNOT = "bitnot";
    public static final String BITOR = "bitor";
    public static final String BITXOR = "bitxor";
    public static final String BIT_SHIFT_LEFT = "bit_shift_left";
    public static final String BIT_SHIFT_RIGHT = "bit_shift_right";
    public static final String BIT_SHIFT_RIGHT_LOGICAL = "bit_shift_right_logical";

    // Hash functions:
    public static final String MURMUR_HASH3_32 = "murmur_hash3_32";
    public static final String CRC32_HASH = "crc32_hash";

    // Percentile functions:
    public static final String PERCENTILE_APPROX_RAW = "percentile_approx_raw";
    public static final String PERCENTILE_EMPTY = "percentile_empty";
    public static final String PERCENTILE_HASH = "percentile_hash";
    public static final String PERCENTILE_UNION = "percentile_union";

    // Condition functions:
    public static final String COALESCE = "coalesce";
    public static final String IF = "if";
    public static final String IFNULL = "ifnull";
    public static final String NULLIF = "nullif";

    // Math functions:
    public static final String ABS = "abs";
    public static final String ACOS = "acos";
    public static final String ADD = "add";
    public static final String ASIN = "asin";
    public static final String ATAN = "atan";
    public static final String ATAN2 = "atan2";
    public static final String BIN = "bin";
    public static final String CEIL = "ceil";
    public static final String DCEIL = "dceil";
    public static final String CEILING = "ceiling";
    public static final String CONV = "conv";
    public static final String COS = "cos";
    public static final String COT = "cot";
    public static final String DEGRESS = "degress";
    public static final String DIVIDE = "divide";
    public static final String E = "e";
    public static final String EXP = "exp";
    public static final String DEXP = "dexp";
    public static final String FLOOR = "floor";
    public static final String DFLOOR = "dfloor";
    public static final String FMOD = "fmod";
    public static final String GREATEST = "greatest";
    public static final String LEAST = "least";
    public static final String LN = "ln";
    public static final String DLOG1 = "dlog1";
    public static final String LOG = "log";
    public static final String LOG2 = "log2";
    public static final String LOG10 = "log10";
    public static final String DLOG10 = "dlog10";
    public static final String MOD = "mod";
    public static final String MULTIPLY = "multiply";
    public static final String NEGATIVE = "negative";
    public static final String PI = "pi";
    public static final String PMOD = "pmod";
    public static final String POSITIVE = "positive";
    public static final String POW = "pow";
    public static final String POWER = "power";
    public static final String DPOW = "dpow";
    public static final String FPOW = "fpow";
    public static final String RADIANS = "radians";
    public static final String RAND = "rand";
    public static final String RANDOM = "random";
    public static final String ROUND = "round";
    public static final String DROUND = "dround";
    public static final String SIGN = "sign";
    public static final String SIN = "sin";
    public static final String SQRT = "sqrt";
    public static final String SUBTRACT = "subtract";
    public static final String DSQRT = "dsqrt";
    public static final String SQUARE = "square";
    public static final String TAN = "tan";
    public static final String TRUNCATE = "truncate";
    public static final String ICEBERG_TRANSFORM_TRUNCATE = "__iceberg_transform_truncate";
    public static final String ICEBERG_TRANSFORM_BUCKET = "__iceberg_transform_bucket";

    // Window functions:
    public static final String LEAD = "lead";
    public static final String LAG = "lag";
    public static final String FIRST_VALUE = "first_value";
    public static final String FIRST_VALUE_REWRITE = "first_value_rewrite";
    public static final String LAST_VALUE = "last_value";
    public static final String DENSE_RANK = "dense_rank";
    public static final String RANK = "rank";
    public static final String CUME_DIST = "cume_dist";
    public static final String PERCENT_RANK = "percent_rank";
    public static final String NTILE = "ntile";
    public static final String ROW_NUMBER = "row_number";
    public static final String SESSION_NUMBER = "session_number";

    // Other functions:
    public static final String HLL_HASH = "hll_hash";
    public static final String HLL_CARDINALITY = "hll_cardinality";
    public static final String DEFAULT_VALUE = "default_value";
    public static final String REPLACE_VALUE = "replace_value";

    // high-order functions related lambda functions
    public static final String ARRAY_MAP = "array_map";
    public static final String TRANSFORM = "transform";

    // map functions:
    public static final String MAP = "map";
    public static final String MAP_APPLY = "map_apply";
    public static final String MAP_FILTER = "map_filter";

    public static final String DISTINCT_MAP_KEYS = "distinct_map_keys";
    public static final String MAP_VALUES = "map_values";

    public static final String MAP_CONCAT = "map_concat";

    public static final String MAP_FROM_ARRAYS = "map_from_arrays";
    public static final String MAP_KEYS = "map_keys";
    public static final String MAP_SIZE = "map_size";

    public static final String MAP_AGG = "map_agg";

    public static final String TRANSFORM_VALUES = "transform_values";
    public static final String TRANSFORM_KEYS = "transform_keys";

    public static final String ELEMENT_AT = "element_at";

    public static final String CARDINALITY = "cardinality";
    // Struct functions:
    public static final String ROW = "row";
    public static final String STRUCT = "struct";
    public static final String NAMED_STRUCT = "named_struct";

    public static final String NGRAM_SEARCH = "ngram_search";
    public static final String NGRAM_SEARCH_CASE_INSENSITIVE = "ngram_search_case_insensitive";


    // JSON functions
    public static final Function JSON_QUERY_FUNC = new Function(
            new FunctionName(JSON_QUERY), new Type[] {Type.JSON, Type.VARCHAR}, Type.JSON, false);

    // dict query function
    public static final String DICT_MAPPING = "dict_mapping";

    //user and role function
    public static final String IS_ROLE_IN_SESSION = "is_role_in_session";

    public static final String QUARTERS_ADD = "quarters_add";
    public static final String QUARTERS_SUB = "quarters_sub";
    public static final String WEEKS_ADD = "weeks_add";
    public static final String WEEKS_SUB = "weeks_sub";
    public static final String HOURS_ADD = "hours_add";
    public static final String HOURS_SUB = "hours_sub";
    public static final String MINUTES_ADD = "minutes_add";
    public static final String MINUTES_SUB = "minutes_sub";
    public static final String SECONDS_ADD = "seconds_add";
    public static final String SECONDS_SUB = "seconds_sub";
    public static final String MILLISECONDS_ADD = "milliseconds_add";
    public static final String MILLISECONDS_SUB = "milliseconds_sub";

    // table function
    public static final String UNNEST = "unnest";
    public static final String UNNEST_BITMAP = "unnest_bitmap";

    public static final String CONNECTION_ID = "connection_id";
    public static final String SESSION_ID = "session_id";

    public static final String CATALOG = "catalog";

    public static final String DATABASE = "database";

    public static final String SCHEMA = "schema";

    public static final String USER = "user";

    public static final String SESSION_USER = "session_user";

    public static final String CURRENT_USER = "current_user";
    public static final String CURRENT_ROLE = "current_role";
    public static final String CURRENT_GROUP = "current_group";

    public static final String AGG_STATE_SUFFIX = "_state";
    public static final String AGG_STATE_UNION_SUFFIX = "_union";
    public static final String AGG_STATE_MERGE_SUFFIX = "_merge";
    public static final String AGG_STATE_IF_SUFFIX = "_if";

    public static final String BOOL_OR = "bool_or";
    public static final String BOOLOR_AGG = "boolor_agg";

    private static final Logger LOGGER = LogManager.getLogger(FunctionSet.class);

    private static final Set<Type> STDDEV_ARG_TYPE =
            ImmutableSet.<Type>builder()
                    .addAll(Type.FLOAT_TYPES)
                    .addAll(Type.INTEGER_TYPES)
                    .build();

    private static final Set<Type> HISTOGRAM_TYPE =
            ImmutableSet.<Type>builder()
                    .addAll(Type.FLOAT_TYPES)
                    .addAll(Type.INTEGER_TYPES)
                    .addAll(Type.DECIMAL_TYPES)
                    .addAll(Type.STRING_TYPES)
                    .addAll(Type.DATE_TYPES)
                    .build();

    private static final Set<Type> MULTI_DISTINCT_COUNT_TYPES =
            ImmutableSet.<Type>builder()
                    .addAll(Type.INTEGER_TYPES)
                    .addAll(Type.FLOAT_TYPES)
                    .addAll(Type.DECIMAL_TYPES)
                    .addAll(Type.STRING_TYPES)
                    .add(Type.DATE)
                    .add(Type.DATETIME)
                    .add(Type.DECIMALV2)
                    .build();

    private static final Set<Type> SORTABLE_TYPES =
            ImmutableSet.<Type>builder()
                    .addAll(Type.INTEGER_TYPES)
                    .addAll(Type.DECIMAL_TYPES)
                    .addAll(Type.STRING_TYPES)
                    .add(Type.DATE)
                    .add(Type.DATETIME)
                    .add(Type.DECIMALV2)
                    .addAll(Type.FLOAT_TYPES)
                    .build();

    public static final Set<Type> BITMAP_AGG_TYPE =
            ImmutableSet.<Type>builder()
                    .addAll(Type.INTEGER_TYPES)
                    .build();
    /**
     * Use for vectorized engine, but we can't use vectorized function directly, because we
     * need to check whether the expression tree can use vectorized function from bottom to
     * top, we must to re-analyze function_call_expr when vectorized function is can't used
     * if we choose to use the vectorized function here. So... we need bind vectorized function
     * to row function when init.
     */
    private final Map<String, List<Function>> vectorizedFunctions;

    // This contains the nullable functions, which cannot return NULL result directly for the NULL parameter.
    // This does not contain any user defined functions. All UDFs handle null values by themselves.
    private final ImmutableSet<String> notAlwaysNullResultWithNullParamFunctions =
            ImmutableSet.of(IF, CONCAT_WS, IFNULL, NULLIF, NULL_OR_EMPTY, COALESCE, BITMAP_HASH, BITMAP_HASH64,
                    PERCENTILE_HASH, HLL_HASH, JSON_ARRAY, JSON_OBJECT, ROW, STRUCT, NAMED_STRUCT);

    // If low cardinality string column with global dict, for some string functions,
    // we could evaluate the function only with the dict content, not all string column data.
    public final ImmutableSet<String> couldApplyDictOptimizationFunctions =
            ImmutableSet.of(APPEND_TRAILING_CHAR_IF_ABSENT, CONCAT, CONCAT_WS, HEX, LEFT, LIKE, LOWER, LPAD, LTRIM,
                    REGEXP_EXTRACT, REGEXP_REPLACE, REPEAT, REPLACE, REVERSE, RIGHT, RPAD, RTRIM, SPLIT_PART, SUBSTR,
                    SUBSTRING, SUBSTRING_INDEX,
                    TRIM, UPPER, IF);

    public static final Set<String> alwaysReturnNonNullableFunctions =
            ImmutableSet.<String>builder()
                    .add(FunctionSet.COUNT)
                    .add(FunctionSet.COUNT_IF)
                    .add(FunctionSet.MULTI_DISTINCT_COUNT)
                    .add(FunctionSet.NULL_OR_EMPTY)
                    .add(FunctionSet.HLL_HASH)
                    .add(FunctionSet.HLL_UNION_AGG)
                    .add(FunctionSet.NDV)
                    .add(FunctionSet.APPROX_COUNT_DISTINCT)
                    .add(FunctionSet.APPROX_TOP_K)
                    .add(FunctionSet.BITMAP_UNION_INT)
                    .add(FunctionSet.BITMAP_UNION_COUNT)
                    .add(FunctionSet.BITMAP_COUNT)
                    .add(FunctionSet.CURDATE)
                    .add(FunctionSet.CURRENT_TIMESTAMP)
                    .add(FunctionSet.CURRENT_TIME)
                    .add(FunctionSet.NOW)
                    .add(FunctionSet.UTC_TIMESTAMP)
                    .add(FunctionSet.MD5_SUM)
                    .add(FunctionSet.DS_HLL_COUNT_DISTINCT)
                    .add(FunctionSet.DS_THETA_COUNT_DISTINCT)
                    .add(FunctionSet.MD5_SUM_NUMERIC)
                    .add(FunctionSet.BITMAP_EMPTY)
                    .add(FunctionSet.HLL_EMPTY)
                    .add(FunctionSet.EXCHANGE_BYTES)
                    .add(FunctionSet.EXCHANGE_SPEED)
                    .add(FunctionSet.FIELD)
                    .add(FunctionSet.SPLIT_PART)
                    .build();

    public static final Set<String> DECIMAL_ROUND_FUNCTIONS =
            ImmutableSet.<String>builder()
                    .add(TRUNCATE)
                    .add(ROUND)
                    .add(DROUND)
                    .build();

    public static final Set<String> nonDeterministicFunctions =
            ImmutableSet.<String>builder()
                    .add(RAND)
                    .add(RANDOM)
                    .add(UUID)
                    .add(SLEEP)
                    .build();

    public static final Set<String> VECTOR_COMPUTE_FUNCTIONS =
            ImmutableSet.<String>builder()
                    .add(APPROX_COSINE_SIMILARITY)
                    .add(APPROX_L2_DISTANCE)
                    .build();

    // Only use query cache if these time function can be reduced into a constant
    // date/datetime value after applying FoldConstantRule, otherwise BE would yield
    // non-deterministic result when these function is delivered to BE.
    public static final Set<String> nonDeterministicTimeFunctions =
            ImmutableSet.<String>builder()
                    .add(CURTIME)
                    .add(CURDATE)
                    .add(CURRENT_DATE)
                    .add(CURRENT_TIMESTAMP)
                    .add(CURRENT_TIME)
                    .add(NOW)
                    .add(UNIX_TIMESTAMP)
                    .add(UTC_TIMESTAMP)
                    .add(UTC_TIME)
                    .add(LOCALTIME)
                    .add(LOCALTIMESTAMP)
                    .add()
                    .build();

    // Contains all non-deterministic functions both time and non-time functions.
    public static final Set<String> allNonDeterministicFunctions = ImmutableSet.<String>builder()
            .addAll(nonDeterministicFunctions)
            .addAll(nonDeterministicTimeFunctions)
            .build();

    public static final Set<String> onlyAnalyticUsedFunctions = ImmutableSet.<String>builder()
            .add(FunctionSet.LEAD)
            .add(FunctionSet.LAG)
            .add(FunctionSet.DENSE_RANK)
            .add(FunctionSet.RANK)
            .add(FunctionSet.CUME_DIST)
            .add(FunctionSet.PERCENT_RANK)
            .add(FunctionSet.NTILE)
            .add(FunctionSet.ROW_NUMBER)
            .add(FunctionSet.FIRST_VALUE)
            .add(FunctionSet.LAST_VALUE)
            .add(FunctionSet.FIRST_VALUE_REWRITE)
            .add(FunctionSet.SESSION_NUMBER)
            .build();

    public static final Set<String> VARIANCE_FUNCTIONS = ImmutableSet.<String>builder()
            .add(FunctionSet.VAR_POP)
            .add(FunctionSet.VAR_SAMP)
            .add(FunctionSet.VARIANCE)
            .add(FunctionSet.VARIANCE_POP)
            .add(FunctionSet.VARIANCE_SAMP)
            .add(FunctionSet.STD)
            .add(FunctionSet.STDDEV)
            .add(FunctionSet.STDDEV_POP)
            .add(FunctionSet.STDDEV_SAMP).build();

    public static final Set<String> STATISTIC_FUNCTIONS = ImmutableSet.<String>builder()
            .add(FunctionSet.VAR_POP)
            .add(FunctionSet.VAR_SAMP)
            .add(FunctionSet.VARIANCE)
            .add(FunctionSet.VARIANCE_POP)
            .add(FunctionSet.VARIANCE_SAMP)
            .add(FunctionSet.STD)
            .add(FunctionSet.STDDEV)
            .add(FunctionSet.STDDEV_POP)
            .add(FunctionSet.STDDEV_SAMP)
            .add(FunctionSet.COVAR_POP)
            .add(FunctionSet.COVAR_SAMP)
            .add(FunctionSet.CORR)
            .build();

    public static final Set<String> IGNORE_NULL_WINDOW_FUNCTION =
            ImmutableSet.<String>builder().add(FunctionSet.FIRST_VALUE)
                    .add(FunctionSet.LAST_VALUE)
                    .add(FunctionSet.FIRST_VALUE_REWRITE)
                    .add(FunctionSet.LEAD)
                    .add(FunctionSet.LAG)
                    .build();

    public static final List<String> ARRAY_DECIMAL_FUNCTIONS = ImmutableList.<String>builder()
            .add(ARRAY_SUM)
            .add(ARRAY_AVG)
            .add(ARRAY_MIN)
            .add(ARRAY_MAX)
            .add(ARRAY_DISTINCT)
            .add(ARRAY_SORT)
            .add(REVERSE)
            .add(ARRAY_INTERSECT)
            .add(ARRAY_DIFFERENCE)
            .add(ARRAYS_OVERLAP)
            .add(ARRAY_AGG)
            .add(ARRAY_CONCAT)
            .add(ARRAY_SLICE)
            .add(ARRAY_CONTAINS)
            .add(ARRAY_CONTAINS_ALL)
            .add(ARRAY_CONTAINS_SEQ)
            .add(ARRAY_POSITION)
            .build();

    public static final Set<String> INFORMATION_FUNCTIONS = ImmutableSet.<String>builder()
            .add(CONNECTION_ID)
            .add(SESSION_ID)
            .add(CATALOG)
            .add(DATABASE)
            .add(SCHEMA)
            .add(USER)
            .add(SESSION_USER)
            .add(CURRENT_USER)
            .add(CURRENT_ROLE)
            .add(CURRENT_GROUP)
            .build();

    public static final java.util.function.Function<Type, ArrayType> APPROX_TOP_N_RET_TYPE_BUILDER =
            (Type itemType) -> {
                List<StructField> fields = Lists.newArrayList();
                fields.add(new StructField("item", itemType));
                fields.add(new StructField("count", Type.BIGINT));
                return new ArrayType(new StructType(fields, true));
            };

    public static final Set<String> INDEX_ONLY_FUNCTIONS =
            ImmutableSet.<String>builder().add().add(NGRAM_SEARCH).add(NGRAM_SEARCH_CASE_INSENSITIVE).build();

    // Unsupported functions for agg state combinator.
    public static final Set<String> UNSUPPORTED_AGG_STATE_FUNCTIONS =
            new ImmutableSortedSet.Builder<>(String.CASE_INSENSITIVE_ORDER)
                    // TODO: Add unsupported functions here.
                    .add(GROUP_CONCAT) // Unsupported function
                    // UNSUPPORTED functions
                    .add(COUNT_IF) // count_if is a syntax sugar in fe
                    .add(HLL_RAW) // hll_raw use `hll` as input, use existed `hll_union` instead
                    // Internal functions are not supported.
                    .add(FLAT_JSON_META)
                    .add(EXCHANGE_BYTES)
                    .add(EXCHANGE_SPEED)
                    .add(FIRST_VALUE_REWRITE)
                    .add(HISTOGRAM)
                    .add(DICT_MERGE)
                    // Functions with constant contexts in be are not supported.
                    .add(WINDOW_FUNNEL)
                    .add(APPROX_TOP_K)
                    .add(INTERSECT_COUNT)
                    .add(LC_PERCENTILE_DISC)
                    .add(MAP_AGG)
                    .build();

    public static final Set<String> RANK_RALATED_FUNCTIONS =
            ImmutableSet.<String>builder().add().add(ROW_NUMBER).add(RANK).add(DENSE_RANK).build();

    public FunctionSet() {
        vectorizedFunctions = Maps.newHashMap();
    }

    /**
     * There are essential differences in the implementation of some functions for different
     * types params, which should be prohibited.
     *
     * @param desc
     * @param candicate
     * @return
     */
    public static boolean isCastMatchAllowed(Function desc, Function candicate) {
        final String functionName = desc.getFunctionName().getFunction();
        final Type[] descArgTypes = desc.getArgs();
        final Type[] candidateArgTypes = candicate.getArgs();
        if (functionName.equalsIgnoreCase(HEX)
                || functionName.equalsIgnoreCase(LEAD)
                || functionName.equalsIgnoreCase(LAG)
                || functionName.equalsIgnoreCase(APPROX_TOP_K)) {
            final ScalarType descArgType = (ScalarType) descArgTypes[0];
            final ScalarType candidateArgType = (ScalarType) candidateArgTypes[0];
            if (functionName.equalsIgnoreCase(LEAD) ||
                    functionName.equalsIgnoreCase(LAG) ||
                    functionName.equalsIgnoreCase(APPROX_TOP_K)) {
                // lead and lag function respect first arg type
                return descArgType.isNull() || descArgType.matchesType(candidateArgType);
            } else if (descArgType.isOnlyMetricType()) {
                // Bitmap, HLL, PERCENTILE type don't allow cast
                return false;
            } else {
                // The implementations of hex for string and int are different.
                return descArgType.isStringType() || !candidateArgType.isStringType();
            }
        }

        // ifnull, nullif(DATE, DATETIME) should return datetime, not bigint
        // if(boolean, DATE, DATETIME) should return datetime
        // coalesce(DATE, DATETIME) should return datetime
        int arg_index = 0;
        if (functionName.equalsIgnoreCase(IFNULL) ||
                functionName.equalsIgnoreCase(NULLIF) ||
                functionName.equalsIgnoreCase(IF) ||
                functionName.equalsIgnoreCase(COALESCE)) {
            if (functionName.equalsIgnoreCase(IF)) {
                arg_index = 1;
            }
            boolean descIsAllDateType = true;
            for (int i = arg_index; i < descArgTypes.length; ++i) {
                if (!descArgTypes[i].isDateType()) {
                    descIsAllDateType = false;
                    break;
                }
            }
            Type candidateArgType = candidateArgTypes[arg_index];
            if (descIsAllDateType && !candidateArgType.isDateType()) {
                return false;
            }
        }
        return true;
    }

    public void init() {
        ArithmeticExpr.initBuiltins(this);
        TableFunction.initBuiltins(this);
        VectorizedBuiltinFunctions.initBuiltins(this);
        initAggregateBuiltins();
        addBooleanAggregateFunctions();
    }

    public boolean isNotAlwaysNullResultWithNullParamFunctions(String funcName) {
        return notAlwaysNullResultWithNullParamFunctions.contains(funcName)
                || alwaysReturnNonNullableFunctions.contains(funcName);
    }

    private Function matchFuncCandidates(Function desc, Function.CompareMode mode, List<Function> fns) {
        Function fn = matchStrictFunction(desc, mode, fns);
        if (fn != null) {
            return fn;
        }
        return matchCastFunction(desc, mode, fns);
    }

    private Function matchCastFunction(Function desc, Function.CompareMode mode, List<Function> fns) {
        if (fns == null || fns.isEmpty()) {
            return null;
        }

        if (mode.ordinal() < Function.CompareMode.IS_SUPERTYPE_OF.ordinal()) {
            return null;
        }

        // Next check for strict supertypes
        for (Function f : fns) {
            if (f.compare(desc, Function.CompareMode.IS_SUPERTYPE_OF) && isCastMatchAllowed(desc, f)) {
                return f;
            }
        }

        if (mode.ordinal() < Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF.ordinal()) {
            return null;
        }

        // Finally, check for non-strict supertypes
        for (Function f : fns) {
            if (f.compare(desc, Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF) && isCastMatchAllowed(desc, f)) {
                return f;
            }
        }
        return null;
    }

    private Function matchStrictFunction(Function desc, Function.CompareMode mode, List<Function> fns) {
        if (fns == null || fns.isEmpty()) {
            return null;
        }
        // First check for identical
        for (Function f : fns) {
            if (f.compare(desc, Function.CompareMode.IS_IDENTICAL)) {
                return f;
            }
        }

        if (mode.ordinal() < Function.CompareMode.IS_INDISTINGUISHABLE.ordinal()) {
            return null;
        }

        // Next check for indistinguishable
        for (Function f : fns) {
            if (f.compare(desc, Function.CompareMode.IS_INDISTINGUISHABLE)) {
                return f;
            }
        }
        return null;
    }

    private Function matchPolymorphicFunction(Function desc, Function.CompareMode mode, List<Function> fns,
                                              List<Function> standFns) {
        Function fn = matchFuncCandidates(desc, mode, fns);
        if (fn != null) {
            fn = PolymorphicFunctionAnalyzer.generatePolymorphicFunction(fn, desc.getArgs());
        }
        if (fn != null) {
            Function newFn = matchStrictFunction(fn, mode, standFns);
            if (newFn != null) {
                return newFn;
            }
            // check generate function is right
            return matchFuncCandidates(desc, mode, Collections.singletonList(fn));
        }
        return null;
    }

    public Function getFunction(Function desc, Function.CompareMode mode) {
        List<Function> fns = vectorizedFunctions.get(desc.functionName());
        if (desc.hasNamedArg() && fns != null && !fns.isEmpty()) {
            fns = fns.stream().filter(Function::hasNamedArg).collect(Collectors.toList());
        }
        if (fns == null || fns.isEmpty()) {
            return null;
        }

        Function func;
        // To be back-compatible, we first choose the functions from the non-polymorphic functions, if we can't find
        // a suitable in non-polymorphic functions. We will try to search in the polymorphic functions.
        List<Function> standFns = fns.stream().filter(fn -> !fn.isPolymorphic()).collect(Collectors.toList());
        func = matchStrictFunction(desc, mode, standFns);
        if (func != null) {
            return func;
        }

        List<Function> polyFns = fns.stream().filter(Function::isPolymorphic).collect(Collectors.toList());
        func = matchPolymorphicFunction(desc, mode, polyFns, standFns);
        if (func != null) {
            return func;
        }

        return matchCastFunction(desc, mode, standFns);
    }

    private void addBuiltInFunction(Function fn) {
        Preconditions.checkArgument(!fn.getReturnType().isPseudoType() || fn.isPolymorphic(), fn.toString());
        if (!fn.isPolymorphic() && getFunction(fn, Function.CompareMode.IS_INDISTINGUISHABLE) != null) {
            return;
        }
        fn.setIsNullable(!alwaysReturnNonNullableFunctions.contains(fn.functionName()));
        List<Function> fns = vectorizedFunctions.computeIfAbsent(fn.functionName(), k -> Lists.newArrayList());
        fns.add(fn);
    }

    // for vectorized engine
    public void addVectorizedScalarBuiltin(long fid, String fnName, boolean varArgs,
                                           Type retType, Type... args) {

        List<Type> argsType = Arrays.stream(args).collect(Collectors.toList());
        addVectorizedBuiltin(ScalarFunction.createVectorizedBuiltin(fid, fnName, argsType, varArgs, retType));
    }

    private void addVectorizedBuiltin(Function fn) {
        fn.setCouldApplyDictOptimize(couldApplyDictOptimizationFunctions.contains(fn.functionName()));
        fn.setIsNullable(!alwaysReturnNonNullableFunctions.contains(fn.functionName()));
        List<Function> fns = vectorizedFunctions.computeIfAbsent(fn.functionName(), k -> Lists.newArrayList());
        fns.add(fn);
    }

    /**
     * Adds a builtin scalar function to this database. The function must not already exist.
     */
    public void addBuiltin(Function fn) {
        addBuiltInFunction(fn);
    }

    /**
     * Adds a builtin aggregate function to this database. The function must not already exist.
     */
    public void addBuiltin(AggregateFunction aggFunc) {
        addBuiltInFunction(aggFunc);

        if (AggStateUtils.isSupportedAggStateFunction(aggFunc, false)) {
            // register `_state` combinator
            AggStateCombinator.of(aggFunc).ifPresent(this::addBuiltInFunction);
            // register `_merge`/`_union` combinator for aggregate functions
            AggStateUnionCombinator.of(aggFunc).ifPresent(this::addBuiltInFunction);
            AggStateMergeCombinator.of(aggFunc).ifPresent(this::addBuiltInFunction);
        }

        if (AggStateUtils.isSupportedAggStateFunction(aggFunc, true)) {
            // register `_if` for aggregate functions
            AggStateIf.of(aggFunc).ifPresent(this::addBuiltInFunction);
        }
    }

    public static String getAggStateName(String name) {
        return String.format("%s%s", name, AGG_STATE_SUFFIX);
    }

    public static String getAggStateUnionName(String name) {
        return String.format("%s%s", name, AGG_STATE_UNION_SUFFIX);
    }

    public static String getAggStateMergeName(String name) {
        return String.format("%s%s", name, AGG_STATE_MERGE_SUFFIX);
    }

    public static String getAggStateIfName(String name) {
        return String.format("%s%s", name, AGG_STATE_IF_SUFFIX);
    }

    // Populate all the aggregate builtins in the globalStateMgr.
    // null symbols indicate the function does not need that step of the evaluation.
    // An empty symbol indicates a TODO for the BE to implement the function.
    private void initAggregateBuiltins() {
        // count(*)
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.COUNT,
                new ArrayList<>(), Type.BIGINT, Type.BIGINT, false, true, true));

        // EXCHANGE_BYTES/_SPEED with various arguments
        addBuiltin(AggregateFunction.createBuiltin(EXCHANGE_BYTES,
                Lists.newArrayList(Type.ANY_ELEMENT), Type.BIGINT, Type.BIGINT, true,
                true, false, true));

        addBuiltin(AggregateFunction.createBuiltin(EXCHANGE_SPEED,
                Lists.newArrayList(Type.ANY_ELEMENT), Type.VARCHAR, Type.BIGINT, true,
                true, false, true));

        addBuiltin(AggregateFunction.createBuiltin(ARRAY_AGG,
                Lists.newArrayList(Type.ANY_ELEMENT), Type.ANY_ARRAY, Type.ANY_STRUCT, true,
                true, false, false));

        addBuiltin(AggregateFunction.createBuiltin(GROUP_CONCAT,
                Lists.newArrayList(Type.ANY_ELEMENT), Type.VARCHAR, Type.ANY_STRUCT, true,
                false, false, false));

        for (Type t : Type.getSupportedTypes()) {
            if (t.isFunctionType()) {
                continue;
            }
            if (t.isNull()) {
                continue; // NULL is handled through type promotion.
            }
            if (t.isChar()) {
                continue; // promoted to STRING
            }
            // Count
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.COUNT,
                    Lists.newArrayList(t), Type.BIGINT, Type.BIGINT, false, true, true));

            // ANY_VALUE
            addBuiltin(AggregateFunction.createBuiltin(ANY_VALUE,
                    Lists.newArrayList(t), t, t, true, false, false));

            if (t.isPseudoType()) {
                continue; // Only function `Count` support pseudo types now.
            }

            // Min
            addBuiltin(AggregateFunction.createBuiltin(MIN,
                    Lists.newArrayList(t), t, t, true, true, false));

            // Max
            addBuiltin(AggregateFunction.createBuiltin(MAX,
                    Lists.newArrayList(t), t, t, true, true, false));

            // MAX_BY
            for (Type t1 : Type.getSupportedTypes()) {
                if (t1.isFunctionType() || t1.isNull() || t1.isChar() || t1.isPseudoType()) {
                    continue;
                }
                addBuiltin(AggregateFunction.createBuiltin(
                        MAX_BY, Lists.newArrayList(t1, t), t1, Type.VARBINARY, true, true, false));
            }

            // MIN_BY
            for (Type t1 : Type.getSupportedTypes()) {
                if (t1.isFunctionType() || t1.isNull() || t1.isChar() || t1.isPseudoType()) {
                    continue;
                }
                addBuiltin(AggregateFunction.createBuiltin(
                        MIN_BY, Lists.newArrayList(t1, t), t1, Type.VARBINARY, true, true, false));
            }

            // NDV
            addBuiltin(AggregateFunction.createBuiltin(NDV,
                    Lists.newArrayList(t), Type.BIGINT, Type.VARBINARY,
                    true, false, true));

            // APPROX_COUNT_DISTINCT
            // alias of ndv, compute approx count distinct use HyperLogLog
            addBuiltin(AggregateFunction.createBuiltin(APPROX_COUNT_DISTINCT,
                    Lists.newArrayList(t), Type.BIGINT, Type.VARBINARY,
                    true, false, true));

            // ds_hll_count_distinct(col)
            addBuiltin(AggregateFunction.createBuiltin(DS_HLL_COUNT_DISTINCT,
                    Lists.newArrayList(t), Type.BIGINT, Type.VARBINARY,
                    true, false, true));
            // ds_hll_count_distinct(col, log_k)
            addBuiltin(AggregateFunction.createBuiltin(DS_HLL_COUNT_DISTINCT,
                    Lists.newArrayList(t, Type.INT), Type.BIGINT, Type.VARBINARY,
                    true, false, true));
            // ds_hll_count_distinct(col, log_k, tgt_type)
            addBuiltin(AggregateFunction.createBuiltin(DS_HLL_COUNT_DISTINCT,
                    Lists.newArrayList(t, Type.INT, Type.VARCHAR), Type.BIGINT, Type.VARBINARY,
                    true, false, true));

            // ds_theta_count_distinct(col)
            addBuiltin(AggregateFunction.createBuiltin(DS_THETA_COUNT_DISTINCT,
                    Lists.newArrayList(t), Type.BIGINT, Type.VARBINARY,
                    true, false, true));

            // HLL_RAW
            addBuiltin(AggregateFunction.createBuiltin(HLL_RAW,
                    Lists.newArrayList(t), Type.HLL, Type.VARBINARY,
                    true, false, true));

            // BITMAP_UNION_INT
            addBuiltin(AggregateFunction.createBuiltin(BITMAP_UNION_INT,
                    Lists.newArrayList(t), Type.BIGINT, Type.BITMAP,
                    true, false, true));

            // INTERSECT_COUNT
            addBuiltin(AggregateFunction.createBuiltin(INTERSECT_COUNT,
                    Lists.newArrayList(Type.BITMAP, t, t), Type.BIGINT, Type.VARCHAR, true,
                    true, false, true));
        }

        // MULTI_DISTINCT_COUNT
        for (Type type : MULTI_DISTINCT_COUNT_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.MULTI_DISTINCT_COUNT, Lists.newArrayList(type),
                    Type.BIGINT,
                    Type.VARBINARY,
                    false, true, true));

        }

        // Sum
        registerBuiltinSumAggFunction(SUM);
        // MultiDistinctSum
        registerBuiltinMultiDistinctSumAggFunction();

        // array_agg(distinct)
        registerBuiltinArrayAggDistinctFunction();

        registerBuiltinArrayUniqueAggFunction();

        // Avg
        registerBuiltinAvgAggFunction();

        // Stddev
        registerBuiltinStddevAggFunction();

        // Percentile
        registerBuiltinPercentileAggFunction();

        // map_agg
        registerBuiltinMapAggFunction();

        // HLL_UNION_AGG
        addBuiltin(AggregateFunction.createBuiltin(HLL_UNION_AGG,
                Lists.newArrayList(Type.HLL), Type.BIGINT, Type.HLL,
                true, true, true));

        // HLL_UNION
        addBuiltin(AggregateFunction.createBuiltin(HLL_UNION,
                Lists.newArrayList(Type.HLL), Type.HLL, Type.HLL,
                true, false, true));

        // HLL_RAW_AGG is alias of HLL_UNION
        addBuiltin(AggregateFunction.createBuiltin(HLL_RAW_AGG,
                Lists.newArrayList(Type.HLL), Type.HLL, Type.HLL,
                true, false, true));

        // Bitmap
        addBuiltin(AggregateFunction.createBuiltin(BITMAP_UNION, Lists.newArrayList(Type.BITMAP),
                Type.BITMAP,
                Type.BITMAP,
                true, true, true));

        // Type.VARCHAR must before all other type, so bitmap_agg(varchar) can convert to bitmap_agg(bigint)
        addBuiltin(AggregateFunction.createBuiltin(BITMAP_AGG, Lists.newArrayList(Type.BIGINT),
                Type.BITMAP, Type.BITMAP, true, false, true));
        addBuiltin(AggregateFunction.createBuiltin(BITMAP_AGG, Lists.newArrayList(Type.LARGEINT),
                Type.BITMAP, Type.BITMAP, true, false, true));
        addBuiltin(AggregateFunction.createBuiltin(BITMAP_AGG, Lists.newArrayList(Type.INT),
                Type.BITMAP, Type.BITMAP, true, false, true));
        addBuiltin(AggregateFunction.createBuiltin(BITMAP_AGG, Lists.newArrayList(Type.SMALLINT),
                Type.BITMAP, Type.BITMAP, true, false, true));
        addBuiltin(AggregateFunction.createBuiltin(BITMAP_AGG, Lists.newArrayList(Type.TINYINT),
                Type.BITMAP, Type.BITMAP, true, false, true));
        addBuiltin(AggregateFunction.createBuiltin(BITMAP_AGG, Lists.newArrayList(Type.BOOLEAN),
                Type.BITMAP, Type.BITMAP, true, false, true));

        addBuiltin(AggregateFunction.createBuiltin(BITMAP_UNION_COUNT, Lists.newArrayList(Type.BITMAP),
                Type.BIGINT,
                Type.BITMAP,
                true, true, true));
        // TODO(ml): supply function symbol
        addBuiltin(AggregateFunction.createBuiltin(BITMAP_INTERSECT, Lists.newArrayList(Type.BITMAP),
                Type.BITMAP, Type.BITMAP,
                true, false, true));

        // Retention
        addBuiltin(AggregateFunction.createBuiltin(RETENTION, Lists.newArrayList(Type.ARRAY_BOOLEAN),
                Type.ARRAY_BOOLEAN, Type.BIGINT, false, false, false));


        // Type.DATE must before Type.DATATIME, because DATE could be considered as DATETIME.
        addBuiltin(AggregateFunction.createBuiltin(WINDOW_FUNNEL,
                Lists.newArrayList(Type.BIGINT, Type.DATE, Type.INT, Type.ARRAY_BOOLEAN),
                Type.INT, Type.ARRAY_BIGINT, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(WINDOW_FUNNEL,
                Lists.newArrayList(Type.BIGINT, Type.DATETIME, Type.INT, Type.ARRAY_BOOLEAN),
                Type.INT, Type.ARRAY_BIGINT, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(WINDOW_FUNNEL,
                Lists.newArrayList(Type.BIGINT, Type.INT, Type.INT, Type.ARRAY_BOOLEAN),
                Type.INT, Type.ARRAY_BIGINT, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(WINDOW_FUNNEL,
                Lists.newArrayList(Type.BIGINT, Type.BIGINT, Type.INT, Type.ARRAY_BOOLEAN),
                Type.INT, Type.ARRAY_BIGINT, false, false, false));

        // analytic functions
        // Rank
        addBuiltin(AggregateFunction.createAnalyticBuiltin(RANK,
                Collections.emptyList(), Type.BIGINT, Type.VARBINARY));
        // Dense rank
        addBuiltin(AggregateFunction.createAnalyticBuiltin(DENSE_RANK,
                Collections.emptyList(), Type.BIGINT, Type.VARBINARY));
        // Row number
        addBuiltin(AggregateFunction.createAnalyticBuiltin(ROW_NUMBER,
                Collections.emptyList(), Type.BIGINT, Type.BIGINT));
        // Cume dist
        addBuiltin(AggregateFunction.createAnalyticBuiltin(CUME_DIST,
                Collections.emptyList(), Type.DOUBLE, Type.VARBINARY));
        // Percent rank
        addBuiltin(AggregateFunction.createAnalyticBuiltin(PERCENT_RANK,
                Collections.emptyList(), Type.DOUBLE, Type.VARBINARY));
        // Ntile
        addBuiltin(AggregateFunction.createAnalyticBuiltin(NTILE,
                Lists.newArrayList(Type.BIGINT), Type.BIGINT, Type.BIGINT));
        // Allocate session
        addBuiltin(AggregateFunction.createAnalyticBuiltin(SESSION_NUMBER,
                Lists.newArrayList(Type.BIGINT, Type.INT), Type.BIGINT, Type.BIGINT));
        addBuiltin(AggregateFunction.createAnalyticBuiltin(SESSION_NUMBER,
                Lists.newArrayList(Type.INT, Type.INT), Type.BIGINT, Type.BIGINT));
        // Approx top k
        registerBuiltinApproxTopKWindowFunction();
        // Dict merge
        addBuiltin(AggregateFunction.createBuiltin(DICT_MERGE, Lists.newArrayList(Type.VARCHAR, Type.INT),
                Type.VARCHAR, Type.VARCHAR, true, false, false));
        addBuiltin(AggregateFunction.createBuiltin(DICT_MERGE, Lists.newArrayList(Type.ARRAY_VARCHAR, Type.INT),
                Type.VARCHAR, Type.VARCHAR, true, false, false));
        // flat json meta
        addBuiltin(AggregateFunction.createBuiltin(FLAT_JSON_META, Lists.newArrayList(Type.JSON),
                Type.ARRAY_VARCHAR, Type.ARRAY_VARCHAR, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(FLAT_JSON_META, Lists.newArrayList(Type.ANY_STRUCT),
                Type.ARRAY_VARCHAR, Type.ARRAY_VARCHAR, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(FLAT_JSON_META, Lists.newArrayList(Type.ANY_MAP),
                Type.ARRAY_VARCHAR, Type.ARRAY_VARCHAR, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(FLAT_JSON_META, Lists.newArrayList(Type.ANY_ARRAY),
                Type.ARRAY_VARCHAR, Type.ARRAY_VARCHAR, false, false, false));

        for (Type t : Type.getSupportedTypes()) {
            // null/char/time is handled through type promotion
            // TODO: array/json/pseudo is not supported yet
            if (!t.canBeWindowFunctionArgumentTypes()) {
                continue;
            }
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    FIRST_VALUE, Lists.newArrayList(t), t, t));
            // Implements FIRST_VALUE for some windows that require rewrites during planning.
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    FIRST_VALUE_REWRITE, Lists.newArrayList(t, Type.BIGINT), t, t));

            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LAST_VALUE, Lists.newArrayList(t), t, t));

            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LAG, Lists.newArrayList(t, Type.BIGINT, t), t, t));
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LEAD, Lists.newArrayList(t, Type.BIGINT, t), t, t));

            // lead() and lag() the default offset and the default value should be
            // rewritten to call the overrides that take all parameters.
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LAG, Lists.newArrayList(t), t, t));
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LAG, Lists.newArrayList(t, Type.BIGINT), t, t));
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LEAD, Lists.newArrayList(t), t, t));
            addBuiltin(AggregateFunction.createAnalyticBuiltin(
                    LEAD, Lists.newArrayList(t, Type.BIGINT), t, t));
        }

        for (Type t : HISTOGRAM_TYPE) {
            addBuiltin(AggregateFunction.createBuiltin(HISTOGRAM,
                    Lists.newArrayList(t, Type.INT, Type.DOUBLE), Type.VARCHAR, Type.VARCHAR,
                    false, false, false));
        }

        // causal inference functions.
        registerBuiltinHypothesisTestingFunctions();
    }

    private void registerBuiltinHypothesisTestingFunctions() {
        // mann_whitney_u_test
        addBuiltin(AggregateFunction.createBuiltin(MANN_WHITNEY_U_TEST,
                Lists.newArrayList(Type.DOUBLE, Type.BOOLEAN, Type.VARCHAR, Type.BIGINT), Type.JSON,
                Type.VARBINARY, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(MANN_WHITNEY_U_TEST,
                Lists.newArrayList(Type.DOUBLE, Type.BOOLEAN, Type.VARCHAR), Type.JSON,
                Type.VARBINARY, false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(MANN_WHITNEY_U_TEST,
                Lists.newArrayList(Type.DOUBLE, Type.BOOLEAN), Type.JSON,
                Type.VARBINARY, false, false, false));
    }

    private void registerBuiltinSumAggFunction(String name) {
        for (ScalarType type : Type.FLOAT_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(name,
                    Lists.newArrayList(type), Type.DOUBLE, Type.DOUBLE,
                    false, true, false));
        }
        for (ScalarType type : Type.INTEGER_TYPES) {
            if (type.isLargeint()) {
                addBuiltin(AggregateFunction.createBuiltin(name,
                        Lists.newArrayList(Type.LARGEINT), Type.LARGEINT, Type.LARGEINT, false, true, false));
            } else {
                addBuiltin(AggregateFunction.createBuiltin(name,
                        Lists.newArrayList(type), Type.BIGINT, Type.BIGINT,
                        false, true, false));
            }
        }
        for (ScalarType type : Type.DECIMAL_TYPES) {
            Type retType;
            // TODO(stephen): support auto scale up decimal precision
            if (type.isDecimal256()) {
                retType = Type.DECIMAL256;
            } else {
                retType = Type.DECIMAL128;
            }
            addBuiltin(AggregateFunction.createBuiltin(name,
                    Lists.newArrayList(type), retType, retType,
                    false, true, false));
        }
        addBuiltin(AggregateFunction.createBuiltin(name,
                Lists.newArrayList(Type.DECIMALV2), Type.DECIMALV2, Type.DECIMALV2, false, true, false));
    }

    private void registerBuiltinMultiDistinctSumAggFunction() {
        for (ScalarType type : Type.FLOAT_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(MULTI_DISTINCT_SUM,
                    Lists.newArrayList(type), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
        }
        for (ScalarType type : Type.INTEGER_TYPES) {
            if (type.isLargeint()) {
                addBuiltin(AggregateFunction.createBuiltin(MULTI_DISTINCT_SUM,
                        Lists.newArrayList(type), Type.LARGEINT, Type.VARBINARY,
                        false, true, false));
            } else {
                addBuiltin(AggregateFunction.createBuiltin(MULTI_DISTINCT_SUM,
                        Lists.newArrayList(type), Type.BIGINT, Type.VARBINARY,
                        false, true, false));
            }
        }
        for (ScalarType type : Type.DECIMAL_TYPES) {
            Type retType = Type.DECIMAL128;
            // TODO(stephen): support auto scale up decimal precision
            if (type.isDecimal256()) {
                retType = Type.DECIMAL256;
            } else {
                retType = Type.DECIMAL128;
            }
            addBuiltin(AggregateFunction.createBuiltin(MULTI_DISTINCT_SUM,
                    Lists.newArrayList(type), retType, Type.VARBINARY,
                    false, true, false));
        }
        addBuiltin(AggregateFunction.createBuiltin(MULTI_DISTINCT_SUM,
                Lists.newArrayList(Type.DECIMALV2), Type.DECIMALV2, Type.VARBINARY, false, true, false));
    }

    private void registerBuiltinArrayAggDistinctFunction() {
        // array_agg(distinct)
        for (ScalarType type : Type.getNumericTypes()) {
            Type arrayType = new ArrayType(type);
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.ARRAY_AGG_DISTINCT,
                    Lists.newArrayList(type), arrayType, arrayType,
                    false, false, false));
        }
        for (ScalarType type : Type.STRING_TYPES) {
            Type arrayType = new ArrayType(type);
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.ARRAY_AGG_DISTINCT,
                    Lists.newArrayList(type), arrayType, arrayType,
                    false, false, false));
        }

        for (ScalarType type : Type.DATE_TYPES) {
            Type arrayType = new ArrayType(type);
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.ARRAY_AGG_DISTINCT,
                    Lists.newArrayList(type), arrayType, arrayType,
                    false, false, false));
        }
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.ARRAY_AGG_DISTINCT,
                Lists.newArrayList(Type.TIME), Type.ARRAY_DATETIME, Type.ARRAY_DATETIME,
                false, false, false));
    }

    private void registerBuiltinMapAggFunction() {
        for (ScalarType keyType : Type.getNumericTypes()) {
                addBuiltin(AggregateFunction.createBuiltin(FunctionSet.MAP_AGG,
                        Lists.newArrayList(keyType, Type.ANY_ELEMENT), Type.ANY_MAP, null,
                        false, false, false));
        }
        for (ScalarType keyType : Type.STRING_TYPES) {
                addBuiltin(AggregateFunction.createBuiltin(FunctionSet.MAP_AGG,
                        Lists.newArrayList(keyType, Type.ANY_ELEMENT), Type.ANY_MAP, null,
                        false, false, false));
        }

        for (ScalarType keyType : Type.DATE_TYPES) {
                addBuiltin(AggregateFunction.createBuiltin(FunctionSet.MAP_AGG,
                        Lists.newArrayList(keyType, Type.ANY_ELEMENT), Type.ANY_MAP, null,
                        false, false, false));
        }
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.MAP_AGG,
                Lists.newArrayList(Type.TIME, Type.ANY_ELEMENT), Type.ANY_MAP, null,
                false, false, false));
    }

    private void registerBuiltinArrayUniqueAggFunction() {
        // array_unique_agg mapping array_agg_distinct while array as input.
        for (ScalarType type : Type.getNumericTypes()) {
            Type arrayType = new ArrayType(type);
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.ARRAY_UNIQUE_AGG,
                    Lists.newArrayList(arrayType), arrayType, arrayType,
                    false, false, false));
        }

        for (ScalarType type : Type.STRING_TYPES) {
            Type arrayType = new ArrayType(type);
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.ARRAY_UNIQUE_AGG,
                    Lists.newArrayList(arrayType), arrayType, arrayType,
                    false, false, false));
        }

        for (ScalarType type : Type.DATE_TYPES) {
            Type arrayType = new ArrayType(type);
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.ARRAY_UNIQUE_AGG,
                    Lists.newArrayList(arrayType), arrayType, arrayType,
                    false, false, false));
        }
    }


    private void registerBuiltinAvgAggFunction() {
        // TODO: switch to CHAR(sizeof(AvgIntermediateType) when that becomes available
        for (ScalarType type : Type.FLOAT_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(AVG,
                    Lists.newArrayList(type), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
        }
        for (ScalarType type : Type.INTEGER_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(AVG,
                    Lists.newArrayList(type), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
        }
        for (ScalarType type : Type.DECIMAL_TYPES) {
            Type retType;
            // TODO(stephen): support auto scale up decimal precision
            if (type.isDecimal256()) {
                retType = Type.DECIMAL256;
            } else {
                retType = Type.DECIMAL128;
            }
            addBuiltin(AggregateFunction.createBuiltin(AVG,
                    Lists.newArrayList(type), retType, Type.VARBINARY,
                    false, true, false));
        }
        addBuiltin(AggregateFunction.createBuiltin(AVG,
                Lists.newArrayList(Type.DECIMALV2), Type.DECIMALV2, Type.VARCHAR,
                false, true, false));
        // Avg(Timestamp)
        addBuiltin(AggregateFunction.createBuiltin(AVG,
                Lists.newArrayList(Type.DATE), Type.DATE, Type.VARBINARY,
                false, true, false));
        addBuiltin(AggregateFunction.createBuiltin(AVG,
                Lists.newArrayList(Type.DATETIME), Type.DATETIME, Type.DATETIME,
                false, true, false));
    }

    private void registerBuiltinStddevAggFunction() {
        for (Type t : STDDEV_ARG_TYPE) {
            addBuiltin(AggregateFunction.createBuiltin(STDDEV,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(STDDEV_SAMP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(STDDEV_POP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(STD,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(VARIANCE,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(VARIANCE_SAMP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(VAR_SAMP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(VARIANCE_POP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(VAR_POP,
                    Lists.newArrayList(t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(COVAR_POP,
                    Lists.newArrayList(t, t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(COVAR_SAMP,
                    Lists.newArrayList(t, t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
            addBuiltin(AggregateFunction.createBuiltin(CORR,
                    Lists.newArrayList(t, t), Type.DOUBLE, Type.VARBINARY,
                    false, true, false));
        }
    }

    private void registerBuiltinPercentileAggFunction() {
        // PercentileApprox
        addBuiltin(AggregateFunction.createBuiltin(PERCENTILE_APPROX,
                Lists.newArrayList(Type.DOUBLE, Type.DOUBLE), Type.DOUBLE, Type.VARBINARY,
                false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(PERCENTILE_APPROX,
                Lists.newArrayList(Type.DOUBLE, Type.DOUBLE, Type.DOUBLE), Type.DOUBLE, Type.VARBINARY,
                false, false, false));
        // percentile_approx_weighted
        addBuiltin(AggregateFunction.createBuiltin(PERCENTILE_APPROX_WEIGHTED,
                Lists.newArrayList(Type.DOUBLE, Type.BIGINT, Type.DOUBLE), Type.DOUBLE, Type.VARBINARY,
                false, false, false));
        // percentile_approx_weighted
        addBuiltin(AggregateFunction.createBuiltin(PERCENTILE_APPROX_WEIGHTED,
                Lists.newArrayList(Type.DOUBLE, Type.BIGINT, Type.DOUBLE, Type.DOUBLE), Type.DOUBLE, Type.VARBINARY,
                false, false, false));

        addBuiltin(AggregateFunction.createBuiltin(PERCENTILE_UNION,
                Lists.newArrayList(Type.PERCENTILE), Type.PERCENTILE, Type.PERCENTILE,
                false, false, false));

        // PercentileCont
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.PERCENTILE_CONT,
                Lists.newArrayList(Type.DATE, Type.DOUBLE), Type.DATE, Type.VARBINARY,
                false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.PERCENTILE_CONT,
                Lists.newArrayList(Type.DATETIME, Type.DOUBLE), Type.DATETIME, Type.VARBINARY,
                false, false, false));
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.PERCENTILE_CONT,
                Lists.newArrayList(Type.DOUBLE, Type.DOUBLE), Type.DOUBLE, Type.VARBINARY,
                false, false, false));

        for (Type type : SORTABLE_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.PERCENTILE_DISC,
                    Lists.newArrayList(type, Type.DOUBLE), type, Type.VARBINARY,
                    false, false, false));
        }
        for (Type type : SORTABLE_TYPES) {
            addBuiltin(AggregateFunction.createBuiltin(FunctionSet.LC_PERCENTILE_DISC,
                    Lists.newArrayList(type, Type.DOUBLE), type, Type.VARBINARY,
                    false, false, false));
        }
    }

    private void registerBuiltinApproxTopKWindowFunction() {
        java.util.function.Consumer<ScalarType> registerBuiltinForType = (ScalarType type) -> {
            ArrayType retType = APPROX_TOP_N_RET_TYPE_BUILDER.apply(type);
            addBuiltin(AggregateFunction.createBuiltin(APPROX_TOP_K,
                    Lists.newArrayList(type), retType, Type.VARBINARY,
                    false, true, true));
            addBuiltin(AggregateFunction.createBuiltin(APPROX_TOP_K,
                    Lists.newArrayList(type, Type.INT), retType, Type.VARBINARY,
                    false, true, true));
            addBuiltin(AggregateFunction.createBuiltin(APPROX_TOP_K,
                    Lists.newArrayList(type, Type.INT, Type.INT), retType, Type.VARBINARY,
                    false, true, true));
        };
        java.util.function.Consumer<List<ScalarType>> registerBuiltinForTypes = (List<ScalarType> types) -> {
            for (ScalarType type : types) {
                registerBuiltinForType.accept(type);
            }
        };
        registerBuiltinForTypes.accept(Type.FLOAT_TYPES);
        registerBuiltinForTypes.accept(Type.INTEGER_TYPES);
        registerBuiltinForTypes.accept(Type.DECIMAL_TYPES);
        registerBuiltinForTypes.accept(Type.STRING_TYPES);
        registerBuiltinForTypes.accept(Type.DATE_TYPES);
    }

    public List<Function> getBuiltinFunctions() {
        List<Function> builtinFunctions = Lists.newArrayList();
        for (Map.Entry<String, List<Function>> entry : vectorizedFunctions.entrySet()) {
            builtinFunctions.addAll(entry.getValue());
        }
        return builtinFunctions;
    }

    // Boolean aggregate functions
    private void addBooleanAggregateFunctions() {
        // BOOL_OR: Returns true if any value in the expression is true
        addBuiltin(AggregateFunction.createBuiltin(FunctionSet.BOOL_OR,
                Lists.newArrayList(Type.BOOLEAN),
                Type.BOOLEAN, Type.BOOLEAN,
                false, true, false));
    }
}
