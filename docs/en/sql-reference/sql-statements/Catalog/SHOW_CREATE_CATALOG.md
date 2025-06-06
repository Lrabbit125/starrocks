---
displayed_sidebar: docs
---

# SHOW CREATE CATALOG

SHOW CREATE CATALOG queries the creation statement of an [external catalog](../../../data_source/catalog/catalog_overview.md).

Note that authentication-related information in the return result will be anonymized.

This command is supported from v3.0 onwards.

## Syntax

```SQL
SHOW CREATE CATALOG <catalog_name>;
```

## Parameters

| **Parameter** | **Required** | **Description**                                              |
| ------------- | ------------ | ------------------------------------------------------------ |
| catalog_name  | Yes          | The name of the catalog whose creation statement you want to view. |

## Return result

```Plain
+------------+-----------------+
| Catalog    | Create Catalog  |
+------------+-----------------+
```

| **Field**  | **Description**                                        |
| -------------- | ------------------------------------------------------ |
| Catalog        | The name of the catalog.                               |
| Create Catalog | The statement that was executed to create the catalog. |

## Examples

The following example queries the creation statement of a Hive catalog named `hive_catalog_hms`:

```SQL
SHOW CREATE CATALOG hive_catalog_hms;
```

The return result is as follows:

```SQL
CREATE EXTERNAL CATALOG `hive_catalog_hms`
PROPERTIES ("aws.s3.access_key"  =  "AK******M4",
"hive.metastore.type"  =  "glue",
"aws.s3.secret_key"  =  "iV******iD",
"aws.glue.secret_key"  =  "iV******iD",
"aws.s3.use_instance_profile"  =  "false",
"aws.s3.region"  =  "us-west-1",
"aws.glue.region"  =  "us-west-1",
"type"  =  "hive",
"aws.glue.access_key"  =  "AK******M4"
)
```
