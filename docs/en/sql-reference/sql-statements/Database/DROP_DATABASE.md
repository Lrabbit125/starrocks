---
displayed_sidebar: docs
---

# DROP DATABASE

DROP DATABASE is used to delete a database in StarRocks.

> **NOTE**
>
> This operation requires the DROP privilege on the destination database.

## Syntax

```sql
DROP DATABASE [IF EXISTS] <db_name> [FORCE]
```

Take note of the following points:

- After executing DROP DATABASE to drop a database, you can restore the dropped database by using the [RECOVER](../backup_restore/RECOVER.md) statement within a specified retention period (the default retention period spans one day), but the pipes (supported from v3.2 onwards) that have been dropped along with the database cannot be recovered.
- If you execute `DROP DATABASE FORCE` to drop a database, the database is deleted directly without any checks on whether there are unfinished activities in it and cannot be recovered. Generally, this operation is not recommended.
- If you drop a database, all pipes (supported from v3.2 onwards) that belong to the database are dropped along with the database.

## Examples

1. Drop database db_text.

    ```sql
    DROP DATABASE db_test;
    ```

## References

- [CREATE DATABASE](CREATE_DATABASE.md)
- [SHOW CREATE DATABASE](SHOW_CREATE_DATABASE.md)
- [USE](USE.md)
- [DESC](../table_bucket_part_index/DESCRIBE.md)
- [SHOW DATABASES](SHOW_DATABASES.md)
