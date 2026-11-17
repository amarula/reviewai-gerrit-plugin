---
name: code-review
description: Additional code review guidance for this project.
---

Check that every tenant-scoped query filters by tenant ID.
Flag logging of access tokens or customer credentials.

## Examples

```java
repository.findByTenantId(tenantId);
```
