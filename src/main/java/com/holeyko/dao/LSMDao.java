package com.holeyko.dao;

import com.holeyko.entry.Entry;
import com.holeyko.iterators.EntrySkipNullsIterator;
import com.holeyko.iterators.FutureIterator;
import com.holeyko.iterators.GatheringIterator;
import com.holeyko.iterators.LazyIterator;
import com.holeyko.iterators.PriorityIterator;
import com.holeyko.memtable.MemoryTable;
import com.holeyko.sstable.SSTableManager;
import com.holeyko.utils.MemorySegmentUtils;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class LSMDao implements Dao<MemorySegment, Entry<MemorySegment>> {
    private final MemoryTable memTable;
    private final SSTableManager ssTableManager;

    public LSMDao() throws IOException {
        this(null);
    }

    public LSMDao(Config config) throws IOException {
        this.ssTableManager = new SSTableManager(config.basePath());
        long flushThresholdBytes = config.flushThresholdBytes();
        if (flushThresholdBytes == 0) {
            flushThresholdBytes = Long.MAX_VALUE / 2;
        }
        this.memTable = new MemoryTable(ssTableManager, flushThresholdBytes);
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        Entry<MemorySegment> result = memTable.get(key);

        if (result == null && existsSSTableManager()) {
            result = ssTableManager.load(key);
        }
        return handleDeletededEntry(result);
    }

    @Override
    public Iterator<Entry<MemorySegment>> allFrom(MemorySegment from) {
        return get(from, null);
    }

    @Override
    public Iterator<Entry<MemorySegment>> allTo(MemorySegment to) {
        return get(null, to);
    }

    @Override
    public Iterator<Entry<MemorySegment>> all() {
        return get(null, null);
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return makeIteratorWithSkipNulls(from, to);
    }

    private Entry<MemorySegment> handleDeletededEntry(Entry<MemorySegment> entry) {
        if (entry == null || entry.value() == null) {
            return null;
        }
        return entry;
    }

    private FutureIterator<Entry<MemorySegment>> makeIteratorWithSkipNulls(
            MemorySegment from,
            MemorySegment to
    ) {
        Iterator<Entry<MemorySegment>> memoryIterator = memTable.get(from, to);
        if (!existsSSTableManager() || ssTableManager.size() == 0) {
            return new EntrySkipNullsIterator(memoryIterator);
        }

        int priority = 0;
        List<FutureIterator<Entry<MemorySegment>>> loadedIterators = ssTableManager.load(from, to);
        List<PriorityIterator<Entry<MemorySegment>>> priorityIterators = new ArrayList<>();

        for (FutureIterator<Entry<MemorySegment>> it : loadedIterators) {
            priorityIterators.add(new PriorityIterator<>(it, priority++));
        }
        if (memoryIterator.hasNext()) {
            priorityIterators.add(new PriorityIterator<>(new LazyIterator<>(memoryIterator), priority));
        }

        GatheringIterator<Entry<MemorySegment>> gatheringIterator = new GatheringIterator<>(
                priorityIterators,
                Comparator.comparing(
                        (PriorityIterator<Entry<MemorySegment>> it) -> it.showNext().key(),
                        MemorySegmentUtils::compareMemorySegments
                ).thenComparing(Comparator.comparingInt((PriorityIterator<?> it) -> it.getPriority()).reversed()),
                Comparator.comparing(Entry::key, MemorySegmentUtils::compareMemorySegments)
        );

        return new EntrySkipNullsIterator(gatheringIterator);
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        memTable.upsert(entry);
    }

    @Override
    public void close() throws IOException {
        memTable.close();
        ssTableManager.close();
    }

    @Override
    public void flush() throws IOException {
        memTable.flush(false);
    }

    @Override
    public void compact() throws IOException {
        if (!existsSSTableManager()) {
            return;
        }

        ssTableManager.compact();
    }

    private boolean existsSSTableManager() {
        return ssTableManager != null;
    }
}
