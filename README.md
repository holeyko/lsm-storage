# LSM Key-Value Storage
It's a key-value storage that implements LSM. The project is under development, so some methods are not thread-safe.

Storage supports methods:
- `upsert` &#8211; a thread-safe operation, that adds entry in the storage (if you want to delete entry by the key, pass `null` as the value)
- `get` &#8211; a thread-safe operation, that lazy returns an entry or a range of entries by the key or border keys corresponding.
- `all` &#8211; a thread-safe operation, that lazy return all entries in the storage.
- `allFrom` &#8211; a thread-safe operation, that lazy return all entries in the storage inclusive from passed key.
- `allTo` &#8211; a thread-safe operation, that lazy return all entries in the storage exclusive to passed key.
- `compact` &#8211; a operation, that compacts SSTables in one SSTable.
- `close` &#8211; a thread-safe operation, that safity saves entries from the memory table to a disk.

Project was written on Java 21 with preview features.
