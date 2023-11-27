# LSM Key-Value Storage
It's a key-value storage that implements LSM. All methods are thread-safe.

Storage supports methods:
- `upsert` &#8211; adds entry in the storage (if you want to delete entry by the key, pass `null` as the value)
- `get` &#8211; lazy returns an entry or a range of entries by the key or border keys corresponding.
- `all` &#8211; lazy return all entries in the storage.
- `allFrom` &#8211; lazy return all entries in the storage inclusive from passed key.
- `allTo` &#8211; lazy return all entries in the storage exclusive to passed key.
- `compact` &#8211; compacts SSTables in one SSTable in the background.
- `flush` &#8211; safity saves entries from the memory table to the disk, it starts automatically if the amount of memory used in the memory table is greater than `flushThresholdBytes`
- `close` &#8211; calls `flush` and close all used resources

Project was written on Java 21 with preview features.
