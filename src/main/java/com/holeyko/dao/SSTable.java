package com.holeyko.dao;

import com.holeyko.entry.BaseEntry;
import com.holeyko.utils.FileUtils;
import com.holeyko.utils.MemorySegmentUtils;
import com.holeyko.entry.Entry;
import com.holeyko.iterators.FutureIterator;
import com.holeyko.iterators.LazyIterator;
import com.holeyko.utils.NumberUtils;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

public class SSTable {
    private final Path parentPath;
    private final long id;
    private final MemorySegment data;
    private final int countRecords;

    public SSTable(Path parentPath, long id, Arena arena) throws IOException {
        this.parentPath = parentPath;
        this.id = id;
        Path dataFile = getDataFilePath();

        try (FileChannel dataFileChannel = FileChannel.open(dataFile, READ)) {
            this.data = dataFileChannel.map(MapMode.READ_ONLY, 0, dataFileChannel.size(), arena);
            this.countRecords = (int) (this.data.get(ValueLayout.JAVA_LONG, 0) / Long.BYTES);
        }
    }

    public Entry<MemorySegment> findEntry(MemorySegment key) {
        int offsetIndex = binSearchIndex(key, true);
        if (offsetIndex < 0) {
            return null;
        }
        return new BaseEntry<>(key, readValue(getRecordInfo(getOffset(offsetIndex))));
    }

    public FutureIterator<Entry<MemorySegment>> findEntries(MemorySegment from, MemorySegment to) {
        int fromIndex = 0;
        int toIndex = countRecords;
        if (from != null) {
            int fromOffsetIndex = binSearchIndex(from, true);
            fromIndex = fromOffsetIndex < 0 ? -(fromOffsetIndex + 1) : fromOffsetIndex;
        }
        if (to != null) {
            int toOffsetIndex = binSearchIndex(to, false);
            toIndex = toOffsetIndex < 0 ? -toOffsetIndex : toOffsetIndex;
        }

        Iterator<Long> offsetsIterator = getOffsetIterator(fromIndex, toIndex);
        return new LazyIterator<>(
                () -> {
                    RecordInfo recordInfo = getRecordInfo(offsetsIterator.next());
                    return new BaseEntry<>(readKey(recordInfo), readValue(recordInfo));
                },
                offsetsIterator::hasNext
        );
    }

    private int binSearchIndex(MemorySegment key, boolean lowerBound) {
        int l = -1;
        int r = countRecords;
        while (l + 1 < r) {
            int mid = (l + r) / 2;
            RecordInfo recordInfo = getRecordInfo(getOffset(mid));
            int compareResult = MemorySegmentUtils.compareMemorySegments(
                    data, recordInfo.keyOffset(), recordInfo.valueOffset(),
                    key, 0, key.byteSize()
            );

            if (compareResult == 0) {
                return mid;
            } else if (compareResult > 0) {
                r = mid;
            } else {
                l = mid;
            }
        }
        return lowerBound ? -r - 1 : -l - 1;
    }

    private RecordInfo getRecordInfo(long recordOffset) {
        long curOffset = recordOffset + 1;
        byte sizeInfo = data.get(ValueLayout.JAVA_BYTE, curOffset++);
        int keySizeSize = sizeInfo >> 4;
        int valueSizeSize = sizeInfo & 0xf;

        byte[] keySizeInBytes = new byte[keySizeSize];
        for (int i = 0; i < keySizeSize; ++i) {
            keySizeInBytes[i] = data.get(ValueLayout.JAVA_BYTE, curOffset++);
        }
        byte[] valueSizeInBytes = new byte[valueSizeSize];
        for (int i = 0; i < valueSizeSize; ++i) {
            valueSizeInBytes[i] = data.get(ValueLayout.JAVA_BYTE, curOffset++);
        }

        long keySize = NumberUtils.fromBytes(keySizeInBytes);
        long valueSize = NumberUtils.fromBytes(valueSizeInBytes);
        byte meta = data.get(ValueLayout.JAVA_BYTE, recordOffset);
        return new RecordInfo(meta, keySize, curOffset, valueSize, curOffset + keySize);
    }

    private MemorySegment readKey(RecordInfo recordInfo) {
        return data.asSlice(recordInfo.keyOffset(), recordInfo.keySize());
    }

    private MemorySegment readValue(RecordInfo recordInfo) {
        if (SSTableMeta.isRemovedValue(recordInfo.meta())) {
            return null;
        }
        return data.asSlice(recordInfo.valueOffset(), recordInfo.valueSize());
    }

    private long getOffset(int index) {
        return data.get(ValueLayout.JAVA_LONG, index * Long.BYTES);
    }

    private Iterator<Long> getOffsetIterator(int fromIndex, int toIndex) {
        return new Iterator<>() {
            private int curIndex = fromIndex;

            @Override
            public boolean hasNext() {
                return curIndex < toIndex;
            }

            @Override
            public Long next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return getOffset(curIndex++);
            }
        };
    }

    public static void save(
            Path prefix,
            long id,
            Iterable<Entry<MemorySegment>> entries,
            Arena arena
    ) throws IOException {
        Path tmpDataFile = FileUtils.makePath(prefix, Long.toString(id), FileUtils.TMP_FILE_EXT);
        try (FileChannel dataFileChannel = FileChannel.open(tmpDataFile, CREATE, WRITE, READ)) {
            long dataSize = 0;
            int countRecords = 0;
            for (Entry<MemorySegment> entry : entries) {
                ++countRecords;
                dataSize += 2;
                MemorySegment key = entry.key();
                MemorySegment value = entry.value();

                dataSize += NumberUtils.toBytes(key.byteSize()).length + key.byteSize();
                if (value != null) {
                    dataSize += NumberUtils.toBytes(value.byteSize()).length + value.byteSize();
                }
            }

            if (countRecords == 0) {
                Files.deleteIfExists(tmpDataFile);
                return;
            }

            long dataOffset = countRecords * Long.BYTES;
            MemorySegment dataSegment = dataFileChannel.map(
                MapMode.READ_WRITE,
                0,
                dataSize + dataOffset,
                arena
            );

            int curEntryNumber = 0;
            for (Entry<MemorySegment> entry : entries) {
                dataSegment.set(ValueLayout.JAVA_LONG, curEntryNumber * Long.BYTES, dataOffset);

                MemorySegment key = entry.key();
                MemorySegment value = entry.value();
                byte[] keySizeInBytes = NumberUtils.toBytes(key.byteSize());
                byte[] valueSizeInBytes = value == null
                        ? new byte[0]
                        : NumberUtils.toBytes(value.byteSize());

                byte meta = SSTableMeta.buildMeta(entry);
                byte sizeInfo = (byte) ((keySizeInBytes.length << 4) | valueSizeInBytes.length);
                dataSegment.set(ValueLayout.JAVA_BYTE, dataOffset++, meta);
                dataSegment.set(ValueLayout.JAVA_BYTE, dataOffset++, sizeInfo);

                MemorySegmentUtils.copyByteArray(keySizeInBytes, dataSegment, dataOffset);
                dataOffset += keySizeInBytes.length;
                MemorySegmentUtils.copyByteArray(valueSizeInBytes, dataSegment, dataOffset);
                dataOffset += valueSizeInBytes.length;
                MemorySegment.copy(key, 0, dataSegment, dataOffset, key.byteSize());
                dataOffset += key.byteSize();
                if (value != null) {
                    MemorySegment.copy(value, 0, dataSegment, dataOffset, value.byteSize());
                    dataOffset += value.byteSize();
                }

                ++curEntryNumber;
            }
        }

        Path dataFile = FileUtils.makePath(prefix, Long.toString(id), FileUtils.DATA_FILE_EXT);
        Files.move(tmpDataFile, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public void delete() throws IOException {
        Files.deleteIfExists(getDataFilePath());
    }

    private Path getDataFilePath() {
        return FileUtils.makePath(parentPath, Long.toString(id), FileUtils.DATA_FILE_EXT);
    }

    public long getId() {
        return id;
    }

    private static final class SSTableMeta {
        private static final byte REMOVE_VALUE = 0x1;

        public static boolean isRemovedValue(byte meta) {
            return (meta & REMOVE_VALUE) == REMOVE_VALUE;
        }

        public static byte buildMeta(Entry<MemorySegment> entry) {
            byte meta = 0;

            if (entry.value() == null) {
                meta |= SSTableMeta.REMOVE_VALUE;
            }
            return meta;
        }

        private SSTableMeta() {
        }
    }

    private static final class RecordInfo {
        private final byte meta;
        private final long keySize;
        private final long keyOffset;
        private final long valueSize;
        private final long valueOffset;

        private RecordInfo(
                byte meta,
                long keySize,
                long keyOffset,
                long valueSize,
                long valueOffset
        ) {
            this.meta = meta;
            this.keySize = keySize;
            this.keyOffset = keyOffset;
            this.valueSize = valueSize;
            this.valueOffset = valueOffset;
        }

        public byte meta() {
            return meta;
        }

        public long keySize() {
            return keySize;
        }

        public long keyOffset() {
            return keyOffset;
        }

        public long valueSize() {
            return valueSize;
        }

        public long valueOffset() {
            return valueOffset;
        }
    }
}
