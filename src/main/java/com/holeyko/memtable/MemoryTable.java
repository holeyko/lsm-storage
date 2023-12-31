package com.holeyko.memtable;

import com.holeyko.entry.Entry;
import com.holeyko.exception.MemoryTableOutOfMemoryException;
import com.holeyko.iterators.MemoryMergeIterators;
import com.holeyko.sstable.SSTableManager;
import com.holeyko.utils.IteratorUtils;
import com.holeyko.utils.MemorySegmentUtils;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MemoryTable {
    private final Logger log = Logger.getLogger(MemoryTable.class.getName());

    private final AtomicReference<ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>>> memTable =
            new AtomicReference<>(createMap());
    private final AtomicReference<ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>>> flushTable =
            new AtomicReference<>(null);
    private final ExecutorService flushWorker = Executors.newSingleThreadExecutor();
    private final SSTableManager ssTableManager;
    private final long flushThresholdBytes;
    private AtomicLong usedSpace = new AtomicLong();
    private AtomicBoolean wasDropped = new AtomicBoolean(true);
    private Future<?> flushFuture = CompletableFuture.completedFuture(null);

    public MemoryTable(SSTableManager ssTableManager, long flushThresholdBytes) {
        this.ssTableManager = ssTableManager;
        this.flushThresholdBytes = flushThresholdBytes;
    }

    public Entry<MemorySegment> get(MemorySegment key) {
        Entry<MemorySegment> entry = memTable.get().get(key);
        if (entry == null) {
            NavigableMap<MemorySegment, Entry<MemorySegment>> curFlushTable = flushTable.get();
            if (curFlushTable != null) {
                entry = curFlushTable.get(key);
            }
        }

        return entry;
    }

    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return new MemoryMergeIterators(
                getIterator(memTable.get(), from, to),
                getIterator(flushTable.get(), from, to)
        );
    }

    private Iterator<Entry<MemorySegment>> getIterator(
            ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> table,
            MemorySegment from,
            MemorySegment to
    ) {
        if (table == null) {
            return IteratorUtils.emptyIterator();
        }

        if (from == null && to == null) {
            return table.values().iterator();
        } else if (from == null) {
            return table.headMap(to).values().iterator();
        } else if (to == null) {
            return table.tailMap(from).values().iterator();
        }
        return table.subMap(from, to).values().iterator();
    }

    public void upsert(Entry<MemorySegment> entry) {
        Objects.requireNonNull(entry);

        long newSize = getEntrySize(entry);
        long prevSize = 0;
        Entry<MemorySegment> prev = memTable.get().putIfAbsent(entry.key(), new ChangeableEntryWithLock<>(entry));

        if (prev != null) {
            prevSize = getValueSize(((ChangeableEntryWithLock<MemorySegment>) prev).getAndSet(entry.value()));
            newSize = getValueSize(entry.value());
        }

        if (usedSpace.addAndGet(newSize - prevSize) < flushThresholdBytes) {
            return;
        }

        if (wasDropped.compareAndSet(true, false)) {
            if (!flushFuture.isDone() && usedSpace.get() >= flushThresholdBytes) {
                wasDropped.set(true);
                throw new MemoryTableOutOfMemoryException();
            } else {
                if (!flush(false)) {
                    wasDropped.set(true);
                }
            }
        }
    }

    private long getEntrySize(Entry<MemorySegment> entry) {
        if (entry == null) {
            return 0;
        }
        return entry.key().byteSize() + getValueSize(entry.value());
    }

    private long getValueSize(MemorySegment value) {
        if (value == null) {
            return 0;
        }
        return value.byteSize();
    }

    public boolean flush(boolean importantFlush) {
        if (!importantFlush && (memTable.get().isEmpty() || !flushFuture.isDone())) {
            return false;
        }

        flushFuture = flushWorker.submit(() -> {
            flushTable.set(memTable.get());
            memTable.set(createMap());
            usedSpace.set(0);
            wasDropped.set(true);

            try {
                ArrayList<Entry<MemorySegment>> entries = new ArrayList<>(flushTable.get().values());
                ssTableManager.saveEntries(entries::iterator);
            } catch (Exception e) {
                log.log(Level.WARNING, "Flushing was failed", e);
            } finally {
                flushTable.set(null);
            }
        });
        return true;
    }

    public void close() throws IOException {
        try {
            if (!flushWorker.isShutdown()) {
                flush(true);
                flushFuture.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.log(Level.WARNING, "Closing was failed", e.getCause());
            if (e.getCause() instanceof IOException ioEx) {
                throw ioEx;
            }
        } finally {
            flushWorker.close();
        }
    }

    private static ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> createMap() {
        return new ConcurrentSkipListMap<>(MemorySegmentUtils::compareMemorySegments);
    }
}
