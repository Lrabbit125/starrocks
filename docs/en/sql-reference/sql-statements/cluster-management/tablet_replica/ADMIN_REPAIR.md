---
displayed_sidebar: docs
---

# ADMIN REPAIR

ADMIN REPAIR is used to try and fix the specified tables or partitions first.

:::tip

This operation requires the SYSTEM-level OPERATE privilege. You can follow the instructions in [GRANT](../../account-management/GRANT.md) to grant this privilege.

:::

## Syntax

```sql
ADMIN REPAIR TABLE table_name[ PARTITION (p1,...)]
```

Note:

1. This statement only means that the system attempts to repair sharding replicas of specified tables or partitions with high priority without the guarantee that the repair will be successful. Users can view the repair status through ADMIN SHOW REPLICA STATUS command.
2. The default timeout is 14400 seconds (4 hours). Timeout means the system will not repair sharding replicas of specified tables or partitions with high priority. In case of timeout, the command needs to be used again for the intended settings.

## Examples

1. Attempt to repair specified tables

    ```sql
    ADMIN REPAIR TABLE tbl1;
    ```

2. Attempt to fix specified partition

    ```sql
    ADMIN REPAIR TABLE tbl1 PARTITION (p1, p2);
    ```
